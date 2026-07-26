package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 变量图的<b>跨语言端到端对照</b>。
 *
 * <p>与 {@code packages/reference-engine} 读同一批事件({@code graph-scenario.json})、
 * 跑同一批真实变量资产,逐 key 比对最终值({@code graph-expected.json})。
 *
 * <p>这比逐算子对照更进一步:它覆盖图的传播与剪枝、按 key 分槽、事件继承链匹配、
 * 以及 dual / aggregate 不同的时间语义。任何一侧的图实现出现偏差,这个测试立刻失败。
 */
class GraphSnapshotTest {

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
        Path p = ROOT.resolve(rel);
        try {
            return MAPPER.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + p + " 失败", e);
        }
    }

    @Test
    @DisplayName("变量图计算结果与参考引擎固化的快照完全一致")
    void matchesReferenceSnapshot() {
        JsonNode spec = readJson("tests/golden/vectors/graph-scenario.json");
        JsonNode expected = readJson("tests/golden/vectors/graph-expected.json").get("values");

        Set<String> wanted = new LinkedHashSet<>();
        spec.get("variables").forEach(v -> wanted.add(v.asText()));

        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : loadDir("variables")) {
            vars.add(new VariableDef(m));
        }
        VariableGraph graph = new VariableGraph(vars, wanted, new EventModel(loadDir("events")));

        for (JsonNode e : spec.get("events")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) Vectors.toJava(e);
            long ts = ((Number) event.get("timestamp")).longValue();
            graph.process(event, String.valueOf(event.get("name")), ts);
        }

        long probeTs = spec.get("probeTs").asLong();
        Map<String, Map<String, Object>> got = new LinkedHashMap<>();
        for (String v : wanted) {
            Map<String, Object> byProbe = new LinkedHashMap<>();
            for (JsonNode probe : spec.get("probes")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pe = (Map<String, Object>) Vectors.toJava(probe);
                pe.put("timestamp", probeTs);
                byProbe.put(String.valueOf(pe.get("__label")), graph.valueOf(v, pe, probeTs));
            }
            got.put(v, byProbe);
        }

        for (String v : wanted) {
            JsonNode want = expected.get(v);
            for (Map.Entry<String, Object> e : got.get(v).entrySet()) {
                Object w = Vectors.toJava(want.get(e.getKey()));
                assertEquals(w, e.getValue(),
                        "变量 " + v + " 在 " + e.getKey() + " 上的值与参考引擎不一致");
            }
        }
    }
}
