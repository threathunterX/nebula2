package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>端到端告警对照</b> —— 跨语言一致性的最高层级。
 *
 * <p>两个引擎加载全部 170 条策略与 253 个变量,跑同一批事件,产出的告警必须逐条
 * 一致。这覆盖了变量图传播、条件求值、内联计数器、事件继承匹配、告警去重的全链路
 * —— 任何一环出现语义偏差,这个测试立刻失败。
 */
class NoticeSnapshotTest {

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
    @DisplayName("全量策略产出的告警与参考引擎逐条一致")
    void noticesMatchReference() {
        JsonNode spec = readJson("tests/golden/vectors/notice-scenario.json");
        JsonNode expected = readJson("tests/golden/vectors/notice-expected.json");

        List<Map<String, Object>> strategies = loadDir("strategies");
        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : loadDir("variables")) {
            vars.add(new VariableDef(m));
        }
        EventModel em = new EventModel(loadDir("events"));

        // 只构建策略实际引用到的变量闭包,与参考引擎一致
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> st : strategies) {
            collectVariableRefs(st, referenced);
        }
        VariableGraph graph = referenced.isEmpty()
                ? null : new VariableGraph(vars, referenced, em);

        StrategyEngine engine = new StrategyEngine(strategies, graph, em);
        for (JsonNode e : spec.get("events")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) Vectors.toJava(e);
            long ts = ((Number) event.get("timestamp")).longValue();
            engine.process(event, String.valueOf(event.get("name")), ts);
        }

        List<Map<String, Object>> got = new ArrayList<>();
        for (StrategyEngine.Notice n : engine.notices()) {
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
        got.sort(Comparator.comparing(r -> String.valueOf(r.get("strategy"))
                + r.get("key")));

        List<Map<String, Object>> want = new ArrayList<>();
        for (JsonNode n : expected.get("notices")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) Vectors.toJava(n);
            want.add(row);
        }

        assertEquals(want.size(), got.size(),
                "告警条数不一致。参考引擎 " + want.size() + " 条,本引擎 " + got.size() + " 条");
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i), got.get(i), "第 " + (i + 1) + " 条告警不一致");
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectVariableRefs(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> m) {
            if ("variable".equals(m.get("kind")) && m.get("variable") != null) {
                out.add(String.valueOf(m.get("variable")));
            }
            m.values().forEach(v -> collectVariableRefs(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectVariableRefs(v, out));
        }
    }
}
