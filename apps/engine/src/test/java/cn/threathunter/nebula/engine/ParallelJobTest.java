package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.flink.NebulaParallelJob;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 并行拓扑的正确性验证。
 *
 * <p>在<b>不同并行度</b>下跑同一批事件,结果必须与参考引擎的固化快照完全一致。
 * 这是并行化最关键的一条保证:并行只应改变吞吐,不应改变结果。
 *
 * <p>若按维度拆链路的方案有缺陷(比如某个变量被分到了错误的分区,或汇聚时漏等了
 * 一个维度),并行度大于 1 时结果就会与快照不符。
 */
class ParallelJobTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static List<Map<String, Object>> loadDir(String sub) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(ROOT.resolve("seeds").resolve(sub))) {
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

    private static JsonNode readJson(String rel) {
        try {
            return MAPPER.readTree(Files.readString(ROOT.resolve(rel)));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + rel + " 失败", e);
        }
    }

    @ParameterizedTest(name = "并行度 {0}")
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("并行拓扑在不同并行度下产出的告警都与参考引擎一致")
    void parallelismDoesNotChangeResults(int parallelism) throws Exception {
        JsonNode spec = readJson("tests/golden/vectors/notice-scenario.json");
        JsonNode expected = readJson("tests/golden/vectors/notice-expected.json");

        List<Map<String, Object>> events = new ArrayList<>();
        int i = 0;
        for (JsonNode e : spec.get("events")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new HashMap<>((Map<String, Object>) Vectors.toJava(e));
            // 注入事件唯一标识,供汇聚阶段分组
            m.put(NebulaParallelJob.EVENT_ID_FIELD, "e" + i++);
            events.add(m);
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);

        DataStream<Map<String, Object>> source = env.fromData(
                TypeInformation.of(new TypeHint<Map<String, Object>>() {
                }),
                events.toArray(new Map[0]));

        DataStream<StrategyEngine.Notice> notices = NebulaParallelJob.build(
                source, loadDir("strategies"), loadDir("variables"), loadDir("events"));

        List<Map<String, Object>> got = new ArrayList<>();
        try (var it = notices.executeAndCollect()) {
            while (it.hasNext()) {
                StrategyEngine.Notice n = it.next();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strategy", n.strategyName());
                row.put("key", n.key());
                row.put("check_type", n.checkType());
                row.put("scene", n.sceneName());
                row.put("decision", n.decision());
                row.put("test", n.test());
                row.put("ttl_ms", n.expire() - n.timestamp());
                got.add(row);
            }
        }
        got.sort(Comparator.comparing(r -> String.valueOf(r.get("strategy")) + r.get("key")));

        List<Map<String, Object>> want = new ArrayList<>();
        for (JsonNode n : expected.get("notices")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) Vectors.toJava(n);
            want.add(row);
        }

        assertTrue(got.size() > 0, "并行度 " + parallelism + " 下应产出告警");
        assertEquals(want.size(), got.size(),
                "并行度 " + parallelism + " 下告警条数不一致。参考 " + want.size()
                        + " 条,实际 " + got.size() + " 条");
        for (int k = 0; k < want.size(); k++) {
            assertEquals(want.get(k), got.get(k),
                    "并行度 " + parallelism + " 下第 " + (k + 1) + " 条告警不一致");
        }
    }
}
