package cn.threathunter.nebula.console.risk;

/**
 * 告警主体的展示脱敏。
 *
 * <p>{@code subject_key} 是被判定为风险的主体值 —— 可能是手机号、账号、设备号或
 * IP。运营要处置告警,必然要看到它;但「要看到」不等于「所有人随时都能看到全量」。
 *
 * <p>规则:VIEWER 看掩码值,OPERATOR / ADMIN 看原值且每次查询记审计。掩码保留
 * 首尾便于人工核对,中间隐去。这与采集端的脱敏是两件事:采集端决定什么不进系统,
 * 这里决定进了系统之后谁能看到。
 */
public final class SubjectMasking {

    private SubjectMasking() {
    }

    public static String mask(String checkType, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // IP 保留网段,末段隐去 —— 运营判断「是不是同一个网段来的」不需要精确到主机
        if ("IP".equalsIgnoreCase(checkType) && value.chars().filter(c -> c == '.').count() == 3) {
            int last = value.lastIndexOf('.');
            return value.substring(0, last + 1) + "*";
        }
        int n = value.length();
        if (n <= 2) {
            return "*".repeat(n);
        }
        if (n <= 6) {
            return value.charAt(0) + "*".repeat(n - 2) + value.charAt(n - 1);
        }
        return value.substring(0, 3) + "*".repeat(n - 6) + value.substring(n - 3);
    }
}
