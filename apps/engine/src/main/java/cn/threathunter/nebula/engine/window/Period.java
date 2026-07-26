package cn.threathunter.nebula.engine.window;

/**
 * 时间窗口定义,对应 {@code docs/reference/operators.md} §4.1。
 *
 * @param kind   窗口种类
 * @param sizeMs 窗口长度(毫秒)。NONE / UNBOUNDED 时无意义。
 */
public record Period(Kind kind, long sizeMs) {

    public enum Kind {
        /** 滑动窗口:last_n_seconds / last_n_hours / last_n_days */
        SLIDING,
        /** 滚动窗口,整点对齐:hourly */
        TUMBLING,
        /** 自然日窗口:today */
        DAILY,
        /** 无界:ever */
        UNBOUNDED,
        /** 无窗口:self */
        NONE
    }

    /** 由变量定义中的 period 解析。未实现的类型必须报错,不能静默当作无窗口。 */
    public static Period parse(String type, String value) {
        long n = 1;
        if (value != null && !value.isBlank()) {
            try {
                n = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                n = 1;
            }
        }
        if (type == null) {
            type = "";
        }
        return switch (type) {
            case "last_n_seconds" -> new Period(Kind.SLIDING, n * 1000L);
            case "last_n_hours" -> new Period(Kind.SLIDING, n * 3_600_000L);
            case "last_n_days" -> new Period(Kind.SLIDING, n * 86_400_000L);
            case "hourly" -> new Period(Kind.TUMBLING, n * 3_600_000L);
            case "today" -> new Period(Kind.DAILY, 86_400_000L);
            case "ever" -> new Period(Kind.UNBOUNDED, 0);
            case "self", "" -> new Period(Kind.NONE, 0);
            default -> throw new IllegalArgumentException(
                    "未实现的窗口类型: " + type + "(规格见 docs/reference/operators.md §4.1)");
        };
    }
}
