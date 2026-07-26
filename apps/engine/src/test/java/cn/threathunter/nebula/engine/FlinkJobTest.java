package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.flink.RiskDetectionFunction;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 在 Flink MiniCluster 中<b>真实运行</b>作业。
 *
 * <p>不是 mock、不是接口测试:它启动一个进程内的 Flink 集群,提交作业,让事件真的
 * 流过算子,再把产出的告警与参考引擎固化的快照逐条比对。
 *
 * <p>这验证了三件事:引擎能被正确接入 Flink 的生命周期(open / processElement)、
 * 序列化没有问题、以及接入 Flink 之后语义没有发生偏移。
 */
class FlinkJobTest {

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

    @Test
    @DisplayName("Flink 作业在 MiniCluster 中跑出的告警与参考引擎一致")
    void jobProducesSameNotices() throws Exception {
        JsonNode spec = readJson("tests/golden/vectors/notice-scenario.json");
        JsonNode expected = readJson("tests/golden/vectors/notice-expected.json");

        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode e : spec.get("events")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Vectors.toJava(e);
            events.add(m);
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 并行度必须为 1:变量按不同维度分组,一次 keyBy 无法同时满足。
        // 按维度拆链路是下一步的工作,见 RiskDetectionFunction 的说明。
        env.setParallelism(1);

        DataStream<Map<String, Object>> source = env.fromData(
                TypeInformation.of(new org.apache.flink.api.common.typeinfo.TypeHint<>() {
                }),
                events.toArray(new Map[0]));

        DataStream<StrategyEngine.Notice> notices = source.process(
                new RiskDetectionFunction(loadDir("strategies"), loadDir("variables"),
                        loadDir("events")));

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

        assertTrue(got.size() > 0, "Flink 作业应产出告警");
        assertEquals(want.size(), got.size(),
                "告警条数不一致。参考引擎 " + want.size() + " 条,Flink 作业 " + got.size() + " 条");
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i), got.get(i), "第 " + (i + 1) + " 条告警不一致");
        }
    }
}
