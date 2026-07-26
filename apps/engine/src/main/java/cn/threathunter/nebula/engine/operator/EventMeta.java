package cn.threathunter.nebula.engine.operator;

/**
 * 一条输入的伴随信息。
 *
 * @param timestamp  事件时间(毫秒)。规格 §2.3:first/last/lastn 依据它排序,
 *                   而不是到达顺序 —— 这是与 1.x 的一处语义差异。
 * @param watermark  流级水位线。规格 §4.2:迟到判定依据的是<b>整个输入流</b>的
 *                   水位线,不是按 key 维护 —— 否则每个新 key 的首个事件都不可能
 *                   被判为迟到,攻击者可用不断变化的 key 绕过检测。
 * @param groupValue 分组字段的值,供 group_count / group_sum / top 使用。
 */
public record EventMeta(long timestamp, long watermark, Object groupValue) {

    public static final EventMeta EMPTY = new EventMeta(0L, Long.MIN_VALUE, null);

    public static EventMeta at(long timestamp) {
        return new EventMeta(timestamp, Long.MIN_VALUE, null);
    }

    public static EventMeta of(long timestamp, Object groupValue) {
        return new EventMeta(timestamp, Long.MIN_VALUE, groupValue);
    }
}
