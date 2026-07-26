package cn.threathunter.nebula.engine.rule;

import cn.threathunter.nebula.engine.condition.Conditions;
import java.util.List;
import java.util.Map;

/**
 * 内联计数器的共用逻辑。
 *
 * <p>单并行度与并行两种模式都用这里的实现,保证计数语义只有一份。
 */
public final class Counters {

    private Counters() {
    }

    /** 计数器的状态键:策略名 + 条件树位置 + 分组值。 */
    public static String stateKey(String strategyName, String path,
                                  Map<String, Object> def, Map<String, Object> event) {
        StringBuilder sb = new StringBuilder(strategyName).append('|').append(path).append('|');
        for (String g : groupBy(def)) {
            Object v = event.get(g);
            sb.append(v == null ? "" : v);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<String> groupBy(Map<String, Object> def) {
        return (List<String>) def.getOrDefault("groupby", List.of());
    }

    /**
     * 计数器所属的维度字段。
     *
     * <p>内置资产中每个计数器都只按单一维度分组(c_ip / uid / did),这是并行化
     * 能按维度拆链路的前提。出现多维度分组时抛错而不是猜。
     */
    public static String dimensionOf(Map<String, Object> def) {
        List<String> g = groupBy(def);
        if (g.size() != 1) {
            throw new IllegalArgumentException(
                    "并行模式暂不支持非单一维度的内联计数器,实际分组键: " + g);
        }
        return g.get(0);
    }

    /** 该事件是否应计入此计数器。 */
    @SuppressWarnings("unchecked")
    public static boolean passesFilter(Map<String, Object> def, Map<String, Object> event) {
        Object f = def.get("filter");
        return evalFilter(f instanceof Map ? (Map<String, Object>) f : null, event);
    }

    @SuppressWarnings("unchecked")
    private static boolean evalFilter(Map<String, Object> f, Map<String, Object> event) {
        if (f == null || f.isEmpty()) {
            return true;
        }
        String type = String.valueOf(f.getOrDefault("type", "simple"));
        if ("simple".equals(type)) {
            Object field = f.get("object");
            return Conditions.eval(String.valueOf(f.get("operation")),
                    field == null ? null : event.get(String.valueOf(field)), f.get("value"));
        }
        List<Map<String, Object>> subs =
                (List<Map<String, Object>>) f.getOrDefault("condition", List.of());
        return switch (type) {
            case "and" -> subs.stream().allMatch(s -> evalFilter(s, event));
            case "or" -> subs.stream().anyMatch(s -> evalFilter(s, event));
            case "not" -> !subs.isEmpty() && !evalFilter(subs.get(0), event);
            default -> throw new IllegalArgumentException("未知的条件组合类型: " + type);
        };
    }
}
