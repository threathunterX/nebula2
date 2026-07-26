package cn.threathunter.nebula.engine.operator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 全部聚合算子的实现。
 *
 * <p>逐条对应 {@code docs/reference/operators.md} §2,并与 JS 参考实现共享
 * {@code tests/golden/vectors/operators.json} 中的测试向量。
 *
 * <p>每个算子的注释标注了它实现的是规格中的哪一条规定,尤其是空窗口的返回值 ——
 * 那是最容易被实现者按直觉猜错的地方(例如 {@code sum} 空窗口返回 0,而
 * {@code max} 返回 null)。
 */
public final class Operators {

    private Operators() {
    }

    /** 算子工厂。config 目前只用到 param(如 lastn 的 N、top 的 N)。 */
    public static Accumulator create(String method, Map<String, Object> config) {
        Function<Map<String, Object>, Accumulator> f = REGISTRY.get(method);
        if (f == null) {
            throw new IllegalArgumentException(
                    "未实现的聚合算子: " + method + "(规格见 docs/reference/operators.md)");
        }
        return f.apply(config == null ? Map.of() : config);
    }

    public static boolean isSupported(String method) {
        return REGISTRY.containsKey(method);
    }

    public static java.util.Set<String> supported() {
        return REGISTRY.keySet();
    }

    private static final Map<String, Function<Map<String, Object>, Accumulator>> REGISTRY =
            new LinkedHashMap<>();

