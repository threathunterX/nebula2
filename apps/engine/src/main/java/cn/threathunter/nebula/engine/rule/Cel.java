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
    private static final Pattern HHMM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    public static boolean eval(String expr, Map<String, Object> event) {
        String s = expr == null ? "" : expr.trim();
        if (s.startsWith("!(") && s.endsWith(")")) {
            return !eval(s.substring(2, s.length() - 1), event);
        }
        Matcher in = IN.matcher(s);
        if (in.matches()) {
            Object left = term(in.group(1), event);
            for (String item : parseList(in.group(2))) {
                if (item.equals(String.valueOf(left))) {
                    return true;
                }
            }
            return false;
        }
        Matcher cmp = CMP.matcher(s);
        if (cmp.matches()) {
            Object left = term(cmp.group(1), event);
            String right = stripQuotes(cmp.group(3).trim());
            boolean eq = String.valueOf(left).equals(right);
            return "==".equals(cmp.group(2)) == eq;
        }
        Object v = term(s, event);
        return Boolean.TRUE.equals(v);
    }

    private static Object term(String src, Map<String, Object> event) {
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
            return call(fn, args, event);
        }
        if (s.startsWith("\"") || s.startsWith("'")) {
            return stripQuotes(s);
        }
        return event.get(s);
    }

    private static Object call(String fn, List<Object> args, Map<String, Object> event) {
        switch (fn) {
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
