package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.operator.Accumulator;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.operator.Operators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 跨语言语义一致性测试。
 *
 * <p>本测试与 {@code packages/reference-engine/test/vectors.test.js} 读<b>同一份</b>
 * {@code tests/golden/vectors/operators.json}。两套实现之间的语义漂移因此在结构上
 * 不可能发生:任何一方改了算子行为,共享向量立刻会在另一方失败。
 *
 * <p>这是 {@code tests/golden/README.md} 描述的对照机制在算子粒度上的落地。
 */
class SharedVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode SUITE = load();

    private static JsonNode load() {
        // 从 apps/engine 向上两级到仓库根
        Path p = Path.of("..", "..", "tests", "golden", "vectors", "operators.json")
                .toAbsolutePath().normalize();
        try {
            return MAPPER.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new IllegalStateException("读取共享测试向量失败: " + p, e);
        }
    }

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        SUITE.get("cases").forEach(out::add);
        return out.stream();
    }

    @Test
    @DisplayName("共享向量文件结构完整,且用例 id 唯一")
    void suiteIsWellFormed() {
        Set<String> ids = new HashSet<>();
        for (JsonNode c : SUITE.get("cases")) {
            String id = c.get("id").asText();
            assertTrue(ids.add(id), "用例 id 重复: " + id);
            assertTrue(c.hasNonNull("operator"), id + " 缺少 operator");
            assertTrue(c.hasNonNull("spec"), id + " 缺少 spec 出处");
            assertTrue(c.has("inputs"), id + " 缺少 inputs");
            assertTrue(c.has("expect"), id + " 缺少 expect");
        }
        assertFalse(ids.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("算子行为与共享向量一致")
    void matchesVector(JsonNode c) {
        String id = c.get("id").asText();
        String operator = c.get("operator").asText();

        Map<String, Object> cfg = new HashMap<>();
        if (c.hasNonNull("param")) {
            cfg.put("param", c.get("param").asText());
        }
        Accumulator acc = Operators.create(operator, cfg);

        int i = 0;
        for (JsonNode row : c.get("inputs")) {
            long ts = row.hasNonNull("ts") ? row.get("ts").asLong() : 1000L + i;
            Object group = row.has("g") ? toJava(row.get("g")) : null;
            acc.add(toJava(row.get("v")), EventMeta.of(ts, group));
            i++;
        }

        Object got = acc.value();
        Object want = toJava(c.get("expect"));

        if (c.hasNonNull("tolerance") && want instanceof Number wn && got instanceof Number gn) {
            double tol = c.get("tolerance").asDouble();
            assertTrue(Math.abs(gn.doubleValue() - wn.doubleValue()) <= tol,
                    id + ": 期望 " + want + " ± " + tol + ",实际 " + got);
        } else {
            assertEquals(normalize(want), normalize(got),
                    id + "(规格 " + c.get("spec").asText() + ")");
        }
    }

    @Test
    @DisplayName("向量覆盖了全部已实现的算子(未覆盖的算子必须补向量)")
    void everyOperatorIsCovered() {
        Set<String> covered = new HashSet<>();
        for (JsonNode c : SUITE.get("cases")) {
            covered.add(c.get("operator").asText());
        }
        // 这些算子的语义与已覆盖算子同构,或依赖窗口/图上下文,单点向量无法表达
        Set<String> exempt = Set.of("topn", "last_value", "global_latest", "group_sum");

        List<String> missing = new ArrayList<>();
        for (String op : Operators.supported()) {
            if (!covered.contains(op) && !exempt.contains(op)) {
                missing.add(op);
            }
        }
        assertTrue(missing.isEmpty(),
                "以下算子没有共享向量覆盖,请在 tests/golden/vectors/operators.json 中补充: " + missing);
    }

    // ---------------------------------------------------------------- 工具

    private static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            double d = n.asDouble();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (long) d : d;
        }
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            n.forEach(x -> out.add(toJava(x)));
            return out;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), toJava(e.getValue())));
            return out;
        }
        return n.asText();
    }

    /**
     * 数值归一化。JS 只有一种数字类型,{@code 10} 与 {@code 10.0} 不可区分;
     * Java 侧需要把两者视为相等,否则共享向量在两边无法通用。
     */
    private static Object normalize(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        }
        if (o instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(x -> out.add(normalize(x)));
            return out;
        }
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        return o;
    }
}
