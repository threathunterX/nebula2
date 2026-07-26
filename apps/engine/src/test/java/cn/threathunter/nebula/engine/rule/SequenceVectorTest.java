package cn.threathunter.nebula.engine.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 多步序列的跨引擎对照 —— 生产引擎这一侧。
 *
 * <p>参考引擎(JS)读<b>同一个文件</b>跑同一批场景
 * ({@code reference-engine/test/sequence-vectors.test.js})。两边不一致时说明有
 * 一侧的语义偏了。
 *
 * <p>这不是形式主义:#44 修的正是这类跨引擎不一致 —— 参考引擎实现了 delay,
 * Java 引擎没有,而两者本该语义等价。不一致<b>不会报错</b>,表现是同一批事件在
 * 两个引擎里产出不同的告警,只有拿具体向量比对才知道。
 *
 * <p><b>为什么不用 Flink CEP</b>见 {@code StrategyEngine.partials} 的说明:
 * flink-cep 1.20.5 的模式在构图时编译进作业图,与 v0.3.0 落地的策略热更新冲突。
 */
class SequenceVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path VECTORS =
            Path.of("../../tests/golden/vectors/sequences.json");

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> sequenceVectors() throws IOException {
        Map<String, Object> doc = MAPPER.readValue(Files.readString(VECTORS),
                new TypeReference<Map<String, Object>>() {
                });
        long base = ((Number) doc.get("base_timestamp")).longValue();
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");

        return cases.stream().map(c -> DynamicTest.dynamicTest(
                "序列向量 " + c.get("name") + ":" + c.get("note"),
                () -> runCase(base, c)));
    }

    @SuppressWarnings("unchecked")
    private void runCase(long base, Map<String, Object> c) {
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("app", "nebula");
        strategy.put("name", "向量-" + c.get("name"));
        strategy.put("visible_name", String.valueOf(c.get("name")));
        strategy.put("status", "online");
        strategy.put("category", "ACCOUNT");
        strategy.put("score", 0);
        strategy.put("dedup_window", 0);
        strategy.put("action", Map.of("decision", "review", "check_type", "IP",
                "check_value", "c_ip", "ttl", 3600));
        strategy.put("sequence", c.get("sequence"));

        StrategyEngine engine = new StrategyEngine(List.of(strategy), null, null);
        for (Map<String, Object> raw : (List<Map<String, Object>>) c.get("events")) {
            Map<String, Object> event = new LinkedHashMap<>(raw);
            String name = String.valueOf(event.remove("name"));
            long off = ((Number) event.remove("offset_seconds")).longValue();
            long ts = base + off * 1000L;
            event.put("name", name);
            event.put("timestamp", ts);
            engine.process(event, name, ts);
        }

        int want = ((Number) c.get("expect_hits")).intValue();
        List<StrategyEngine.Notice> got = new ArrayList<>(engine.notices());
        assertEquals(want, got.size(),
                "命中 " + got.size() + " 次,向量要求 " + want + " 次");
    }
}
