package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.rule.Counters;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.Serializable;
import java.util.LinkedHashMap;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 单个维度的变量与计数器计算。
 *
 * <p>上游按该维度 {@code keyBy},因此同一个 key(如同一个 IP)的全部事件都落在
 * 同一并行实例上,状态完整。每个实例只算<b>属于本维度</b>的值。
 *
 * <p>变量图与计数器的状态存放在 Flink ManagedState 中,<b>支持 Checkpoint 恢复</b>。
 * 每处理一条事件:从 state 恢复 → 计算 → 写回 state。这样做每条事件都有一次
 * 序列化往返的开销,换取的是故障恢复后结果与不中断完全一致(由
 * {@code SnapshotRestoreTest} 守住)。高吞吐场景可改为增量状态,但那需要先把滑动
 * 窗口从「保留原始事件」改成分桶聚合,会把过期粒度从精确到事件放宽到精确到桶。
 */
public final class DimensionVariableFunction
        extends KeyedProcessFunction<String, Map<String, Object>, PartialResult> {

    private static final long serialVersionUID = 1L;

    private final String dimension;
    private final DimensionPlan plan;
    private final List<Map<String, Object>> variableDefs;
    private final List<Map<String, Object>> eventDefs;

    private transient VariableGraph graph;
    private transient Map<String, WindowedAggregate> counters;
    private transient long watermark;
    private transient ValueState<Serializable> graphState;
    private transient ValueState<Serializable> counterState;

    public DimensionVariableFunction(String dimension, DimensionPlan plan,
                                     List<Map<String, Object>> variableDefs,
                                     List<Map<String, Object>> eventDefs) {
        this.dimension = dimension;
        this.plan = plan;
        this.variableDefs = variableDefs;
        this.eventDefs = eventDefs;
    }

    @Override
    public void open(OpenContext ctx) {
        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : variableDefs) {
            vars.add(new VariableDef(m));
        }
        Set<String> wanted = new LinkedHashSet<>(plan.variablesOf(dimension));
        graph = wanted.isEmpty() ? null
                : new VariableGraph(vars, wanted, new EventModel(eventDefs));
        counters = new HashMap<>();
        watermark = Long.MIN_VALUE;
        graphState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("graph-state", Serializable.class));
        counterState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("counter-state", Serializable.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void processElement(Map<String, Object> event, Context ctx,
                               Collector<PartialResult> out) {
        Object tsObj = event.get("timestamp");
        if (!(tsObj instanceof Number tsNum)) {
            return;
        }
        long ts = tsNum.longValue();
        watermark = Math.max(watermark, ts);
        String eventName = String.valueOf(event.get("name"));
        String eventId = String.valueOf(event.get(NebulaParallelJob.EVENT_ID_FIELD));

        // 从 ManagedState 恢复本 key 的状态
        try {
            if (graph != null) {
                graph.importState(graphState.value());
            }
            restoreCounters(counterState.value());
        } catch (Exception e) {
            throw new IllegalStateException("恢复维度状态失败: " + dimension, e);
        }

        PartialResult r = PartialResult.of(eventId, dimension, event, eventName, ts,
                plan.expectedSignals(event));

        if (graph != null) {
            graph.process(event, eventName, ts);
            for (String v : plan.variablesOf(dimension)) {
                r.variables().put(v, graph.valueOf(v, event, ts));
            }
        }

        for (DimensionPlan.CounterRef ref : plan.countersOf(dimension)) {
            String stateKey = Counters.stateKey(ref.strategyName(), ref.path(),
                    ref.def(), event);
            WindowedAggregate agg = counters.computeIfAbsent(stateKey, k ->
                    new WindowedAggregate(String.valueOf(ref.def().get("algorithm")),
                            Period.parse("last_n_seconds",
                                    String.valueOf(ref.def().get("window"))),
                            Map.of()));
            if (Counters.passesFilter(ref.def(), event)) {
                List<String> operand =
                        (List<String>) ref.def().getOrDefault("operand", List.of());
                Object v = operand.isEmpty() ? Long.valueOf(1) : event.get(operand.get(0));
                agg.add(v, new EventMeta(ts, watermark, null));
            }
            r.counters().put(ref.id(), agg.value(ts));
        }

        // 写回 ManagedState
        try {
            if (graph != null) {
                graphState.update(graph.exportState());
            }
            counterState.update(snapshotCounters());
        } catch (Exception e) {
            throw new IllegalStateException("保存维度状态失败: " + dimension, e);
        }

        out.collect(r);
    }

    private Serializable snapshotCounters() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.snapshot()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private void restoreCounters(Serializable state) {
        counters.clear();
        if (state == null) {
            return;
        }
        Map<String, Object> in = (Map<String, Object>) state;
        for (DimensionPlan.CounterRef ref : plan.countersOf(dimension)) {
            for (Map.Entry<String, Object> e : in.entrySet()) {
                if (!e.getKey().startsWith(ref.strategyName() + "|" + ref.path() + "|")) {
                    continue;
                }
                WindowedAggregate agg = new WindowedAggregate(
                        String.valueOf(ref.def().get("algorithm")),
                        Period.parse("last_n_seconds", String.valueOf(ref.def().get("window"))),
                        Map.of());
                agg.restore((Serializable) e.getValue());
                counters.put(e.getKey(), agg);
            }
        }
    }
}
