package cn.threathunter.nebula.engine.sink;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * 批量写入 ClickHouse 的 Sink。
 *
 * <p>按条数或时间攒批 —— ClickHouse 对小批量高频写入很不友好(每次写入产生一个
 * part,merge 压力大),攒批是必须的,不是优化。
 *
 * <p><b>定时刷盘由后台线程驱动,不能只在新元素到达时检查。</b>否则低流量场景下
 * 缓冲区里的数据会一直等不到下一条元素而长期滞留 —— 这不是理论问题:实测中
 * 11 条事件因批次未满且无后续元素,始终没有落库。
 *
 * @param <T> 输入类型
 */
public final class ClickHouseSink<T> extends RichSinkFunction<T> {

    private static final long serialVersionUID = 1L;

    /**
     * 行映射函数。
     *
     * <p>必须自带 Serializable —— Flink 会把算子连同其闭包一起序列化分发到
     * TaskManager,而 {@code java.util.function.Function} 不是 Serializable,
     * 用它会在提交作业时报 NotSerializableException。
     */
    @FunctionalInterface
    public interface RowMapper<T> extends Serializable {
        String toRow(T value);
    }

    private final String table;
    private final RowMapper<T> toRow;
    private final int batchSize;
    private final long flushIntervalMs;
    private final String url;
    private final String user;
    private final String password;

    private transient ClickHouseWriter writer;
    private transient List<String> buffer;
    private transient long lastFlush;
    private transient ScheduledExecutorService flusher;
    private transient volatile Exception asyncFailure;

    public ClickHouseSink(String table, RowMapper<T> toRow,
                          int batchSize, long flushIntervalMs,
                          String url, String user, String password) {
        this.table = table;
        this.toRow = toRow;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
        this.flushIntervalMs = flushIntervalMs <= 0 ? 2000 : flushIntervalMs;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void open(OpenContext ctx) {
        writer = new ClickHouseWriter(url, user, password, 30_000);
        buffer = new ArrayList<>();
        lastFlush = System.currentTimeMillis();
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clickhouse-flush-" + table);
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(() -> {
            try {
                synchronized (this) {
                    if (System.currentTimeMillis() - lastFlush >= flushIntervalMs) {
                        flush();
                    }
                }
            } catch (Exception e) {
                // 后台线程不能直接抛出,记下来由主线程在下次 invoke 时抛出,
                // 否则写入失败会被静默吞掉
                asyncFailure = e;
            }
        }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void invoke(T value, Context ctx) throws Exception {
        if (asyncFailure != null) {
            Exception e = asyncFailure;
            asyncFailure = null;
            throw new IOException("后台刷盘失败", e);
        }
        buffer.add(toRow.toRow(value));
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public synchronized void finish() throws Exception {
        flush();
    }

    @Override
    public synchronized void close() throws Exception {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flush();
        if (writer != null) {
            writer.close();
        }
    }

    private void flush() throws IOException {
        if (buffer == null || buffer.isEmpty()) {
            return;
        }
        writer.insert(table, buffer);
        buffer.clear();
        lastFlush = System.currentTimeMillis();
    }
}
