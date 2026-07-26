package cn.threathunter.nebula.engine.graph;

import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 变量计算图,对应 {@code docs/reference/operators.md} §1。
 *
 * <p>一条事件进入后沿图自根向下传播,节点返回「不通过」即剪枝,下游不再计算。
 * 六类节点:event(图的根)、filter(过滤与派生,无状态)、aggregate(窗口聚合)、
 * dual(双变量二元运算)、sequence(相邻事件求差)、top(按值排序取前 N)。
 *
 * <p>与 JS 参考实现语义一致,由 {@code tests/golden/vectors/graph.json} 中的共享
 * 向量守住。
 */
public final class VariableGraph {

    private final Map<String, VariableDef> defs = new HashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final EventModel eventModel;
    private long watermark = Long.MIN_VALUE;

    /** 每个节点的状态:按 key 分槽。 */
    private static final class Node {
        final VariableDef def;
        final Map<String, WindowedAggregate> aggs = new HashMap<>();
        final Map<String, Object> values = new HashMap<>();  // dual / top 的结果
        final Map<String, Object> prev = new HashMap<>();    // sequence 的上一次值

        Node(VariableDef def) {
            this.def = def;
        }
    }

    public VariableGraph(List<VariableDef> variables, Set<String> needed, EventModel eventModel) {
        this.eventModel = eventModel;
        for (VariableDef v : variables) {
            defs.put(v.name(), v);
        }
        Set<String> scope = needed == null ? new LinkedHashSet<>(defs.keySet()) : closureOf(needed);
        build(scope);
    }

    /**
     * 依赖闭包。
     *
     * <p>event 类型变量的 source 按 1.x 惯例指向<b>同名事件</b>而非上游变量,
     * 展开会误判为自环,因此不展开。
     */
    private Set<String> closureOf(Set<String> names) {
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(names);
        while (!stack.isEmpty()) {
            String n = stack.remove(stack.size() - 1);
            if (!out.add(n)) {
                continue;
            }
            VariableDef d = defs.get(n);
            if (d == null || "event".equals(d.type())) {
                continue;
            }
            for (String s : d.sources()) {
                stack.add(s);
            }
        }
        return out;
    }

    /** 拓扑排序:被依赖者在前。存在环时抛错(schema 层也会拒绝,这里是双保险)。 */
    private void build(Set<String> scope) {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> done = new HashSet<>();
        List<String> sorted = new ArrayList<>(scope);
        sorted.sort(Comparator.naturalOrder());
        for (String n : sorted) {
            visit(n, visiting, done, new ArrayList<>());
        }
    }

    private void visit(String name, Set<String> visiting, Set<String> done, List<String> path) {
        if (done.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(name);
            throw new IllegalStateException("变量定义存在循环依赖: " + String.join(" -> ", cycle));
        }
        VariableDef def = defs.get(name);
        if (def != null && !"event".equals(def.type())) {
            List<String> next = new ArrayList<>(path);
            next.add(name);
            for (String s : def.sources()) {
                visit(s, visiting, done, next);
            }
        }
        visiting.remove(name);
        done.add(name);
        if (def != null) {
            order.add(name);
            nodes.put(name, new Node(def));
        }
    }

    public List<String> order() {
        return List.copyOf(order);
    }

    // ------------------------------------------------------------ Checkpoint

