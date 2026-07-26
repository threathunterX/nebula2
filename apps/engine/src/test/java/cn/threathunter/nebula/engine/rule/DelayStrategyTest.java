package cn.threathunter.nebula.engine.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.graph.EventModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 延迟策略 —— 「主体做了 A,但随后 N 秒内没有做 B」。
 *
 * <p>这类模式表达的是<b>缺席</b>,条件树表达不了:条件树只能对当下这条事件求值,
 * 而「没有发生」要等一段时间之后才能确认。
 *
 * <p><b>最关键的一条是否定用例:B 出现了就不该告警。</b>只测「A 之后没有 B → 告警」
 * 的话,一个永远返回 true 的实现也能通过 —— 而那正是这类逻辑最容易写错的方向。
 */
class DelayStrategyTest {

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

    /** 内置模板的 A / B 是占位符,这里替换成真实路径 —— 与使用方要做的事情完全一样。 */
    private static Map<String, Object> template(String pageA, String pageB) throws IOException {
        for (Map<String, Object> s : loadDir("strategies")) {
            if ("IP请求A一段时间内没有请求B".equals(s.get("name"))) {
                String json = MAPPER.writeValueAsString(s)
                        .replace("\"value\":\"A\"", "\"value\":\"" + pageA + "\"")
                        .replace("\"value\": \"A\"", "\"value\": \"" + pageA + "\"")
                        .replace("\"value\":\"B\"", "\"value\":\"" + pageB + "\"")
                        .replace("\"value\": \"B\"", "\"value\": \"" + pageB + "\"");
                return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
                });
            }
        }
        throw new IllegalStateException("内置模板 IP请求A一段时间内没有请求B 不存在");
    }

    private static Map<String, Object> visit(String ip, String page, long ts) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", "HTTP_DYNAMIC");
        e.put("timestamp", ts);
        e.put("c_ip", ip);
        e.put("page", page);
        e.put("uri_stem", page);
        e.put("method", "GET");
        return e;
    }

    private static StrategyEngine engine(Map<String, Object> strategy) throws IOException {
        return new StrategyEngine(List.of(strategy), null, new EventModel(loadDir("events")));
    }

    private static final long T0 = 1_700_000_000_000L;
    private static final String IP = "198.51.100.20";

    @Test
    @DisplayName("做了 A、窗口内没做 B —— 到期后告警")
    void firesWhenBIsAbsent() throws Exception {
        StrategyEngine e = engine(template("/cart/add", "/order/submit"));

        e.process(visit(IP, "/cart/add", T0), "HTTP_DYNAMIC", T0);
        assertEquals(0, e.notices().size(), "延迟未到期就不该产出");

        // 中间只有无关请求,始终没有 B
        for (int i = 1; i <= 5; i++) {
            long ts = T0 + i * 60_000L;
            e.process(visit(IP, "/browse", ts), "HTTP_DYNAMIC", ts);
        }
        // 越过 300 秒的延迟窗口
        long after = T0 + 301_000L;
        e.process(visit(IP, "/browse", after), "HTTP_DYNAMIC", after);

        assertTrue(e.notices().size() > 0, "A 之后窗口内没有 B,到期应当告警");
    }

    @Test
    @DisplayName("做了 A 又做了 B —— 不告警。这条比上一条更重要")
    void doesNotFireWhenBHappens() throws Exception {
        // 只测上面那条的话,一个永远返回 true 的实现也能通过
        StrategyEngine e = engine(template("/cart/add", "/order/submit"));

        e.process(visit(IP, "/cart/add", T0), "HTTP_DYNAMIC", T0);
        long b = T0 + 60_000L;
        e.process(visit(IP, "/order/submit", b), "HTTP_DYNAMIC", b);

        long after = T0 + 301_000L;
        e.process(visit(IP, "/browse", after), "HTTP_DYNAMIC", after);

        assertEquals(0, e.notices().size(),
                "主体在窗口内做了 B,不该告警 —— 否则这条策略会把正常完成流程的用户全部报出来");
    }

    @Test
    @DisplayName("延迟未到期不产出 —— 判定要等窗口走完才有意义")
    void doesNotFireBeforeDue() throws Exception {
        StrategyEngine e = engine(template("/cart/add", "/order/submit"));
        e.process(visit(IP, "/cart/add", T0), "HTTP_DYNAMIC", T0);
        long mid = T0 + 100_000L;   // 还没到 300 秒
        e.process(visit(IP, "/browse", mid), "HTTP_DYNAMIC", mid);
        assertEquals(0, e.notices().size(), "延迟窗口内不该提前告警");
    }

    @Test
    @DisplayName("由事件时间驱动而非挂钟时间 —— 回放历史数据的结果必须与实时一致")
    void drivenByEventTime() throws Exception {
        // 事件时间戳全部是 2023 年的历史值。若实现用了 System.currentTimeMillis(),
        // 所有延迟会在第一条事件到达时就立刻到期,结果与实时处理不同。
        long old = 1_672_531_200_000L;   // 2023-01-01
        StrategyEngine e = engine(template("/cart/add", "/order/submit"));
        e.process(visit(IP, "/cart/add", old), "HTTP_DYNAMIC", old);
        long mid = old + 100_000L;
        e.process(visit(IP, "/browse", mid), "HTTP_DYNAMIC", mid);
        assertEquals(0, e.notices().size(),
                "用历史时间戳时延迟仍未到期 —— 若这里产出了告警,说明用的是挂钟时间");
    }
}
