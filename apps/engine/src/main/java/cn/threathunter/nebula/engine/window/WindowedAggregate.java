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
}
