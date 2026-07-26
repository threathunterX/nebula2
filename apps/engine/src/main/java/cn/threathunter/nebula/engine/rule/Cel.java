package cn.threathunter.nebula.engine.rule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CEL 表达式求值 —— 只覆盖内置资产实际用到的子集。
 *
 * <p>生产引擎最终应改用标准 cel-java;本实现的作用是让引擎能跑通那 3 条使用 CEL
 * 的策略,并与参考实现的 {@code src/cel.js} 逐条对齐。函数语义的规范定义见
 * {@code packages/cel-functions/README.md}。
 *
 * <p>支持:{@code inTimeWindow("HH:MM","HH:MM")}、
 * {@code checkNotice(keyType,keyValue,strategyName,withinSeconds) > N}、
 * {@code ipLocation(field,"level") == "值"}、{@code ... in [...]}、{@code !(...)}。
 */
public final class Cel {

    private Cel() {
    }

    /** 部署时区。规格:「深夜」是业务含义,必须按当地时间判断,不是 UTC。 */
    private static volatile ZoneId zone = ZoneId.of("Asia/Shanghai");

    public static void setZone(ZoneId z) {
        zone = z == null ? ZoneId.of("Asia/Shanghai") : z;
    }

    @FunctionalInterface
    public interface LocationResolver {
        String resolve(String ip, String level);
    }

    private static volatile LocationResolver locationResolver = (ip, level) -> "unknown";

    public static void setLocationResolver(LocationResolver r) {
        locationResolver = r == null ? (ip, level) -> "unknown" : r;
    }

