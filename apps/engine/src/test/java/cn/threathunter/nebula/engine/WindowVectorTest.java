package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 窗口模型的跨语言一致性。
 *
 * <p>其中 {@code watermark-is-stream-level-not-per-key} 一例尤其重要:它固定了
 * 「水位线属于流、不属于 key」这条语义。若按 key 维护,每个新出现的 key 的首个
 * 事件都不可能被判为迟到,攻击者用不断变化的 IP 或设备号即可绕过迟到检测。
 */
class WindowVectorTest {

    private static final JsonNode SUITE = Vectors.load("windows.json");

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        SUITE.get("cases").forEach(out::add);
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("窗口行为与共享向量一致")
    void matchesVector(JsonNode c) {
        String id = c.get("id").asText();
        JsonNode p = c.get("period");
        Period period = Period.parse(p.get("type").asText(),
                p.hasNonNull("value") ? p.get("value").asText() : null);
        long lateness = c.hasNonNull("allowedLatenessMs")
                ? c.get("allowedLatenessMs").asLong()
                : WindowedAggregate.DEFAULT_ALLOWED_LATENESS_MS;

        WindowedAggregate agg = new WindowedAggregate(
                c.get("operator").asText(), period, Map.of(), lateness);

        List<String> outcomes = new ArrayList<>();
        for (JsonNode e : c.get("events")) {
            long ts = e.get("ts").asLong();
            long wm = e.hasNonNull("wm") ? e.get("wm").asLong() : Long.MIN_VALUE;
            outcomes.add(agg.add(Vectors.toJava(e.get("v")),
                    new EventMeta(ts, wm, null)).name());
        }

        Object got = agg.value(c.get("probeTs").asLong());
        assertEquals(Vectors.toJava(c.get("expect")), got,
                id + " 的窗口聚合值(规格 " + c.get("spec").asText() + ")");

        if (c.hasNonNull("expectOutcomes")) {
            List<String> want = new ArrayList<>();
            c.get("expectOutcomes").forEach(x -> want.add(x.asText()));
            assertEquals(want, outcomes, id + " 的迟到处置结果");
        }
    }
}