    /**
     * 导出全图状态,供 Flink Checkpoint 使用。
     *
     * <p>结构为 {@code 变量名 -> key -> 该 key 的状态}。聚合节点导出窗口快照,
     * dual / top 导出结果值,sequence 导出上一次值。
     */
    public java.io.Serializable exportState() {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            Node n = e.getValue();
            java.util.LinkedHashMap<String, Object> perKey = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, WindowedAggregate> a : n.aggs.entrySet()) {
                perKey.put("a:" + a.getKey(), a.getValue().snapshot());
            }
            for (Map.Entry<String, Object> v : n.values.entrySet()) {
                perKey.put("v:" + v.getKey(), v.getValue());
            }
            for (Map.Entry<String, Object> p : n.prev.entrySet()) {
                perKey.put("p:" + p.getKey(), p.getValue());
            }
            if (!perKey.isEmpty()) {
                out.put(e.getKey(), perKey);
            }
        }
        out.put("__watermark__", watermark);
        return out;
    }

    /** 从导出的状态恢复。图结构由构造函数决定,这里只填状态。 */
    @SuppressWarnings("unchecked")
    public void importState(java.io.Serializable state) {
        if (state == null) {
            return;
        }
        Map<String, Object> in = (Map<String, Object>) state;
        Object wm = in.get("__watermark__");
        if (wm instanceof Number num) {
            watermark = num.longValue();
        }
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            Node n = e.getValue();
            n.aggs.clear();
            n.values.clear();
            n.prev.clear();
            Object raw = in.get(e.getKey());
            if (!(raw instanceof Map)) {
                continue;
            }
            for (Map.Entry<String, Object> kv : ((Map<String, Object>) raw).entrySet()) {
                String k = kv.getKey();
                String key = k.substring(2);
                switch (k.charAt(0)) {
                    case 'a' -> {
                        WindowedAggregate agg = new WindowedAggregate(n.def.method(),
                                Period.parse(n.def.periodType(), n.def.periodValue()),
                                n.def.functionConfig());
                        agg.restore((java.io.Serializable) kv.getValue());
                        n.aggs.put(key, agg);
                    }
                    case 'v' -> n.values.put(key, kv.getValue());
                    case 'p' -> n.prev.put(key, kv.getValue());
                    default -> throw new IllegalStateException("未知的状态前缀: " + k);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 取值

    private String keyFor(VariableDef def, Map<String, Object> event) {
        List<String> keys = def.groupByKeys();
        if (keys.isEmpty()) {
            return "__GLOBAL__";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Object v = event.get(k);
            sb.append(v == null ? "" : String.valueOf(v));
        }
        return sb.toString();
    }

    /** 变量当前值。不存在时按算子的空窗口语义返回。 */
    public Object valueOf(String name, Map<String, Object> event, long now) {
        Node node = nodes.get(name);
        if (node == null) {
            return null;
        }
        String key = keyFor(node.def, event);
        WindowedAggregate agg = node.aggs.get(key);
        if (agg != null) {
            return agg.value(now);
        }
        if (node.values.containsKey(key)) {
            return node.values.get(key);
        }
        if ("aggregate".equals(node.def.type())) {
            return new WindowedAggregate(node.def.method(),
                    Period.parse(node.def.periodType(), node.def.periodValue()),
                    node.def.functionConfig()).value(now);
        }
        return null;
    }

    // ---------------------------------------------------------------- 传播

    /** 处理一条事件,返回本次产生了值的变量集合。 */
    public Set<String> process(Map<String, Object> event, String eventName, long ts) {
        watermark = Math.max(watermark, ts);
        Set<String> produced = new LinkedHashSet<>();
        for (String name : order) {
            Node node = nodes.get(name);
            try {
                if (step(name, node, event, eventName, ts, produced)) {
                    produced.add(name);
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException("变量「" + name + "」计算失败: " + e.getMessage(), e);
            }
        }
        return produced;
    }

    private boolean matchesEvent(String actual, String expected) {
        if (actual.equals(expected)) {
            return true;
        }
        return eventModel != null && eventModel.isA(actual, expected);
    }

    private boolean upstreamReady(VariableDef def, Set<String> produced, String eventName) {
        for (String s : def.sources()) {
            if (produced.contains(s) || matchesEvent(eventName, s)) {
                return true;
            }
        }
        return false;
    }

    private boolean step(String name, Node node, Map<String, Object> event,
                         String eventName, long ts, Set<String> produced) {
        VariableDef def = node.def;
        switch (def.type()) {
            case "event": {
                if (matchesEvent(eventName, name)) {
                    return true;
                }
                for (String s : def.sources()) {
                    if (matchesEvent(eventName, s)) {
                        return true;
                    }
                }
                return false;
            }

            case "filter":
                return upstreamReady(def, produced, eventName)
                        && def.evalFilter(event);

            case "aggregate": {
                if (!upstreamReady(def, produced, eventName) || !def.evalFilter(event)) {
                    return false;
                }
                String key = keyFor(def, event);
                WindowedAggregate agg = node.aggs.computeIfAbsent(key, k ->
                        new WindowedAggregate(def.method(),
                                Period.parse(def.periodType(), def.periodValue()),
                                def.functionConfig()));
                Object v;
                String obj = def.functionObject();
                if (obj == null || obj.isEmpty() || "value".equals(obj)) {
                    List<String> src = def.sources();
                    v = !src.isEmpty() && nodes.containsKey(src.get(0))
                            ? valueOf(src.get(0), event, ts)
                            : Long.valueOf(1);
                } else {
                    v = event.get(obj);
                }
                Object group = def.functionParam() == null || def.functionParam().isEmpty()
                        ? null : event.get(def.functionParam());
                agg.add(v, new EventMeta(ts, watermark, group));
                return true;
            }

            case "dual": {
                List<String> src = def.sources();
                if (src.size() != 2) {
                    throw new IllegalStateException("dual 变量必须恰好有两个上游");
                }
                Object a = valueOf(src.get(0), event, ts);
                Object b = valueOf(src.get(1), event, ts);
                if (!(a instanceof Number an) || !(b instanceof Number bn)) {
                    return false; // 规格 §1.5:任一为 null 则不通过
                }
                double x = an.doubleValue();
                double y = bn.doubleValue();
                double out;
                switch (def.method()) {
                    case "/" -> {
                        if (y == 0.0) {
                            return false; // 规格:除零不通过
                        }
                        out = x / y;
                    }
                    case "+" -> out = x + y;
                    case "-" -> out = x - y;
                    case "*" -> out = x * y;
                    default -> throw new IllegalStateException(
                            "dual 不支持的运算符: " + def.method());
                }
                // key 取自第二个上游(规格 §1.5)
                String key = keyFor(nodes.get(src.get(1)).def, event);
                node.values.put(key, out == Math.rint(out) && Math.abs(out) < 1e15
                        ? (Object) (long) out : (Object) out);
                return true;
            }

            case "sequence": {
                if (!upstreamReady(def, produced, eventName)) {
                    return false;
                }
                String obj = def.functionObject();
                Object cur = event.get(obj == null || obj.isEmpty() ? "timestamp" : obj);
                if (!(cur instanceof Number cn)) {
                    return false;
                }
                String key = keyFor(def, event);
                Object prev = node.prev.put(key, cur);
                if (!(prev instanceof Number pn)) {
                    return false; // 规格 §1.4:首条事件不通过
                }
                long diff = cn.longValue() - pn.longValue(); // 规格:当前值 − 上一次值
                node.values.put(key, diff);
                return true;
            }

            case "top": {
                List<String> src = def.sources();
                if (src.isEmpty() || !produced.contains(src.get(0))) {
                    return false;
                }
                Node parent = nodes.get(src.get(0));
                if (parent == null) {
                    return false;
                }
                Object raw = valueOf(src.get(0), event, ts);
                if (!(raw instanceof Map<?, ?> m)) {
                    return false;
                }
                int n = def.intParam(100);
                Map<String, Double> tmp = new TreeMap<>();
                m.forEach((k, v) -> {
                    if (v instanceof Number num) {
                        tmp.put(String.valueOf(k), num.doubleValue());
                    }
                });
                List<Map.Entry<String, Double>> rows = new ArrayList<>(tmp.entrySet());
                rows.sort((p, q) -> {
                    int c = Double.compare(q.getValue(), p.getValue());
                    return c != 0 ? c : p.getKey().compareTo(q.getKey());
                });
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < Math.min(n, rows.size()); i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", rows.get(i).getKey());
                    double val = rows.get(i).getValue();
                    row.put("value", val == Math.rint(val) ? (Object) (long) val : (Object) val);
                    out.add(row);
                }
                node.values.put(keyFor(def, event), out);
                return true;
            }

            default:
                throw new IllegalStateException("未实现的变量类型: " + def.type());
        }
    }
}
