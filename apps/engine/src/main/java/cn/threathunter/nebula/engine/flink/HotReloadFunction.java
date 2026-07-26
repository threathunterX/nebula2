package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.meta.MetadataClient;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 把 {@link RiskDetectionFunction} 接到广播的元数据流上,实现策略热更新。
 *
 * <p>元数据经 broadcast 下发而不是各算子自行轮询:后者会让并行实例在一小段时间内按
 * 不同版本判定同一批事件,结果取决于事件落在哪个实例上。广播让新版本在流里有确定的
 * 位置,所有实例在同一个点切换。
 *
 * <p><b>这里刻意不用 broadcast state 存元数据。</b>Flink 的 broadcast state 会把内容
 * 纳入 checkpoint,而元数据的权威来源是控制面 —— 存进 checkpoint 意味着从旧 checkpoint
 * 恢复时会带回一份过期策略,并且要等下一次轮询才纠正。作业启动时本来就会从控制面拉
 * 一次全量,让它每次都以控制面为准更简单也更正确。
 */
public final class HotReloadFunction
        extends BroadcastProcessFunction<Map<String, Object>, MetadataClient.Bundle,
                                         StrategyEngine.Notice> {

    private static final long serialVersionUID = 1L;

    /** Flink 要求提供描述符,但本函数不往里写任何东西,理由见类注释。 */
    public static final MapStateDescriptor<Void, Void> UNUSED =
            new MapStateDescriptor<>("nebula-metadata-unused", Void.class, Void.class);

    private final RiskDetectionFunction inner;

    public HotReloadFunction(RiskDetectionFunction inner) {
        this.inner = inner;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        // 不能调 inner.open():inner 不是 Flink 直接管理的算子,它的
        // getRuntimeContext() 会抛「The runtime context has not been initialized」。
        // 指标组由这里传进去 —— 这个坑是靠作业进入 RESTARTING 才发现的,
        // 编译期与单元测试都不会暴露它。
        inner.openWithout(parameters);
        inner.initMetrics(getRuntimeContext().getMetricGroup());
    }

    @Override
    public void processElement(Map<String, Object> event, ReadOnlyContext ctx,
                               Collector<StrategyEngine.Notice> out) throws Exception {
        inner.processElement(event, null, out);
    }

    @Override
    public void processBroadcastElement(MetadataClient.Bundle bundle, Context ctx,
                                        Collector<StrategyEngine.Notice> out) {
        Set<String> cold = inner.reload(bundle.strategies(), bundle.variables(), bundle.version());
        // 冷启动的变量必须说出来:它们要经过一个完整窗口期才给出有意义的值,
        // 不说清楚会被当成「热更新之后策略不准了」
        System.out.println("策略已热更新到 v" + bundle.version()
                + (cold.isEmpty() ? "(无新增变量)" : ",冷启动变量 " + cold.size() + " 个: " + cold));
    }

    /** 便于从作业里取事件流的类型信息。 */
    public static List<String> noop() {
        return List.of();
    }
}
