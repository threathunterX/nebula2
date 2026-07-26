package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 变量计算图 —— 直接跑仓库里真实的 253 个变量与 17 个事件模型。
 *
 * <p>这不是用玩具数据测试:它加载 {@code seeds/} 下与参考引擎完全相同的资产,
 * 因此两侧的行为可以逐个变量对照。
 */
class VariableGraphTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SEEDS =
            Path.of("..", "..", "seeds").toAbsolutePath().normalize();

    private static List<Map<String, Object>> loadDir(String sub) {
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
        } catch (IOException e) {
            throw new IllegalStateException("加载 seeds/" + sub + " 失败", e);
        }
        return out;
    }

    private static List<VariableDef> variables() {
        List<VariableDef> out = new ArrayList<>();
        for (Map<String, Object> m : loadDir("variables")) {
            out.add(new VariableDef(m));
        }
        return out;
    }

    private static EventModel events() {
        return new EventModel(loadDir("events"));
    }

    private static Map<String, Object> event(String ip, long ts) {
        Map<String, Object> e = new HashMap<>();
        e.put("c_ip", ip);
        e.put("page", "/product/1");
        e.put("uri_stem", "/product/1");
        e.put("method", "GET");
        e.put("timestamp", ts);
        return e;
    }

    // ---------------------------------------------------------------- 事件继承

    @Test
    @DisplayName("事件继承:ACCOUNT_LOGIN 的祖先链包含 HTTP_DYNAMIC")
    void inheritanceChain() {
        EventModel em = events();
        assertEquals(List.of("ACCOUNT_LOGIN", "HTTP_DYNAMIC"), em.chainOf("ACCOUNT_LOGIN"));
        assertTrue(em.isA("ACCOUNT_LOGIN", "HTTP_DYNAMIC"));
        assertFalse(em.isA("HTTP_DYNAMIC", "ACCOUNT_LOGIN"), "继承是单向的");
    }

    @Test
    @DisplayName("根事件的 source 指向自身,解析继承链时不构成环")
    void rootSelfReferenceIsNotACycle() {
        assertEquals(List.of("HTTP_DYNAMIC"), events().chainOf("HTTP_DYNAMIC"));
    }

    // ---------------------------------------------------------------- 图构建

    @Test
    @DisplayName("全部 253 个变量可以构成无环图")
    void wholeGraphIsAcyclic() {
        VariableGraph g = new VariableGraph(variables(), null, events());
        assertEquals(253, g.order().size());
    }

    @Test
    @DisplayName("依赖闭包只构建被引用的变量及其上游")
    void closureIsMinimal() {
        VariableGraph g = new VariableGraph(variables(),
                Set.of("ip__visit_count__5m__rt"), events());
        assertTrue(g.order().size() > 1 && g.order().size() < 10,
                "闭包应远小于全量 253,实际 " + g.order().size());
        assertTrue(g.order().contains("ip__visit_dynamic_count__5m__rt"), "应包含上游变量");
    }

    @Test
    @DisplayName("拓扑序:上游一定排在下游之前")
    void topologicalOrderIsCorrect() {
        List<VariableDef> vars = variables();
        VariableGraph g = new VariableGraph(vars, Set.of("ip__visit_count__5m__rt"), events());
        List<String> order = g.order();
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            pos.put(order.get(i), i);
        }
        for (VariableDef v : vars) {
            if (!pos.containsKey(v.name()) || "event".equals(v.type())) {
                continue;
            }
            for (String s : v.sources()) {
                if (pos.containsKey(s)) {
                    assertTrue(pos.get(s) < pos.get(v.name()),
                            s + " 应排在 " + v.name() + " 之前");
                }
            }
        }
    }

    // ---------------------------------------------------------------- 计算

    @Test
    @DisplayName("aggregate 节点:滑动窗口内按 key 累计,不同 key 互不影响")
    void aggregateIsKeyed() {
        VariableGraph g = new VariableGraph(variables(),
                Set.of("ip__visit_dynamic_count__5m__rt"), events());
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 10; i++) {
            Map<String, Object> e = event("198.51.100.5", base + i * 1000L);
            g.process(e, "HTTP_DYNAMIC", base + i * 1000L);
        }
        Map<String, Object> probe = event("198.51.100.5", base + 10_000L);
        assertEquals(10L, g.valueOf("ip__visit_dynamic_count__5m__rt", probe, base + 10_000L));

        Map<String, Object> other = event("198.51.100.6", base + 10_000L);
        assertEquals(0L, g.valueOf("ip__visit_dynamic_count__5m__rt", other, base + 10_000L),
                "不同 key 的窗口互不影响");
    }

    @Test
    @DisplayName("dual 节点:两个上游求和(动态 + 静态请求数)")
    void dualCombinesUpstreams() {
        VariableGraph g = new VariableGraph(variables(),
                Set.of("ip__visit_count__5m__rt"), events());
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 5; i++) {
            Map<String, Object> e = event("198.51.100.7", base + i * 1000L);
            g.process(e, "HTTP_DYNAMIC", base + i * 1000L);
        }
        Map<String, Object> probe = event("198.51.100.7", base + 5000L);
        // 动态 5 次 + 静态 0 次
        assertEquals(5L, g.valueOf("ip__visit_count__5m__rt", probe, base + 5000L));
    }

    @Test
    @DisplayName("事件继承使定义在父事件上的变量能被子事件更新")
    void childEventUpdatesParentVariable() {
        VariableGraph g = new VariableGraph(variables(),
                Set.of("ip__visit_dynamic_count__5m__rt"), events());
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 3; i++) {
            Map<String, Object> e = event("198.51.100.8", base + i * 1000L);
            e.put("uid", "u" + i);
            e.put("result", "F");
            // 用子事件 ACCOUNT_LOGIN 触发,变量定义在父事件 HTTP_DYNAMIC 上
            g.process(e, "ACCOUNT_LOGIN", base + i * 1000L);
        }
        Map<String, Object> probe = event("198.51.100.8", base + 3000L);
        assertEquals(3L, g.valueOf("ip__visit_dynamic_count__5m__rt", probe, base + 3000L),
                "登录事件也是动态请求事件,应计入");
    }
}
