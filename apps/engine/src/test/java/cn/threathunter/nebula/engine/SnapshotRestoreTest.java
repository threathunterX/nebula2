package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.threathunter.nebula.engine.operator.Accumulator;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.operator.Operators;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checkpoint 正确性:<b>快照—恢复之后,后续计算结果必须与从未中断完全一致</b>。
 *
 * <p>这是 Checkpoint 的定义。只验证「能导出、能导入」是不够的 —— 状态漏掉一个字段
 * 时,导出导入都不会报错,但恢复后的计算会悄悄偏离,这类问题在生产上极难定位。
 *
 * <p>因此每个用例都做三件事:算一半 → 快照 → 用新实例恢复 → 喂完剩下的输入 →
 * 与全程不中断的结果比对。同时对快照做真实的 Java 序列化往返,确认状态里没有
 * 混入不可序列化的对象。
 */
class SnapshotRestoreTest {

    private static final JsonNode SUITE = Vectors.load("operators.json");

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        SUITE.get("cases").forEach(c -> {
            // 至少要有 2 条输入才谈得上「算一半」
            if (c.get("inputs").size() >= 2) {
                out.add(c);
            }
        });
        return out.stream();
    }

    /** 真实序列化往返 —— 混入不可序列化对象时这里会直接抛异常。 */
    private static Serializable roundTrip(Serializable s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(s);
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()))) {
            return (Serializable) ois.readObject();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("算子快照恢复后结果与不中断完全一致")
    void accumulatorSurvivesRestore(JsonNode c) throws Exception {
        String operator = c.get("operator").asText();
        Map<String, Object> cfg = new HashMap<>();
        if (c.hasNonNull("param")) {
            cfg.put("param", c.get("param").asText());
        }
        List<JsonNode> inputs = new ArrayList<>();
        c.get("inputs").forEach(inputs::add);
        int half = inputs.size() / 2;

        // 全程不中断
        Accumulator straight = Operators.create(operator, cfg);
        feed(straight, inputs, 0, inputs.size());

        // 算一半 → 快照 → 序列化往返 → 恢复 → 喂完剩下的
        Accumulator before = Operators.create(operator, cfg);
        feed(before, inputs, 0, half);
        Serializable snap = roundTrip(before.snapshot());

        Accumulator after = Operators.create(operator, cfg);
        after.restore(snap);
        feed(after, inputs, half, inputs.size());

        assertEquals(straight.value(), after.value(),
                operator + " 恢复后的结果与不中断不一致(用例 " + c.get("id").asText() + ")");
    }

    private static void feed(Accumulator acc, List<JsonNode> rows, int from, int to) {
        for (int i = from; i < to; i++) {
            JsonNode row = rows.get(i);
            long ts = row.hasNonNull("ts") ? row.get("ts").asLong() : 1000L + i;
            Object group = row.has("g") ? Vectors.toJava(row.get("g")) : null;
            acc.add(Vectors.toJava(row.get("v")), EventMeta.of(ts, group));
        }
    }

    @Test
    @DisplayName("滑动窗口快照恢复后,过期裁剪仍然正确")
    void slidingWindowSurvivesRestore() throws Exception {
        Period p = Period.parse("last_n_seconds", "300");
        long base = 1_700_000_000_000L;

        WindowedAggregate straight = new WindowedAggregate("count", p, Map.of());
        WindowedAggregate before = new WindowedAggregate("count", p, Map.of());
        for (int i = 0; i < 3; i++) {
            EventMeta m = new EventMeta(base + i * 60_000L, Long.MIN_VALUE, null);
            straight.add(1, m);
            before.add(1, m);
        }

        Serializable snap = roundTrip(before.snapshot());
        WindowedAggregate after = new WindowedAggregate("count", p, Map.of());
        after.restore(snap);

        for (int i = 3; i < 6; i++) {
            EventMeta m = new EventMeta(base + i * 60_000L, Long.MIN_VALUE, null);
            straight.add(1, m);
            after.add(1, m);
        }

        long probe = base + 6 * 60_000L;
        assertEquals(straight.value(probe), after.value(probe),
                "恢复后窗口内的计数应与不中断一致");
        // 探针时刻减 300 秒 = base+60000,窗口内只剩 ts > base+60000 的 4 条
        assertEquals(4L, after.value(probe));
    }

    @Test
    @DisplayName("滚动窗口快照恢复后,窗口切换仍然正确")
    void tumblingWindowSurvivesRestore() throws Exception {
        Period p = Period.parse("hourly", "1");
        long hour = 3_600_000L;

        WindowedAggregate before = new WindowedAggregate("count", p, Map.of());
        for (int i = 0; i < 3; i++) {
            before.add(1, new EventMeta(hour + i * 1000L, Long.MIN_VALUE, null));
        }
        Serializable snap = roundTrip(before.snapshot());

        WindowedAggregate after = new WindowedAggregate("count", p, Map.of());
        after.restore(snap);
        assertEquals(3L, after.value(hour + 3000L), "恢复后应保留本窗口已累计的计数");

        // 跨到下一个整点,状态必须重置
        after.add(1, new EventMeta(2 * hour + 1000L, Long.MIN_VALUE, null));
        assertEquals(1L, after.value(2 * hour + 1000L), "跨窗口后应重置");
    }

    @Test
    @DisplayName("去重计数降级为 HLL 后,快照恢复的基数估计完全一致")
    void degradedDistinctCountSurvivesRestore() throws Exception {
        Map<String, Object> cfg = Map.of("approx_threshold", 20);
        Accumulator before = Operators.create("distinct_count", cfg);
        for (int i = 0; i < 500; i++) {
            before.add("v" + i, EventMeta.at(1000 + i));
        }
        assertEquals(true, before.meta().get("approximate"), "应已降级为近似");
        Object valueBefore = before.value();

        Serializable snap = roundTrip(before.snapshot());
        Accumulator after = Operators.create("distinct_count", cfg);
        after.restore(snap);

        assertNotNull(valueBefore);
        assertEquals(valueBefore, after.value(),
                "HLL 恢复后的基数估计必须与快照前完全一致 —— 重启不该改变统计结果");
        assertEquals(true, after.meta().get("approximate"));
    }
}
