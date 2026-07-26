package cn.threathunter.nebula.engine.condition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 过滤与比较条件算子,对应 {@code docs/reference/operators.md} §3。
 *
 * <p>规格要求<b>下表全部算子均有实现</b>。1.x 声明了完整算子集但引擎只实现了其中
 * 一小部分(字符串仅 contains/==/!=,Double 仅 &lt;),用户配得出来的变量跑不了 ——
 * 这正是 2.0 引入 schema 强制校验的直接动因。
 *
 * <p>两条贯穿性约定:
 * <ul>
 *   <li><b>类型严格</b>:实际类型与声明类型不符时判定为不通过,不做隐式转换</li>
 *   <li><b>null 判定为不通过</b>(empty / !empty 除外,它们就是用来判空的)</li>
 * </ul>
 */
public final class Conditions {

    private Conditions() {
    }

    /** IP 归属地查询。缺省返回 unknown,与规格 §3.4 一致(查询失败不使条件报错)。 */
    @FunctionalInterface
    public interface LocationResolver {
        String resolve(String ip, String level);
    }

    private static volatile LocationResolver locationResolver = (ip, level) -> "unknown";

    public static void setLocationResolver(LocationResolver r) {
        locationResolver = r == null ? (ip, level) -> "unknown" : r;
    }

    private static final Map<String, Pattern> REGEX_CACHE = new LinkedHashMap<>();

    private static Pattern compile(String pattern) {
        synchronized (REGEX_CACHE) {
            return REGEX_CACHE.computeIfAbsent(pattern, p -> {
                try {
                    return Pattern.compile("^(?:" + p + ")$"); // 规格:整串匹配语义
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("条件正则无法编译: " + p, e);
                }
            });
        }
    }

    /** 求值单个条件。 */
    public static boolean eval(String op, Object left, Object right) {
        if (op == null) {
            throw new IllegalArgumentException("条件算子为空");
        }
        return switch (op) {
            case "==" -> looseEquals(left, right);
            case "!=" -> !looseEquals(left, right);

            case "empty" -> left == null || "".equals(left);
            case "!empty" -> !(left == null || "".equals(left));

            case ">" -> compareNum(left, right, c -> c > 0);
            case ">=" -> compareNum(left, right, c -> c >= 0);
            case "<" -> compareNum(left, right, c -> c < 0);
            case "<=" -> compareNum(left, right, c -> c <= 0);

            case "contains" -> strOp(left, right, (a, b) -> a.contains(b));
            case "!contains" -> !strOp(left, right, (a, b) -> a.contains(b));
            case "startwith" -> strOp(left, right, String::startsWith);
            case "!startwith" -> !strOp(left, right, String::startsWith);
            case "endwith" -> strOp(left, right, String::endsWith);
            case "!endwith" -> !strOp(left, right, String::endsWith);
            case "regex" -> strOp(left, right, (a, b) -> compile(b).matcher(a).matches());
            case "!regex" -> !strOp(left, right, (a, b) -> compile(b).matcher(a).matches());
            // containsby:反向包含 —— 字段值是给定值的子串
            case "containsby" -> strOp(left, right, (a, b) -> b.contains(a));
            case "!containsby" -> !strOp(left, right, (a, b) -> b.contains(a));
            case "in" -> inSet(left, right);
            case "!in" -> !inSet(left, right);

            case "locationequals" -> locationOf(left).equals(String.valueOf(right));
            case "!locationequals" -> !locationOf(left).equals(String.valueOf(right));
            case "locationcontainsby" -> splitSet(right).contains(locationOf(left));
            case "!locationcontainsby" -> !splitSet(right).contains(locationOf(left));

            default -> throw new IllegalArgumentException(
                    "未实现的条件算子: " + op + "(规格见 docs/reference/operators.md §3)");
        };
    }

    private static String locationOf(Object ip) {
        if (ip == null) {
            return "unknown";
        }
        String r = locationResolver.resolve(String.valueOf(ip), "province");
        return r == null || r.isEmpty() ? "unknown" : r;
    }

    /**
     * 相等比较。1.x 的常量一律以字符串存储(阈值 5 存为 "5"),因此比较时按
     * <b>左值类型</b>对齐 —— 这与迁移工具「常量值保持字符串、由引擎按左值类型
     * 转换」的约定配套。
     */
    private static boolean looseEquals(Object left, Object right) {
        if (left == null) {
            return false;
        }
        if (left instanceof Number ln) {
            Double rn = asNumber(right);
            return rn != null && ln.doubleValue() == rn;
        }
        if (left instanceof Boolean lb) {
            return lb == (Boolean.TRUE.equals(right) || "true".equals(String.valueOf(right)));
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static Double asNumber(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private interface IntPred {
        boolean test(int cmp);
    }

    private static boolean compareNum(Object left, Object right, IntPred pred) {
        if (!(left instanceof Number ln)) {
            return false; // 类型严格:非数值一律不通过,不做隐式转换
        }
        Double rn = asNumber(right);
        if (rn == null) {
            return false;
        }
        return pred.test(Double.compare(ln.doubleValue(), rn));
    }

    private interface StrPred {
        boolean test(String a, String b);
    }

    private static boolean strOp(Object left, Object right, StrPred pred) {
        if (!(left instanceof String s)) {
            return false; // 类型严格
        }
        return pred.test(s, String.valueOf(right));
    }

    private static List<String> splitSet(Object right) {
        if (right instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            list.forEach(x -> out.add(String.valueOf(x)));
            return out;
        }
        String s = String.valueOf(right);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            out.add(p.trim());
        }
        return out;
    }

    private static boolean inSet(Object left, Object right) {
        if (left == null) {
            return false;
        }
        return splitSet(right).contains(String.valueOf(left));
    }

    /** 全部已实现的算子,供 schema 一致性检查使用。 */
    public static List<String> supported() {
        return Arrays.asList(
                "==", "!=", "empty", "!empty",
                ">", ">=", "<", "<=",
                "contains", "!contains", "startwith", "!startwith",
                "endwith", "!endwith", "regex", "!regex",
                "containsby", "!containsby", "in", "!in",
                "locationequals", "!locationequals",
                "locationcontainsby", "!locationcontainsby");
    }
}
