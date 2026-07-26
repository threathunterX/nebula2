package cn.threathunter.nebula.engine.operator;

import java.io.Serializable;
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
 *
 * <p>全部算子实现 {@link Accumulator#snapshot()} / {@link Accumulator#restore},
 * 状态以普通数据形式导出,供 Flink Checkpoint 使用。快照里不含算子对象本身 ——
 * 否则恢复会依赖类的具体实现,重构即失效。
 */
public final class Operators {

    private Operators() {
    }

    /** 算子工厂。config 目前用到 param(如 lastn 的 N、top 的 N)与去重模式。 */
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
        REGISTRY.put("count", cfg -> new CountAccumulator());
        REGISTRY.put("sum", cfg -> new SumAccumulator());
        REGISTRY.put("max", cfg -> new MinMaxAccumulator(true));
        REGISTRY.put("min", cfg -> new MinMaxAccumulator(false));
        REGISTRY.put("avg", cfg -> new MomentAccumulator("avg"));
        REGISTRY.put("variance", cfg -> new MomentAccumulator("variance"));
        REGISTRY.put("stddev", cfg -> new MomentAccumulator("stddev"));
        REGISTRY.put("cv", cfg -> new MomentAccumulator("cv"));
        REGISTRY.put("distinct_count", DistinctCountAccumulator::new);
        REGISTRY.put("first", cfg -> new PickAccumulator("first"));
        REGISTRY.put("last", cfg -> new PickAccumulator("last"));
        REGISTRY.put("last_value", cfg -> new PickAccumulator("last_value"));
        REGISTRY.put("global_latest", cfg -> new PickAccumulator("global_latest"));
        REGISTRY.put("lastn", cfg -> new LastNAccumulator(intParam(cfg, 10)));
        REGISTRY.put("distinct", cfg -> new DistinctAccumulator());
        REGISTRY.put("collection", cfg -> new CollectionAccumulator());
        REGISTRY.put("group_count", cfg -> new GroupAccumulator(false));
        REGISTRY.put("group_sum", cfg -> new GroupAccumulator(true));
        REGISTRY.put("merge", cfg -> new MergeAccumulator(false));
        REGISTRY.put("merge_value", cfg -> new MergeAccumulator(true));
        REGISTRY.put("top", cfg -> new TopAccumulator(intParam(cfg, 100)));
        REGISTRY.put("topn", cfg -> new TopAccumulator(intParam(cfg, 100)));
    }

    // ==================================================================== 计数

    /** 规格 §2.1:空窗口返回 0。 */
    private static final class CountAccumulator extends AbstractAccumulator {
        private long n;

        @Override
        protected void doAdd(Object v, EventMeta m) {
            n++;
        }

        @Override
        public Object value() {
            return n;
        }

        @Override
        public String name() {
            return "count";
        }

        @Override
        public Serializable snapshot() {
            return n;
        }

        @Override
        public void restore(Serializable s) {
            n = ((Number) s).longValue();
        }
    }

    /** 规格 §2.2:空窗口返回 0(与 max/min 返回 null 形成对比)。 */
    private static final class SumAccumulator extends AbstractAccumulator {
        private double s;

        @Override
        protected void doAdd(Object v, EventMeta m) {
            s += toDouble(v);
        }

        @Override
        public Object value() {
            return normalizeNumber(s);
        }

        @Override
        public String name() {
            return "sum";
        }

        @Override
        public Serializable snapshot() {
            return s;
        }

        @Override
        public void restore(Serializable st) {
            s = ((Number) st).doubleValue();
        }
    }

    // ==================================================================== 数值

    /** 规格 §2.2:空窗口返回 null,不是 0。 */
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

        @Override
        public Serializable snapshot() {
            return v;
        }

        @Override
        public void restore(Serializable s) {
            v = s == null ? null : ((Number) s).doubleValue();
        }
    }

    /**
     * avg / variance / stddev / cv —— 共用一份中间统计量(n, sum, 平方和)。
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
                case "avg":
                    // 规格 §2.2:空窗口返回 null —— 0 条数据的平均值无定义
                    return n == 0 ? null : normalizeNumber(sum / n);
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
                        return null; // 规格:均值为 0 返回 null,不是 Infinity
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

        @Override
        public Serializable snapshot() {
            return new ArrayList<>(List.of(n, sum, sq));
        }

        @Override
        public void restore(Serializable s) {
            List<?> l = (List<?>) s;
            n = ((Number) l.get(0)).longValue();
            sum = ((Number) l.get(1)).doubleValue();
            sq = ((Number) l.get(2)).doubleValue();
        }
    }

    // ==================================================================== 取值

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

        @Override
        public Serializable snapshot() {
            ArrayList<Object> out = new ArrayList<>();
            out.add(v);
            out.add(ts);
            return out;
        }

        @Override
        public void restore(Serializable s) {
            List<?> l = (List<?>) s;
            v = l.get(0);
            ts = l.get(1) == null ? null : ((Number) l.get(1)).longValue();
        }
    }

    /** 规格 §2.3:按时间倒序(最新在前),不足 N 条返回全部、不补位。 */
    private static final class LastNAccumulator extends AbstractAccumulator {
        private final int n;
        private final List<Object> values = new ArrayList<>();
        private final List<Long> times = new ArrayList<>();
        private final List<Long> seqs = new ArrayList<>();
        private long seq;

        LastNAccumulator(int n) {
            this.n = n;
        }

        @Override
        protected void doAdd(Object value, EventMeta m) {
            values.add(value);
            times.add(m.timestamp());
            seqs.add(seq++);
        }

        @Override
        public Object value() {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                idx.add(i);
            }
            // 时间相同时按到达顺序倒序,保证结果稳定可比
            idx.sort((a, b) -> {
                int c = Long.compare(times.get(b), times.get(a));
                return c != 0 ? c : Long.compare(seqs.get(b), seqs.get(a));
            });
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, idx.size()); i++) {
                out.add(values.get(idx.get(i)));
            }
            return out;
        }

        @Override
        public String name() {
            return "lastn";
        }

        @Override
        public Serializable snapshot() {
            ArrayList<Object> out = new ArrayList<>();
            out.add(new ArrayList<>(values));
            out.add(new ArrayList<>(times));
            out.add(new ArrayList<>(seqs));
            out.add(seq);
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            List<?> l = (List<?>) s;
            values.clear();
            values.addAll((List<Object>) l.get(0));
            times.clear();
            for (Object t : (List<Object>) l.get(1)) {
                times.add(((Number) t).longValue());
            }
            seqs.clear();
            for (Object t : (List<Object>) l.get(2)) {
                seqs.add(((Number) t).longValue());
            }
            seq = ((Number) l.get(3)).longValue();
        }
    }

    /** 规格 §2.3:按首次出现顺序去重。 */
    private static final class DistinctAccumulator extends AbstractAccumulator {
        private final LinkedHashSet<Object> seen = new LinkedHashSet<>();

        @Override
        protected void doAdd(Object v, EventMeta m) {
            seen.add(v);
        }

        @Override
        public Object value() {
            return new ArrayList<>(seen);
        }

        @Override
        public String name() {
            return "distinct";
        }

        @Override
        public Serializable snapshot() {
            return new ArrayList<>(seen);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            seen.clear();
            seen.addAll((List<Object>) s);
        }
    }

    /** 规格 §2.3:保持到达顺序,不去重。 */
    private static final class CollectionAccumulator extends AbstractAccumulator {
        private final List<Object> items = new ArrayList<>();

        @Override
        protected void doAdd(Object v, EventMeta m) {
            items.add(v);
        }

        @Override
        public Object value() {
            return new ArrayList<>(items);
        }

        @Override
        public String name() {
            return "collection";
        }

        @Override
        public Serializable snapshot() {
            return new ArrayList<>(items);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            items.clear();
            items.addAll((List<Object>) s);
        }
    }

    // ==================================================================== 分组

    /** group_count / group_sum。分组值为 null 时跳过。空窗口返回空 map。 */
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
            m.merge(String.valueOf(g), sum ? toDouble(value) : 1.0, Double::sum);
        }

        @Override
        public Object value() {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, normalizeNumber(v)));
            return out;
        }

        @Override
        public String name() {
            return sum ? "group_sum" : "group_count";
        }

        @Override
        public Serializable snapshot() {
            return new HashMap<>(m);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            m.clear();
            ((Map<String, Object>) s).forEach((k, v) -> m.put(k, ((Number) v).doubleValue()));
        }
    }

    // ==================================================================== 合并

    /** merge 键冲突取较新的值;merge_value 键冲突对值求和。 */
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

        @Override
        public Serializable snapshot() {
            ArrayList<Object> out = new ArrayList<>();
            out.add(new HashMap<>(m));
            out.add(new HashMap<>(ts));
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            List<?> l = (List<?>) s;
            m.clear();
            m.putAll((Map<String, Object>) l.get(0));
            ts.clear();
            ((Map<String, Object>) l.get(1)).forEach((k, v) -> ts.put(k, ((Number) v).longValue()));
        }
    }

    // ==================================================================== TopN

    /** 规格 §1.6:按值降序;值相等时按 key 字典序升序,保证结果稳定。 */
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
                double val = rows.get(i).getValue();
                row.put("value", normalizeNumber(val));
                out.add(row);
            }
            return out;
        }

        @Override
        public String name() {
            return "top";
        }

        @Override
        public Serializable snapshot() {
            return new HashMap<>(m);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(Serializable s) {
            m.clear();
            ((Map<String, Object>) s).forEach((k, v) -> m.put(k, ((Number) v).doubleValue()));
        }
    }
}
