package cn.threathunter.nebula.engine.rule;

import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单并行度下的值来源:变量图与内联计数器都在本进程现算。
 *
 * <p>并行运行时改用 {@link PrecomputedValueProvider}。
 */
public final class LocalValueProvider implements ValueProvider {

    private final VariableGraph graph;
    private final Map<String, WindowedAggregate> counters = new HashMap<>();
    private long watermark = Long.MIN_VALUE;

    public LocalValueProvider(VariableGraph graph) {
        this.graph = graph;
    }

    public void advanceWatermark(long ts) {
        watermark = Math.max(watermark, ts);
    }

    @Override
    public Object variable(String name, Map<String, Object> event, long ts) {
        return graph == null ? null : graph.valueOf(name, event, ts);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object counter(String strategyName, String path, Map<String, Object> def,
                          Map<String, Object> event, long ts) {
        String id = Counters.stateKey(strategyName, path, def, event);
        WindowedAggregate agg = counters.computeIfAbsent(id, k ->
                new WindowedAggregate(String.valueOf(def.get("algorithm")),
                        Period.parse("last_n_seconds", String.valueOf(def.get("window"))),
                        Map.of()));
        if (Counters.passesFilter(def, event)) {
            List<String> operand = (List<String>) def.getOrDefault("operand", List.of());
            Object v = operand.isEmpty() ? Long.valueOf(1) : event.get(operand.get(0));
            agg.add(v, new EventMeta(ts, watermark, null));
        }
        return agg.value(ts);
    }
}
