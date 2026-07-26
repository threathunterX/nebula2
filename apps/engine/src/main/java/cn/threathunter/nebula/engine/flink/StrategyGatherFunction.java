package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.rule.PrecomputedValueProvider;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 按事件 ID 汇聚各维度的中间结果,凑齐后做策略判定。
 *
 * <p>上游按 {@code eventId} keyBy,因此同一条事件的各维度结果落在同一实例上。
 * 每条事件要等多少份由 {@link DimensionPlan#expectedSignals} 决定 —— 只统计该事件
 * <b>实际携带了值</b>的维度,否则一条匿名访问事件会永远等不到账号维度的结果。
 *
 * <p>判定用 {@link PrecomputedValueProvider},与单并行度走的是<b>同一套</b>
 * {@link StrategyEngine} 判定逻辑,差别只在值从哪来。这样并行版与单机版不会出现
 * 结果不一致这类最难排查的问题。
 *
 * <p>汇聚状态用 Flink ManagedState({@link ListState}),因此这一段是支持
 * Checkpoint 的;维度计算那一段尚未迁移,见 {@link DimensionVariableFunction}。
 */
public final class StrategyGatherFunction
        extends KeyedProcessFunction<String, PartialResult, StrategyEngine.Notice> {

    private static final long serialVersionUID = 1L;

    private final List<Map<String, Object>> strategies;
    private final List<Map<String, Object>> eventDefs;

    private transient ListState<PartialResult> pending;
    private transient StrategyEngine engine;
    private transient int emitted;

    public StrategyGatherFunction(List<Map<String, Object>> strategies,
                                  List<Map<String, Object>> eventDefs) {
        this.strategies = strategies;
        this.eventDefs = eventDefs;
    }

    @Override
    public void open(OpenContext ctx) {
        pending = getRuntimeContext().getListState(
                new ListStateDescriptor<>("pending-partials", PartialResult.class));
        // graph 传 null:值全部由汇聚而来,不在此处现算
        engine = new StrategyEngine(strategies, null, new EventModel(eventDefs));
        // 去重在下游按主体分区的独立阶段做,见 NoticeDedupFunction
        engine.setDedupEnabled(false);
        emitted = 0;
    }

    @Override
    public void processElement(PartialResult in, Context ctx,
                               Collector<StrategyEngine.Notice> out) throws Exception {
        pending.add(in);

        List<PartialResult> got = new ArrayList<>();
        for (PartialResult p : pending.get()) {
            got.add(p);
        }
        if (got.size() < in.expected()) {
            return; // 还没凑齐
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Object> counters = new LinkedHashMap<>();
        for (PartialResult p : got) {
            variables.putAll(p.variables());
            counters.putAll(p.counters());
        }
        pending.clear();

        engine.processWith(in.event(), in.eventName(), in.timestamp(),
                new PrecomputedValueProvider(variables, counters));

        List<StrategyEngine.Notice> all = engine.notices();
        for (int i = emitted; i < all.size(); i++) {
            out.collect(all.get(i));
        }
        emitted = all.size();
    }
}
