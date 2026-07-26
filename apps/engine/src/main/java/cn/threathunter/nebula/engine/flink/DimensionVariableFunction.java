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
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 单个维度的变量与计数器计算。
 *
 * <p>上游按该维度 {@code keyBy},因此同一个 key(如同一个 IP)的全部事件都落在
 * 同一并行实例上,状态完整。每个实例只算<b>属于本维度</b>的值。
 *
 * <p>状态放在实例的普通字段而非 Flink ManagedState —— 这意味着当前实现<b>不支持
 * Checkpoint 恢复</b>。把变量图与窗口状态迁到 ManagedState 是下一步,需要为
 * {@code WindowedAggregate} 设计序列化格式。
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

        out.collect(r);
    }
}
