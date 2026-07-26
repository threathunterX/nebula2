package cn.threathunter.nebula.engine.window;

import cn.threathunter.nebula.engine.operator.Accumulator;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.operator.Operators;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个 key 上的窗口聚合状态,对应 {@code docs/reference/operators.md} §4。
 *
 * <p>三条关键规定:
 * <ol>
 *   <li>窗口按<b>事件时间</b>划分,不是处理时间</li>
 *   <li>allowedLateness 之内的迟到事件仍会更新窗口;超出则进侧输出,<b>不静默丢弃</b>
 *       —— 这正是 1.x 需要每小时离线重算的根因之一</li>
 *   <li>聚合结果在窗口内<b>持续可见</b>,不等窗口关闭才输出 —— 风控要在攻击进行中
 *       就能判定</li>
 * </ol>
 *
 * <p><b>水位线是流级属性,不按 key 维护。</b>调用方负责推进并通过 EventMeta 传入。
 * 若按 key 维护,每个新出现的 key 的首个事件都不可能被判为迟到,攻击者用不断变化的
 * IP 或设备号即可绕过迟到检测。
 */
public final class WindowedAggregate {

    /** 迟到事件的处置结果。 */
    public enum Outcome {
        ACCEPTED,
        LATE_ACCEPTED,
        /** 超出容忍度,进侧输出并计数 —— 不是静默丢弃 */
        LATE_DROPPED
    }

    public static final long DEFAULT_ALLOWED_LATENESS_MS = 60_000L;

    private record Entry(Object value, EventMeta meta) {
    }

    private final String method;
    private final Map<String, Object> config;
    private final Period period;
    private final long allowedLatenessMs;

    private final List<Entry> events = new ArrayList<>(); // 滑动窗口用
    private Accumulator acc;                              // 滚动 / 无界用
    private Long currentWindowStart;
    private long lateAccepted;
    private long lateDropped;

    public WindowedAggregate(String method, Period period, Map<String, Object> config) {
        this(method, period, config, DEFAULT_ALLOWED_LATENESS_MS);
    }

    public WindowedAggregate(String method, Period period, Map<String, Object> config,
                             long allowedLatenessMs) {
        this.method = method;
        this.period = period;
        this.config = config == null ? Map.of() : config;
        this.allowedLatenessMs = allowedLatenessMs;
    }

    private long windowStartFor(long ts) {
        long size = period.kind() == Period.Kind.DAILY ? 86_400_000L : period.sizeMs();
        return Math.floorDiv(ts, size) * size;
    }

    public Outcome add(Object value, EventMeta meta) {
        long ts = meta.timestamp();
        long wm = meta.watermark();

        if (wm != Long.MIN_VALUE && ts < wm - allowedLatenessMs) {
            lateDropped++;
            return Outcome.LATE_DROPPED;
        }
        boolean late = wm != Long.MIN_VALUE && ts < wm;
        if (late) {
            lateAccepted++;
        }

        switch (period.kind()) {
            case SLIDING -> events.add(new Entry(value, meta));
            case TUMBLING, DAILY -> {
                long start = windowStartFor(ts);
                if (currentWindowStart == null || start > currentWindowStart) {
                    currentWindowStart = start;
                    acc = Operators.create(method, config); // 窗口切换即重置
                } else if (start < currentWindowStart) {
                    lateDropped++;
                    return Outcome.LATE_DROPPED; // 属于已关闭的历史窗口
                }
                acc.add(value, meta);
            }
            default -> {
                if (acc == null) {
                    acc = Operators.create(method, config);
                }
                acc.add(value, meta);
            }
        }
        return late ? Outcome.LATE_ACCEPTED : Outcome.ACCEPTED;
    }

    /**
     * 当前累计值。滑动窗口在此按给定时刻裁剪过期事件后重算。
     *
     * <p>参考实现同样采用「保留原始事件、取值时重算」的朴素做法 —— 它把语义摆在
     * 明面上,便于对照。生产实现应改为增量聚合,但结果必须一致。
     */
    public Object value(long now) {
        if (period.kind() == Period.Kind.SLIDING) {
            long cutoff = now - period.sizeMs();
            events.removeIf(e -> e.meta().timestamp() <= cutoff);
            Accumulator a = Operators.create(method, config);
            for (Entry e : events) {
                a.add(e.value(), e.meta());
            }
            return a.value();
        }
        return acc != null ? acc.value() : Operators.create(method, config).value();
    }

    public long lateAccepted() {
        return lateAccepted;
    }

    public long lateDropped() {
        return lateDropped;
    }

    // ------------------------------------------------------------ Checkpoint

    /**
     * 导出窗口状态,供 Flink Checkpoint 使用。
     *
     * <p>滑动窗口导出的是<b>窗口内尚未过期的原始输入</b>(值 + 时间戳)。这样做保证
     * 恢复后的语义与快照前完全一致 —— 代价是状态大小与窗口内事件数成正比。
     *
     * <p>高流量场景下可改为时间分桶的增量聚合以压缩状态,但那会把过期粒度从「精确到
     * 事件」放宽到「精确到桶」,是可见的语义变化,需要先改规格。当前选择精确语义。
     */
    public java.io.Serializable snapshot() {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        out.add(period.kind().name());
        out.add(currentWindowStart);
        out.add(lateAccepted);
        out.add(lateDropped);
        if (period.kind() == Period.Kind.SLIDING) {
            java.util.ArrayList<Object> vals = new java.util.ArrayList<>();
            java.util.ArrayList<Object> tss = new java.util.ArrayList<>();
            for (Entry e : events) {
                vals.add(e.value());
                tss.add(e.meta().timestamp());
            }
            out.add(vals);
            out.add(tss);
            out.add(null);
        } else {
            out.add(null);
            out.add(null);
            out.add(acc == null ? null : acc.snapshot());
        }
        return out;
    }

    /** 从快照恢复。传入的必须是同一算子与窗口类型的产物。 */
    @SuppressWarnings("unchecked")
    public void restore(java.io.Serializable state) {
        java.util.List<?> l = (java.util.List<?>) state;
        String kind = String.valueOf(l.get(0));
        if (!kind.equals(period.kind().name())) {
            throw new IllegalStateException(
                    "窗口类型不匹配:快照为 " + kind + ",当前为 " + period.kind());
        }
        currentWindowStart = l.get(1) == null ? null : ((Number) l.get(1)).longValue();
        lateAccepted = ((Number) l.get(2)).longValue();
        lateDropped = ((Number) l.get(3)).longValue();
        events.clear();
        acc = null;
        if (period.kind() == Period.Kind.SLIDING) {
            java.util.List<Object> vals = (java.util.List<Object>) l.get(4);
            java.util.List<Object> tss = (java.util.List<Object>) l.get(5);
            for (int i = 0; i < vals.size(); i++) {
                long ts = ((Number) tss.get(i)).longValue();
                events.add(new Entry(vals.get(i), new EventMeta(ts, Long.MIN_VALUE, null)));
            }
        } else if (l.get(6) != null) {
            acc = Operators.create(method, config);
            acc.restore((java.io.Serializable) l.get(6));
        }
    }
}