    private static final Pattern CALL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)$");
    private static final Pattern IN = Pattern.compile("^(.+?)\\s+in\\s+(\\[.*])$");
    private static final Pattern CMP = Pattern.compile("^(.+?)\\s*(==|!=)\\s*(.+)$");
    /** checkNotice 返回整数,需要数值比较。 */
    private static final Pattern NUM =
            Pattern.compile("^(.+?)\\s*(>=|<=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)$");

    /**
     * 告警历史查询,供 {@code checkNotice} 做策略级联判定。
     *
     * <p>区间两端都含。{@code null} 表示当前求值上下文没有历史可查 —— 那时调用
     * checkNotice 会抛,而不是返回 0:返回 0 会让一条永远不命中的策略看起来在正常工作。
     */
    @FunctionalInterface
    public interface NoticeHistory {
        int count(String checkType, String key, String strategyName, long fromTs, long toTs);
    }

    /**
     * keyType 归一化。
     *
     * <p>规格最初写的取值是 {@code ip} / {@code uid} / {@code did} / {@code page} ——
     * 那是 1.x 的词汇,而 2.0 的名单主体类型是 check_type。<b>{@code page} 没有对应的
     * 名单类型</b>,按它查永远查不到东西,所以不接受它。
     */
    private static String normalizeKeyType(String t) {
        switch (t) {
            case "IP", "USER", "DeviceID", "OrderID" -> {
                return t;
            }
            default -> {
                String alias = switch (t.toLowerCase()) {
                    case "ip" -> "IP";
                    case "uid" -> "USER";
                    case "did" -> "DeviceID";
                    case "order_id" -> "OrderID";
                    default -> null;
                };
                if (alias == null) {
                    throw new IllegalArgumentException(
                            "checkNotice 的 keyType 取值非法: " + t
                            + "(可取 IP / USER / DeviceID / OrderID,或 1.x 别名 ip / uid / did / order_id)");
                }
                return alias;
            }
        }
    }
    private static final Pattern HHMM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    public static boolean eval(String expr, Map<String, Object> event) {
        return eval(expr, event, null);
    }

    public static boolean eval(String expr, Map<String, Object> event, NoticeHistory history) {
        String s = expr == null ? "" : expr.trim();
        if (s.startsWith("!(") && s.endsWith(")")) {
            return !eval(s.substring(2, s.length() - 1), event, history);
        }
        Matcher in = IN.matcher(s);
        if (in.matches()) {
            Object left = term(in.group(1), event, history);
            for (String item : parseList(in.group(2))) {
                if (item.equals(String.valueOf(left))) {
                    return true;
                }
            }
            return false;
        }
        Matcher num = NUM.matcher(s);
        if (num.matches()) {
            double left = toDouble(term(num.group(1), event, history));
            double right = Double.parseDouble(num.group(3));
            return switch (num.group(2)) {
                case ">" -> left > right;
                case "<" -> left < right;
                case ">=" -> left >= right;
                default -> left <= right;
            };
        }
        Matcher cmp = CMP.matcher(s);
        if (cmp.matches()) {
            Object left = term(cmp.group(1), event, history);
            String right = stripQuotes(cmp.group(3).trim());
            boolean eq = String.valueOf(left).equals(right);
            return "==".equals(cmp.group(2)) == eq;
        }
        Object v = term(s, event, history);
        return Boolean.TRUE.equals(v);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return Double.NaN;   // NaN 与任何数比较都为假 —— 与「取不到值就不命中」一致
        }
    }

    private static Object term(String src, Map<String, Object> event, NoticeHistory history) {
        String s = src.trim();
        Matcher m = CALL.matcher(s);
        if (m.matches()) {
            String fn = m.group(1);
            List<Object> args = new ArrayList<>();
            for (String a : splitArgs(m.group(2))) {
                String t = a.trim();
                if (t.startsWith("\"") || t.startsWith("'")) {
                    args.add(stripQuotes(t));
                } else if (t.matches("-?\\d+(\\.\\d+)?")) {
                    args.add(Double.parseDouble(t));
                } else {
                    args.add(event.get(t)); // 裸标识符 = 事件字段
                }
            }
            return call(fn, args, event, history);
        }
        if (s.startsWith("\"") || s.startsWith("'")) {
            return stripQuotes(s);
        }
        return event.get(s);
    }

    private static Object call(String fn, List<Object> args, Map<String, Object> event,
                              NoticeHistory history) {
        switch (fn) {
            case "checkNotice": {
                // 语义与参考引擎逐条对齐,见 packages/reference-engine/src/cel.js。
                // 数的是**已产出**的告警,不含被去重压掉的那些 —— 去重意味着这条告警
                // 没有被报出去,而级联判定问的是「之前报过没有」。
                if (history == null) {
                    throw new IllegalStateException(
                            "checkNotice 需要告警历史,当前求值上下文没有提供");
                }
                String type = normalizeKeyType(String.valueOf(args.get(0)));
                String key = args.get(1) == null ? "" : String.valueOf(args.get(1));
                String strategy = String.valueOf(args.get(2));
                double within = toDouble(args.get(3));
                if (!(within > 0)) {
                    throw new IllegalArgumentException(
                            "checkNotice 的 withinSeconds 应为正整数,实际: " + args.get(3));
                }
                Object ts = event.get("timestamp");
                if (!(ts instanceof Number n)) {
                    return 0;
                }
                long now = n.longValue();
                return history.count(type, key, strategy, now - (long) (within * 1000), now);
            }
            case "inTimeWindow": {
                int start = parseHHMM(String.valueOf(args.get(0)));
                int end = parseHHMM(String.valueOf(args.get(1)));
                Object ts = event.get("timestamp");
                if (!(ts instanceof Number n)) {
                    return false;
                }
                ZonedDateTime t = Instant.ofEpochMilli(n.longValue()).atZone(zone);
                int now = t.getHour() * 60 + t.getMinute();
                // 规格:start 含、end 不含;start > end 表示跨零点
                return start <= end ? (now >= start && now < end) : (now >= start || now < end);
            }
            case "ipLocation": {
                Object ip = args.get(0);
                if (ip == null || String.valueOf(ip).isEmpty()) {
                    return "unknown";
                }
                String level = args.size() > 1 ? String.valueOf(args.get(1)) : "province";
                try {
                    String r = locationResolver.resolve(String.valueOf(ip), level);
                    // 规格:查询失败或无结果返回 unknown,不抛异常
                    return r == null || r.isEmpty() ? "unknown" : r;
                } catch (RuntimeException e) {
                    return "unknown";
                }
            }
            default:
                throw new IllegalArgumentException(
                        "未实现的 CEL 函数: " + fn + "(定义见 packages/cel-functions/README.md)");
        }
    }

    private static int parseHHMM(String s) {
        Matcher m = HHMM.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("inTimeWindow 的时刻格式应为 HH:MM,实际: " + s);
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    private static List<String> parseList(String s) {
        List<String> out = new ArrayList<>();
        String body = s.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String p : splitArgs(body)) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(stripQuotes(t));
            }
        }
        return out;
    }

    private static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                cur.append(ch);
                continue;
            }
            if (ch == '(' || ch == '[') {
                depth++;
            }
            if (ch == ')' || ch == ']') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
