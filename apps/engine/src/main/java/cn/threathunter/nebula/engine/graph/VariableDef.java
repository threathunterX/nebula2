package cn.threathunter.nebula.engine.graph;

import cn.threathunter.nebula.engine.condition.Conditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 变量定义 —— {@code seeds/variables/*.json} 的运行时表示。
 *
 * <p>刻意用 Map 承载原始 JSON 而不引入 POJO 绑定:算子层要保持零运行时依赖,
 * 而变量定义的权威结构在 {@code packages/domain-schema/variable-model.schema.json},
 * 由 schema 校验保证形状正确,这里只做取值。
 */
public final class VariableDef {

    private final Map<String, Object> raw;

    public VariableDef(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    private String str(String k) {
        Object v = raw.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String k) {
        Object v = raw.get(k);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    public String name() {
        return str("name");
    }

    public String type() {
        return str("type");
    }

    public String module() {
        return str("module");
    }

    @SuppressWarnings("unchecked")
    public List<String> sources() {
        Object v = raw.get("source");
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    out.add(String.valueOf(m.get("name")));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<String> groupByKeys() {
        Object v = raw.get("groupbykeys");
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    public String periodType() {
        return String.valueOf(map("period").getOrDefault("type", ""));
    }

    public String periodValue() {
        Object v = map("period").get("value");
        return v == null ? "" : String.valueOf(v);
    }

    public String method() {
        return String.valueOf(map("function").getOrDefault("method", ""));
    }

    public String functionObject() {
        Object v = map("function").get("object");
        return v == null ? "" : String.valueOf(v);
    }

    public String functionParam() {
        Object v = map("function").get("param");
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> functionConfig() {
        Object v = map("function").get("config");
        Map<String, Object> cfg = v instanceof Map ? (Map<String, Object>) v : Map.of();
        String p = functionParam();
        if (p.isEmpty()) {
            return cfg;
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>(cfg);
        merged.put("param", p);
        return merged;
    }

    public int intParam(int fallback) {
        try {
            return Integer.parseInt(functionParam());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 求值该变量的过滤条件。空对象表示不过滤。 */
    public boolean evalFilter(Map<String, Object> event) {
        return evalFilterNode(map("filter"), event);
    }

    @SuppressWarnings("unchecked")
    private static boolean evalFilterNode(Map<String, Object> f, Map<String, Object> event) {
        if (f == null || f.isEmpty()) {
            return true;
        }
        String type = String.valueOf(f.getOrDefault("type", "simple"));
        if ("simple".equals(type)) {
            Object field = f.get("object");
            return Conditions.eval(String.valueOf(f.get("operation")),
                    field == null ? null : event.get(String.valueOf(field)),
                    f.get("value"));
        }
        Object subs = f.get("condition");
        List<Map<String, Object>> list = new ArrayList<>();
        if (subs instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map) {
                    list.add((Map<String, Object>) o);
                }
            }
        }
        return switch (type) {
            case "and" -> {
                for (Map<String, Object> s : list) {
                    if (!evalFilterNode(s, event)) {
                        yield false; // 短路
                    }
                }
                yield true;
            }
            case "or" -> {
                for (Map<String, Object> s : list) {
                    if (evalFilterNode(s, event)) {
                        yield true; // 短路
                    }
                }
                yield false;
            }
            case "not" -> !list.isEmpty() && !evalFilterNode(list.get(0), event);
            default -> throw new IllegalArgumentException("未知的条件组合类型: " + type);
        };
    }
}
