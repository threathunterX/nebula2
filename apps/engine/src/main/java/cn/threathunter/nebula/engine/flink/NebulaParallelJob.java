package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;

/**
 * 并行拓扑:按维度拆链路计算,再按事件 ID 汇聚判定。
 *
 * <pre>
 *   events ─┬─ keyBy(c_ip) → IP 维度的变量与计数器 ─┐
 *           ├─ keyBy(uid)  → 账号维度            ─┼→ keyBy(eventId) → 汇聚 → 判定
 *           └─ keyBy(did)  → 设备维度            ─┘
 * </pre>
 *
 * <p>相比 {@link RiskDetectionFunction} 的单并行度实现,本拓扑可以水平扩展:
 * 各维度分区独立持有自己那份状态,互不干扰。
 *
 * <p>正确性由 {@code ParallelJobTest} 守住 —— 它在不同并行度下跑同一批事件,
 * 结果必须与参考引擎的固化快照完全一致。
 */
public final class NebulaParallelJob {

    /** 注入事件的唯一标识字段名,用于汇聚阶段分组。 */
    public static final String EVENT_ID_FIELD = "__nebula_event_id";

    private NebulaParallelJob() {
    }

    public static DataStream<StrategyEngine.Notice> build(
            DataStream<Map<String, Object>> events,
            List<Map<String, Object>> strategies,
            List<Map<String, Object>> variableDefs,
            List<Map<String, Object>> eventDefs) {

        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : variableDefs) {
            vars.add(new VariableDef(m));
        }
        DimensionPlan plan = new DimensionPlan(strategies, vars);
        if (plan.dimensions().isEmpty()) {
            throw new IllegalStateException("策略未引用任何变量或计数器,无需并行拓扑");
        }

        TypeInformation<PartialResult> partialType = TypeInformation.of(PartialResult.class);

        DataStream<PartialResult> merged = null;
        for (String dim : plan.dimensions()) {
            DataStream<PartialResult> branch = events
                    // 该事件在此维度上没有值就不参与 —— 与 expectedSignals 的口径一致
                    .filter(e -> {
                        Object v = e.get(dim);
                        return v != null && !String.valueOf(v).isEmpty();
                    })
                    .keyBy(e -> String.valueOf(e.get(dim)))
                    .process(new DimensionVariableFunction(dim, plan, variableDefs, eventDefs))
                    .returns(partialType)
                    .name("dim:" + dim);
            merged = merged == null ? branch : merged.union(branch);
        }

        java.util.Map<String, Long> dedupWindows = new java.util.LinkedHashMap<>();
        for (Map<String, Object> st : strategies) {
            Object w = st.get("dedup_window");
            dedupWindows.put(String.valueOf(st.get("name")),
                    w instanceof Number n ? n.longValue() * 1000L : 300_000L);
        }

        return merged
                .keyBy(PartialResult::eventId)
                .process(new StrategyGatherFunction(strategies, eventDefs))
                .name("gather-and-evaluate")
                // 去重必须按「策略 + 主体」分区,不能留在按事件 ID 分区的判定阶段
                .keyBy(n -> n.strategyName() + "|" + n.key())
                .process(new NoticeDedupFunction(dedupWindows, 300_000L))
                .name("dedup-by-subject");
    }
}
