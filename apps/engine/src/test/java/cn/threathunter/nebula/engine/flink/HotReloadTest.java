package cn.threathunter.nebula.engine.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 策略热更新。
 *
 * <p><b>这组测试要证明的核心事实只有一条:换策略之后,已累积的窗口状态还在。</b>
 *
 * <p>如果重载时重建变量图,所有窗口计数会归零 —— 「IP 5 分钟内登录失败次数」在改完
 * 阈值的那一刻变回 0,攻击正好在那个窗口里溜过去。这类失效是<b>静默</b>的:不报错、
 * 不中断,只是告警在那一刻之后少了一批。所以必须构造「窗口内已累积 N 次 → 热更新 →
 * 第 N+1 次事件」的场景,断言计数是 N+1 而不是 1。
 */
class HotReloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SEEDS = Path.of("../../seeds");

    private static List<Map<String, Object>> loadDir(String sub) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(SEEDS.resolve(sub))) {
            for (Path p : s.sorted().toList()) {
                String f = p.getFileName().toString();
                if (!f.endsWith(".json") || f.equals("index.json")) {
                    continue;
                }
                out.add(MAPPER.readValue(Files.readString(p),
                        new TypeReference<Map<String, Object>>() {
                        }));
            }
        }
        return out;
    }

    /** 只保留一条策略,便于精确断言。 */
    private static List<Map<String, Object>> only(List<Map<String, Object>> all, String name) {
        return all.stream().filter(s -> name.equals(s.get("name"))).toList();
    }

    /** 改掉某条策略里所有 counter 比较的阈值。 */
    private static List<Map<String, Object>> withThreshold(List<Map<String, Object>> strategies,
                                                           String newThreshold) throws IOException {
        String json = MAPPER.writeValueAsString(strategies);
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("x", json);
        List<Map<String, Object>> copy = MAPPER.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {
                });
        for (Map<String, Object> s : copy) {
            retarget(s, newThreshold);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void retarget(Object node, String newThreshold) {
        if (node instanceof Map<?, ?> m) {
            Object left = m.get("left");
            if (left instanceof Map<?, ?> l && "counter".equals(l.get("kind"))) {
                Object right = m.get("right");
                if (right instanceof Map) {
                    ((Map<String, Object>) right).put("value", newThreshold);
                }
            }
            for (Object v : m.values()) {
                retarget(v, newThreshold);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                retarget(v, newThreshold);
            }
        }
    }

    /** 与 golden 向量同形态的动态请求事件。 */
    private static Map<String, Object> dynamicVisit(String ip, long ts) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", "HTTP_DYNAMIC");
        e.put("timestamp", ts);
        e.put("c_ip", ip);
        e.put("page", "/p/1");
        e.put("uri_stem", "/p/1");
        e.put("method", "GET");
        return e;
    }

    private static Map<String, Object> loginFailure(String ip, long ts) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", "ACCOUNT_LOGIN");
        e.put("timestamp", ts);
        e.put("c_ip", ip);
        e.put("uid", "u-" + (ts % 7));
        e.put("did", "d1");
        e.put("page", "/api/login");
        e.put("result", "F");
        return e;
    }

    /** 收集器,只记录命中的策略名。 */
    private static final class Sink implements Collector<StrategyEngine.Notice> {
        final List<StrategyEngine.Notice> got = new ArrayList<>();

        @Override
        public void collect(StrategyEngine.Notice n) {
            got.add(n);
        }

        @Override
        public void close() {
        }
    }

    private static RiskDetectionFunction fresh(List<Map<String, Object>> strategies)
            throws Exception {
        RiskDetectionFunction f = new RiskDetectionFunction(
                strategies, loadDir("variables"), loadDir("events"));
        f.open(new org.apache.flink.configuration.Configuration());
        return f;
    }

    private static void feed(RiskDetectionFunction f, Map<String, Object> event, Sink sink)
            throws Exception {
        f.processElement(event, null, sink);
    }

    // ---------------------------------------------------------------- 测试

    @Test
    @DisplayName("热更新后窗口计数不归零 —— 这是整个特性成立的前提")
    void windowStateSurvivesReload() throws Exception {
        List<Map<String, Object>> all = loadDir("strategies");
        List<Map<String, Object>> one = only(all, "IP多次登录失败");
        assertFalse(one.isEmpty(), "测试依赖的策略不存在");

        RiskDetectionFunction f = fresh(one);
        Sink sink = new Sink();
        long t0 = 1_700_000_000_000L;
        String ip = "198.51.100.5";

        // 原阈值是 >5。先灌 5 条失败:第 5 条时计数为 5,不满足 >5,应当不命中。
        for (int i = 0; i < 5; i++) {
            feed(f, loginFailure(ip, t0 + i * 1000L), sink);
        }
        int before = sink.got.size();

        // 热更新:策略内容不变(只是重新下发一次)
        f.reload(one, loadDir("variables"), 42L);

        // 再灌一条。如果状态没丢,窗口内累计是 6 > 5,应当命中。
        // 如果重载把图重建了,这一条会被当作窗口内的第 1 条,永远不会命中。
        feed(f, loginFailure(ip, t0 + 5000L), sink);

        assertTrue(sink.got.size() > before,
                "热更新后第 6 条失败没有命中 —— 说明窗口计数被重置了。"
                        + "重载前命中 " + before + " 条,重载后 " + sink.got.size() + " 条");
    }

    @Test
    @DisplayName("热更新真的会改变判定 —— 否则上面那条测试可能只是碰巧通过")
    void reloadActuallyChangesBehaviour() throws Exception {
        List<Map<String, Object>> one = only(loadDir("strategies"), "IP多次登录失败");
        RiskDetectionFunction f = fresh(one);
        Sink sink = new Sink();
        long t0 = 1_700_000_000_000L;
        String ip = "198.51.100.6";

        // 阈值 >5,灌 3 条不该命中
        for (int i = 0; i < 3; i++) {
            feed(f, loginFailure(ip, t0 + i * 1000L), sink);
        }
        assertEquals(0, sink.got.size(), "阈值 >5 时 3 条失败不该命中");

        // 把阈值改成 >2 并热更新。已累积的 3 条仍在,下一条应当立刻命中。
        f.reload(withThreshold(one, "2"), loadDir("variables"), 43L);
        feed(f, loginFailure(ip, t0 + 3000L), sink);

        assertTrue(sink.got.size() > 0,
                "阈值降到 >2 后仍未命中 —— 要么新策略没生效,要么状态丢了");
    }

    @Test
    @DisplayName("变量图的累积状态在 extendTo 之后仍然存在")
    void namedVariableStateSurvivesExtend() throws Exception {
        // 上面几条覆盖的是内联 counter(状态在 LocalValueProvider)。变量图是另一条
        // 独立的状态存放路径,只测其中一条会让另一条的重建缺陷完全不被发现 ——
        // 这个盲区是靠注入缺陷发现的:把 extendTo 换成重建 VariableGraph,
        // 当时已有的 5 条测试一条都没失败。
        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : loadDir("variables")) {
            vars.add(new VariableDef(m));
        }
        EventModel em = new EventModel(loadDir("events"));
        // 用与 golden 向量同一组事件/变量,确保基线本身是已被验证过的
        String target = "ip__visit_dynamic_count__5m__rt";

        VariableGraph g = new VariableGraph(vars, Set.of(target), em);
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 7; i++) {
            g.process(dynamicVisit("198.51.100.9", t0 + i * 1000L), "HTTP_DYNAMIC", t0 + i * 1000L);
        }
        Map<String, Object> probe = dynamicVisit("198.51.100.9", t0 + 7000L);
        Object before = g.valueOf(target, probe, t0 + 7000L);
        assertEquals(7L, ((Number) before).longValue(), "基线:7 条事件应累积为 7");

        // 扩展图 —— 模拟热更新时新策略引用了更多变量
        Set<String> cold = g.extendTo(vars, Set.of(target, "ip__visit_count__5m__rt"));
        assertTrue(cold.contains("ip__visit_count__5m__rt"), "新引用的变量应被报告为冷启动");
        assertFalse(cold.contains(target), "已有变量不该被当成新建的");

        Object after = g.valueOf(target, probe, t0 + 7000L);
        assertEquals(7L, ((Number) after).longValue(),
                "extendTo 之后累积值变了 —— 说明节点被重建、状态丢了。"
                        + "改一个阈值的代价不该是丢掉全部在途状态");

        // 再灌一条,应当继续累加而不是从 1 开始
        g.process(loginFailure("198.51.100.9", t0 + 8000L), "ACCOUNT_LOGIN", t0 + 8000L);
        Object grown = g.valueOf(target, probe, t0 + 8000L);
        assertEquals(8L, ((Number) grown).longValue(), "扩展后应继续累加");
    }

    @Test
    @DisplayName("新引入的变量会被报告为冷启动 —— 运维需要知道它们要等一个窗口期")
    void newVariablesAreReportedAsCold() throws Exception {
        List<Map<String, Object>> all = loadDir("strategies");
        List<Map<String, Object>> one = only(all, "IP多次登录失败");
        RiskDetectionFunction f = fresh(one);

        // 换成引用面更广的一组策略
        List<Map<String, Object>> more = all.stream().limit(20).toList();
        Set<String> cold = f.reload(more, loadDir("variables"), 44L);

        assertFalse(cold.isEmpty(), "换成引用更多变量的策略后,应当报告有变量冷启动");
    }

    @Test
    @DisplayName("重复下发同一份元数据不会产生冷启动变量")
    void idempotentReloadHasNoColdStart() throws Exception {
        List<Map<String, Object>> one = only(loadDir("strategies"), "IP多次登录失败");
        RiskDetectionFunction f = fresh(one);
        Set<String> cold = f.reload(one, loadDir("variables"), 45L);
        assertTrue(cold.isEmpty(), "同一份元数据重新下发不该有变量冷启动,实际: " + cold);
    }

    @Test
    @DisplayName("策略被移除后不再产出告警,但它引用过的变量状态仍保留")
    void removingStrategyKeepsVariableState() throws Exception {
        List<Map<String, Object>> one = only(loadDir("strategies"), "IP多次登录失败");
        RiskDetectionFunction f = fresh(one);
        Sink sink = new Sink();
        long t0 = 1_700_000_000_000L;
        String ip = "198.51.100.7";
        for (int i = 0; i < 6; i++) {
            feed(f, loginFailure(ip, t0 + i * 1000L), sink);
        }
        assertTrue(sink.got.size() > 0, "基线:阈值 >5 时 6 条失败应当命中");

        // 下线这条策略
        f.reload(List.of(), loadDir("variables"), 46L);
        int afterRemoval = sink.got.size();
        feed(f, loginFailure(ip, t0 + 6000L), sink);
        assertEquals(afterRemoval, sink.got.size(), "策略已移除,不该再产出告警");

        // 再上线回来 —— 状态若被保留,下一条应当立刻命中
        f.reload(one, loadDir("variables"), 47L);
        feed(f, loginFailure(ip, t0 + 7000L), sink);
        assertTrue(sink.got.size() > afterRemoval,
                "策略重新上线后没有立刻命中 —— 说明下线期间变量状态被丢弃了。"
                        + "策略在 test / online 之间来回切是常见操作,每切一次清空状态"
                        + "会让热更新失去意义");
    }
}
