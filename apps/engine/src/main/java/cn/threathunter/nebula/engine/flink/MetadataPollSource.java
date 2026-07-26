package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.meta.MetadataClient;
import org.apache.flink.streaming.api.functions.source.SourceFunction;

/**
 * 轮询控制面的元数据版本,版本变了才拉全量并向下游发一份。
 *
 * <p><b>为什么是一个 source 而不是让每个算子各自轮询。</b>各自轮询时,并行实例会在
 * 一小段时间内按不同版本的策略判定同一批事件 —— 结果取决于事件落在哪个实例上,
 * 而这既不可复现也无法解释。走 source + broadcast,新版本在流里有确定的位置,
 * 所有实例在同一个点切换。
 *
 * <p><b>先比版本号再拉全量。</b>{@code /version} 返回一个整数,全量 bundle 含 253 个
 * 变量与上百条策略。按默认 30 秒轮询算,一天 2880 次请求 —— 传整数与传全量的差别
 * 在控制面上是可观的。
 *
 * <p>拉取失败<b>不中断作业</b>:记一条日志,下一轮重试。作业带着上一版策略继续跑,
 * 远好于因为控制面短暂不可用就整体停摆。这与<b>启动时</b>拉取失败的处理刚好相反 ——
 * 启动时拿不到元数据必须失败,因为那时手上没有任何可用的策略。
 */
public final class MetadataPollSource implements SourceFunction<MetadataClient.Bundle> {

    private static final long serialVersionUID = 1L;

    private final String consoleUrl;
    private final String token;
    private final long intervalMillis;
    private volatile boolean running = true;

    public MetadataPollSource(String consoleUrl, String token, long intervalMillis) {
        this.consoleUrl = consoleUrl;
        this.token = token;
        this.intervalMillis = intervalMillis <= 0 ? 30_000 : intervalMillis;
    }

    @Override
    public void run(SourceContext<MetadataClient.Bundle> ctx) throws Exception {
        MetadataClient client = new MetadataClient(consoleUrl, token, 10_000);
        long known = -1;
        while (running) {
            try {
                long latest = client.version();
                if (latest != known) {
                    MetadataClient.Bundle b = client.bundle();
                    // 用 bundle 自带的版本而不是刚查到的 latest:两次请求之间可能又变了,
                    // 记下实际拿到的那一版才不会漏掉下一次变更
                    synchronized (ctx.getCheckpointLock()) {
                        ctx.collect(b);
                    }
                    known = b.version();
                    System.out.println("元数据更新到 v" + known
                            + "(事件 " + b.events().size()
                            + " / 变量 " + b.variables().size()
                            + " / 策略 " + b.strategies().size() + ")");
                }
            } catch (Exception e) {
                // 不中断作业:带着上一版策略继续跑,好过因为控制面短暂不可用而停摆
                System.err.println("拉取元数据失败,将在下一轮重试: " + e.getMessage());
            }
            Thread.sleep(intervalMillis);
        }
    }

    @Override
    public void cancel() {
        running = false;
    }
}
