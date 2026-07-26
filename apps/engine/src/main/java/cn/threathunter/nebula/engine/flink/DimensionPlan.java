package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.rule.Counters;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 并行拓扑的计算计划:把策略需要的每一个值,归到它所属的维度。
 *
 * <p>这是并行化的核心。变量按不同维度分组(c_ip / uid / did),一次 keyBy 只能选
 * 一个 —— 按 IP 分区后,账号维度变量的状态就被拆到不同并行实例上,结果错误。
 *
 * <p>解法是按维度拆链路再汇聚:
 * <pre>
 *   events ─┬─ keyBy(c_ip) → IP 维度的变量与计数器 ─┐
 *           ├─ keyBy(uid)  → 账号维度            ─┼→ keyBy(eventId) → 汇聚 → 判定
 *           └─ keyBy(did)  → 设备维度            ─┘
 * </pre>
 *
 * <p>本类负责在作业构建期把「哪些值属于哪个维度」算清楚,运行期各分区只算自己
 * 那一份。内置资产中每个变量与计数器都只按单一维度分组,这是该方案成立的前提;
 * 出现多维度分组时会在构建期报错而不是产生错误结果。
 */
public final class DimensionPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 一个内联计数器及其在策略条件树中的位置。 */
    public record CounterRef(String strategyName, String path,
                             Map<String, Object> def) implements Serializable {
        public String id() {
            return strategyName + "|" + path;
        }
    }

    private final Map<String, Set<String>> variablesByDimension = new LinkedHashMap<>();
    private final Map<String, List<CounterRef>> countersByDimension = new LinkedHashMap<>();
    private final Set<String> dimensions = new TreeSet<>();

    public DimensionPlan(List<Map<String, Object>> strategies, List<VariableDef> variables) {
        Map<String, VariableDef> byName = new LinkedHashMap<>();
        for (VariableDef v : variables) {
            byName.put(v.name(), v);
        }

        for (Map<String, Object> st : strategies) {
            String name = String.valueOf(st.get("name"));
            scan(st.get("condition"), name, "c", byName);
            Object delay = st.get("delay");
            if (delay instanceof Map<?, ?> d) {
                scan(d.get("condition"), name, "d", byName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scan(Object node, String strategyName, String path,
                      Map<String, VariableDef> byName) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> cond = (Map<String, Object>) m;
            Object op = cond.get("op");
            if ("and".equals(op) || "or".equals(op) || "not".equals(op)) {
                List<Object> subs = (List<Object>) cond.get("conditions");
                if (subs != null) {
                    for (int i = 0; i < subs.size(); i++) {
                        scan(subs.get(i), strategyName, path + "." + i, byName);
                    }
                }
                return;
            }
            Object left = cond.get("left");
            if (left instanceof Map<?, ?> lm) {
                register((Map<String, Object>) lm, strategyName, path, byName);
            }
            Object right = cond.get("right");
            if (right instanceof Map<?, ?> rm) {
                register((Map<String, Object>) rm, strategyName, path, byName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void register(Map<String, Object> operand, String strategyName, String path,
                          Map<String, VariableDef> byName) {
        String kind = String.valueOf(operand.get("kind"));
        if ("variable".equals(kind)) {
            String var = String.valueOf(operand.get("variable"));
            VariableDef def = byName.get(var);
            if (def == null) {
                return; // schema 校验会拦住不存在的引用,这里静默跳过
            }
            String dim = dimensionOfVariable(def);
            dimensions.add(dim);
            variablesByDimension.computeIfAbsent(dim, k -> new LinkedHashSet<>()).add(var);
        } else if ("counter".equals(kind)) {
            Map<String, Object> def = (Map<String, Object>) operand.get("counter");
            String dim = Counters.dimensionOf(def);
            dimensions.add(dim);
            countersByDimension.computeIfAbsent(dim, k -> new ArrayList<>())
                    .add(new CounterRef(strategyName, path, def));
        }
    }

    /**
     * 变量所属的维度字段。
     *
     * <p>取 groupbykeys 的唯一元素。多维度变量在并行模式下无法归属到单一分区,
     * 报错而不是猜 —— 内置资产中不存在这种变量,若将来出现需要单独设计。
     */
    private static String dimensionOfVariable(VariableDef def) {
        List<String> keys = def.groupByKeys();
        if (keys.size() != 1) {
            throw new IllegalArgumentException(
                    "并行模式暂不支持非单一维度的变量: " + def.name() + ",分组键 " + keys);
        }
        return keys.get(0);
    }

    /** 全部涉及的维度字段,排序后返回,保证各并行实例看到一致的顺序。 */
    public Set<String> dimensions() {
        return dimensions;
    }

    public Set<String> variablesOf(String dimension) {
        return variablesByDimension.getOrDefault(dimension, Set.of());
    }

    public List<CounterRef> countersOf(String dimension) {
        return countersByDimension.getOrDefault(dimension, List.of());
    }

    /**
     * 一条事件需要等待多少个维度的结果。
     *
     * <p>只统计该事件<b>实际携带了值</b>的维度 —— 一条没有 uid 的匿名访问事件不该
     * 等待账号维度的结果,否则永远等不齐。
     *
     * <p>1.x 用同样的思路解决这个问题(它叫「信号计数」),这是那套设计里少数
     * 值得原样继承的部分。
     */
    public int expectedSignals(Map<String, Object> event) {
        int n = 0;
        for (String dim : dimensions) {
            Object v = event.get(dim);
            if (v != null && !String.valueOf(v).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 该事件应参与哪些维度的计算。 */
    public List<String> activeDimensions(Map<String, Object> event) {
        List<String> out = new ArrayList<>();
        for (String dim : dimensions) {
            Object v = event.get(dim);
            if (v != null && !String.valueOf(v).isEmpty()) {
                out.add(dim);
            }
        }
        return out;
    }
}
