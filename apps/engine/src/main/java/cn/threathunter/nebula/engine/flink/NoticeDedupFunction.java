package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.rule.StrategyEngine;
import java.util.HashMap;
import java.util.Map;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 告警去重 —— 按「策略 + 主体」分区。
 *
 * <p>这一段必须独立于判定阶段。判定阶段按事件 ID 分区(为了汇聚各维度的中间结果),
 * 而去重的分组键是策略与主体 —— 同一个 IP 的多条事件会落到不同的判定实例上,
 * 若在那里去重,每个实例各持一份状态、互相看不见,同一主体会被重复告警。
 *
 * <p>这个问题是并行度提高到 4 之后被 {@code ParallelJobTest} 抓出来的:告警数
 * 从 13 变成 24。并行度 1 和 2 时恰好没暴露 —— 这正是「并行只应改变吞吐、不应
 * 改变结果」这条测试存在的意义。
 *
 * <p>状态用 Flink ManagedState,支持 Checkpoint。
 */
public final class NoticeDedupFunction
        extends KeyedProcessFunction<String, StrategyEngine.Notice, StrategyEngine.Notice> {

    private static final long serialVersionUID = 1L;

    /** 各策略的去重窗口(毫秒),构建期从策略定义中提取。 */
    private final Map<String, Long> windowByStrategy;
    private final long defaultWindowMs;

    private transient ValueState<Long> lastEmitted;

    public NoticeDedupFunction(Map<String, Long> windowByStrategy, long defaultWindowMs) {
        this.windowByStrategy = windowByStrategy == null ? new HashMap<>() : windowByStrategy;
        this.defaultWindowMs = defaultWindowMs;
    }

    @Override
    public void open(OpenContext ctx) {
        lastEmitted = getRuntimeContext().getState(
                new ValueStateDescriptor<>("last-emitted-ts", Long.class));
    }

    @Override
    public void processElement(StrategyEngine.Notice n, Context ctx,
                               Collector<StrategyEngine.Notice> out) throws Exception {
        long window = windowByStrategy.getOrDefault(n.strategyName(), defaultWindowMs);
        Long last = lastEmitted.value();
        if (last != null && n.timestamp() - last < window) {
            return; // 窗口内已告警过同一主体
        }
        lastEmitted.update(n.timestamp());
        out.collect(n);
    }
}
