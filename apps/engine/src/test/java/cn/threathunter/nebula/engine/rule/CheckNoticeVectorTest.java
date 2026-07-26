package cn.threathunter.nebula.engine.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * checkNotice(策略级联)的跨引擎对照 —— 生产引擎这一侧。
 *
 * <p>参考引擎读<b>同一个文件</b>跑同一批场景
 * ({@code reference-engine/test/check-notice.test.js})。
 *
 * <p>每个场景两条策略:setup 命中后产出告警,probe 用 checkNotice 查它。断言 probe 的
 * 命中次数 —— 测的是「查得到 / 查不到」,而不是内部实现。
 */
class CheckNoticeVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path VECTORS =
            Path.of("../../tests/golden/vectors/check-notice.json");

    private static Map<String, Object> base(String name, String checkType, String checkValue) {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("app", "nebula");
        st.put("name", name);
        st.put("visible_name", name);
        st.put("status", "online");
        st.put("category", "ACCOUNT");
        st.put("score", 0);
        st.put("dedup_window", 0);
        st.put("action", Map.of("decision", "review", "check_type", checkType,
                "check_value", checkValue, "ttl", 3600));
        return st;
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> checkNoticeVectors() throws IOException {
        Map<String, Object> doc = MAPPER.readValue(Files.readString(VECTORS),
                new TypeReference<Map<String, Object>>() {
                });
        long tsBase = ((Number) doc.get("base_timestamp")).longValue();
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");

        return cases.stream().map(c -> DynamicTest.dynamicTest(
                "checkNotice 向量 " + c.get("name") + ":" + c.get("note"),
                () -> runCase(tsBase, c)));
    }

    @SuppressWarnings("unchecked")
    private void runCase(long tsBase, Map<String, Object> c) {
        Map<String, Object> s = (Map<String, Object>) c.get("setup_strategy");
        Map<String, Object> p = (Map<String, Object>) c.get("probe_strategy");

        Map<String, Object> setup = base(String.valueOf(s.get("name")),
                String.valueOf(s.get("check_type")), String.valueOf(s.get("check_value")));
        setup.put("condition", s.get("condition"));

        Map<String, Object> probe = base(String.valueOf(p.get("name")),
                String.valueOf(p.get("check_type")), String.valueOf(p.get("check_value")));
        probe.put("condition", Map.of("cel", p.get("cel")));

        StrategyEngine engine = new StrategyEngine(List.of(setup, probe), null, null);
        for (Map<String, Object> raw : (List<Map<String, Object>>) c.get("events")) {
            Map<String, Object> event = new LinkedHashMap<>(raw);
            String name = String.valueOf(event.remove("name"));
            long off = ((Number) event.remove("offset_seconds")).longValue();
            long ts = tsBase + off * 1000L;
            event.put("name", name);
            event.put("timestamp", ts);
            engine.process(event, name, ts);
        }

        long probeHits = engine.notices().stream()
                .filter(n -> n.strategyName().equals(p.get("name")))
                .count();
        int want = ((Number) c.get("expect_hits")).intValue();
        assertEquals(want, probeHits, "probe 命中 " + probeHits + " 次,向量要求 " + want + " 次");
    }
}