    private static int intParam(Map<String, Object> cfg, int fallback) {
        Object p = cfg.get("param");
        if (p == null || String.valueOf(p).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(p));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static {
        // ---------------- 计数类 ----------------
        REGISTRY.put("count", cfg -> new AbstractAccumulator() {
            private long n;

            @Override
            protected void doAdd(Object v, EventMeta m) {
                n++;
            }

            @Override
            public Object value() {
                return n; // 规格 §2.1:空窗口返回 0
            }

            @Override
            public String name() {
                return "count";
            }
        });

        REGISTRY.put("group_count", cfg -> new GroupAccumulator(false));
        REGISTRY.put("group_sum", cfg -> new GroupAccumulator(true));

        // ---------------- 数值类 ----------------
        REGISTRY.put("sum", cfg -> new AbstractAccumulator() {
            private double s;

            @Override
            protected void doAdd(Object v, EventMeta m) {
                s += toDouble(v);
            }

            @Override
            public Object value() {
                return normalizeNumber(s); // 规格 §2.2:空窗口返回 0
            }

            @Override
            public String name() {
                return "sum";
            }
        });

        REGISTRY.put("max", cfg -> new MinMaxAccumulator(true));
        REGISTRY.put("min", cfg -> new MinMaxAccumulator(false));

        REGISTRY.put("avg", cfg -> new AbstractAccumulator() {
            private long n;
            private double s;

            @Override
            protected void doAdd(Object v, EventMeta m) {
                n++;
                s += toDouble(v);
            }

            @Override
            public Object value() {
                // 规格 §2.2:空窗口返回 null —— 0 条数据的平均值无定义
                return n == 0 ? null : normalizeNumber(s / n);
            }

            @Override
            public String name() {
                return "avg";
            }
        });

        REGISTRY.put("variance", cfg -> new MomentAccumulator("variance"));
        REGISTRY.put("stddev", cfg -> new MomentAccumulator("stddev"));
        REGISTRY.put("cv", cfg -> new MomentAccumulator("cv"));

        // ---------------- 去重计数 ----------------
        REGISTRY.put("distinct_count", DistinctCountAccumulator::new);

        // ---------------- 取值类 ----------------
        REGISTRY.put("first", cfg -> new PickAccumulator("first"));
        REGISTRY.put("last", cfg -> new PickAccumulator("last"));
        REGISTRY.put("last_value", cfg -> new PickAccumulator("last_value"));
        REGISTRY.put("global_latest", cfg -> new PickAccumulator("global_latest"));

        REGISTRY.put("lastn", cfg -> new LastNAccumulator(intParam(cfg, 10)));

        REGISTRY.put("distinct", cfg -> new AbstractAccumulator() {
            private final LinkedHashSet<Object> seen = new LinkedHashSet<>();

            @Override
            protected void doAdd(Object v, EventMeta m) {
                seen.add(v); // LinkedHashSet 保证「按首次出现顺序」
            }

            @Override
            public Object value() {
                return new ArrayList<>(seen);
            }

            @Override
            public String name() {
                return "distinct";
            }
        });

        REGISTRY.put("collection", cfg -> new AbstractAccumulator() {
            private final List<Object> items = new ArrayList<>();

            @Override
            protected void doAdd(Object v, EventMeta m) {
                items.add(v); // 规格 §2.3:保持到达顺序,不去重
            }

            @Override
            public Object value() {
                return new ArrayList<>(items);
            }

            @Override
            public String name() {
                return "collection";
            }
        });

        // ---------------- 合并类 ----------------
        REGISTRY.put("merge", cfg -> new MergeAccumulator(false));
        REGISTRY.put("merge_value", cfg -> new MergeAccumulator(true));

        // ---------------- TopN ----------------
        REGISTRY.put("top", cfg -> new TopAccumulator(intParam(cfg, 100)));
        REGISTRY.put("topn", cfg -> new TopAccumulator(intParam(cfg, 100)));
    }

    // ==================================================================== 实现

    /** max / min:规格 §2.2 空窗口返回 null,不是 0。 */
    private static final class MinMaxAccumulator extends AbstractAccumulator {
        private final boolean max;
        private Double v;

        MinMaxAccumulator(boolean max) {
            this.max = max;
        }

        @Override
        protected void doAdd(Object value, EventMeta m) {
            double d = toDouble(value);
            if (v == null || (max ? d > v : d < v)) {
                v = d;
            }
        }

        @Override
        public Object value() {
            return v == null ? null : normalizeNumber(v);
        }

        @Override
        public String name() {
            return max ? "max" : "min";
        }
    }

    /**
     * variance / stddev / cv —— 共用一份中间统计量。
     *
     * <p><b>与 1.x 的关键差异</b>:1.x 中名为 stddev 的算子实际返回<b>方差</b>,
     * 没有开平方;而 cv 内部才做了 sqrt。2.0 更正:stddev 返回真正的标准差,
     * 并提供独立的 variance 算子。迁移工具默认把 1.x 的 stddev 映射为 variance。
     */
    private static final class MomentAccumulator extends AbstractAccumulator {
        private final String kind;
        private long n;
        private double sum;
        private double sq;

        MomentAccumulator(String kind) {
            this.kind = kind;
        }

        @Override
        protected void doAdd(Object value, EventMeta m) {
            double d = toDouble(value);
            n++;
            sum += d;
            sq += d * d;
        }

        private double sampleVariance() {
            if (n <= 1) {
                return 0.0; // 规格 §2.2:n <= 1 返回 0.0
            }
            double mean = sum / n;
            return (sq - sum * mean) / (n - 1);
        }

        @Override
        public Object value() {
            switch (kind) {
                case "variance":
                    return sampleVariance();
                case "stddev":
                    return Math.sqrt(sampleVariance());
                case "cv": {
                    if (n <= 1) {
                        return null;
                    }
                    double mean = sum / n;
                    if (mean == 0.0) {
                        return null; // 规格 §2.2:均值为 0 返回 null,不是 Infinity
                    }
                    return Math.sqrt(sampleVariance()) / mean;
                }
                default:
                    throw new IllegalStateException(kind);
            }
        }

        @Override
        public String name() {
            return kind;
        }
    }

    /** first / last / last_value / global_latest —— 依据<b>事件时间</b>而非到达顺序。 */
    private static final class PickAccumulator extends AbstractAccumulator {
        private final String kind;
        private final boolean earliest;
        private Object v;
        private Long ts;

        PickAccumulator(String kind) {
            this.kind = kind;
            this.earliest = "first".equals(kind);
        }

        @Override
        protected void doAdd(Object value, EventMeta m) {
            long t = m.timestamp();
            if (ts == null || (earliest ? t < ts : t >= ts)) {
                ts = t;
                v = value;
            }
        }

        @Override
        public Object value() {
            return v; // 规格 §2.3:空窗口返回 null
        }

        @Override
        public String name() {
            return kind;
        }
    }

    /** lastn:规格 §2.3 按时间倒序(最新在前),不足 N 条返回全部、不补位。 */
    private static final class LastNAccumulator extends AbstractAccumulator {
        private record Item(Object v, long ts, long seq) {
        }

        private final int n;
        private final List<Item> items = new ArrayList<>();
        private long seq;

        LastNAccumulator(int n) {
            this.n = n;
        }

        @Override
        protected void doAdd(Object value, EventMeta m) {
            items.add(new Item(value, m.timestamp(), seq++));
        }

        @Override
        public Object value() {
            List<Item> sorted = new ArrayList<>(items);
            // 时间相同时按到达顺序倒序,保证结果稳定可比
            sorted.sort((a, b) -> {
                int c = Long.compare(b.ts(), a.ts());
                return c != 0 ? c : Long.compare(b.seq(), a.seq());
            });
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, sorted.size()); i++) {
                out.add(sorted.get(i).v());
            }
            return out;
        }

