package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.threathunter.nebula.engine.condition.Conditions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 条件算子的跨语言一致性 —— 与 JS 参考实现读同一份向量。 */
class ConditionVectorTest {

    private static final JsonNode SUITE = Vectors.load("conditions.json");

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        SUITE.get("cases").forEach(out::add);
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("条件算子行为与共享向量一致")
    void matchesVector(JsonNode c) {
        String id = c.get("id").asText();
        boolean got = Conditions.eval(
                c.get("op").asText(),
                Vectors.toJava(c.get("left")),
                Vectors.toJava(c.get("right")));
        assertEquals(c.get("expect").asBoolean(), got,
                id + "(规格 " + c.get("spec").asText() + ")");
    }

    @Test
    @DisplayName("向量覆盖了全部已实现的条件算子")
    void everyOperatorCovered() {
        Set<String> covered = new HashSet<>();
        SUITE.get("cases").forEach(c -> covered.add(c.get("op").asText()));
        List<String> missing = new ArrayList<>();
        for (String op : Conditions.supported()) {
            if (!covered.contains(op)) {
                missing.add(op);
            }
        }
        assertTrue(missing.isEmpty(),
                "以下条件算子没有向量覆盖,请补充 tests/golden/vectors/conditions.json: " + missing);
    }
}