        @Override
        public String name() {
            return "lastn";
        }
    }

    /** group_count / group_sum:按分组字段再分组。分组值为 null 时跳过。 */
    private static final class GroupAccumulator extends AbstractAccumulator {
        private final boolean sum;
        private final Map<String, Double> m = new TreeMap<>();

        GroupAccumulator(boolean sum) {
            this.sum = sum;
        }

        @Override
        protected void doAdd(Object value, EventMeta meta) {
            Object g = meta.groupValue();
            if (g == null) {
                return;
            }
            double delta = sum ? toDouble(value) : 1.0;
            m.merge(String.valueOf(g), delta, Double::sum);
        }

        @Override
        public Object value() {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, normalizeNumber(v)));
            return out; // 规格 §2.1:空窗口返回空 map
        }

        @Override
        public String name() {
            return sum ? "group_sum" : "group_count";
        }
    }

    /** merge / merge_value:键冲突时前者取较新的值,后者对值求和。 */
    private static final class MergeAccumulator extends AbstractAccumulator {
        private final boolean sumValues;
        private final Map<String, Object> m = new TreeMap<>();
        private final Map<String, Long> ts = new HashMap<>();

        MergeAccumulator(boolean sumValues) {
            this.sumValues = sumValues;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doAdd(Object value, EventMeta meta) {
            if (!(value instanceof Map<?, ?> src)) {
                return;
            }
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) src).entrySet()) {
                String k = String.valueOf(e.getKey());
                if (sumValues) {
                    double cur = m.containsKey(k) ? toDouble(m.get(k)) : 0.0;
                    m.put(k, cur + toDouble(e.getValue()));
                } else {
                    Long prev = ts.get(k);
                    if (prev == null || meta.timestamp() >= prev) {
                        m.put(k, e.getValue());
                        ts.put(k, meta.timestamp());
                    }
                }
            }
        }

        @Override
        public Object value() {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, v instanceof Number num
                    ? normalizeNumber(num.doubleValue()) : v));
            return out;
        }

        @Override
        public String name() {
            return sumValues ? "merge_value" : "merge";
        }
    }

    /** top / topn:规格 §1.6 按值降序;值相等时按 key 字典序升序,保证结果稳定。 */
    private static final class TopAccumulator extends AbstractAccumulator {
        private final int n;
        private final Map<String, Double> m = new TreeMap<>();

        TopAccumulator(int n) {
            this.n = n;
        }

        @Override
        protected void doAdd(Object value, EventMeta meta) {
            Object g = meta.groupValue();
            if (g == null) {
                return;
            }
            double delta = value instanceof Number num ? num.doubleValue() : 1.0;
            m.merge(String.valueOf(g), delta, Double::sum);
        }

        @Override
        public Object value() {
            List<Map.Entry<String, Double>> rows = new ArrayList<>(m.entrySet());
            rows.sort((a, b) -> {
                int c = Double.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, rows.size()); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", rows.get(i).getKey());
                row.put("value", normalizeNumber(rows.get(i).getValue()));
                out.add(row);
            }
            return out;
        }

        @Override
        public String name() {
            return "top";
        }
    }
}
