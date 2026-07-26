package cn.threathunter.nebula.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件模型与单继承链。
 *
 * <p>事件以 HTTP_DYNAMIC 为根做单继承:ACCOUNT_LOGIN 的父事件是 HTTP_DYNAMIC,
 * 因此一条登录事件同时也是一条动态请求事件。<b>凡是按事件名匹配的地方(变量的
 * source、策略的 trigger)都必须考虑整条继承链</b>,否则定义在父事件上的变量与
 * 策略永远不会被触发。
 *
 * <p>这一条是在编写参考实现时发现规格未写明的 —— 见 operators.md 的相关说明。
 */
public final class EventModel {

    private final Map<String, Map<String, Object>> models = new HashMap<>();

    /**
     * 继承链缓存。
     *
     * <p><b>这是热路径上最贵的一处。</b>{@code isA} 被每条事件调用数百次(每条策略的
     * trigger 匹配一次,变量图的每个节点再匹配一次),而原实现每次都新建一个
     * {@code ArrayList} 加一个 {@code HashSet} 走一遍继承链,再做线性 {@code contains}。
     * 10 万条事件 × 170 条策略就是上千万次这样的分配。
     *
     * <p>模型在构造后不再变化,链路可以一次算完。这里存 {@code Set} 而不是
     * {@code List}:{@code isA} 要的是包含判断,而 {@code chainOf} 的顺序语义
     * (由近及远)另有调用方依赖,所以两份都留。
     */
    private final Map<String, List<String>> chains = new HashMap<>();
    private final Map<String, Set<String>> chainSets = new HashMap<>();

    public EventModel(List<Map<String, Object>> defs) {
        for (Map<String, Object> d : defs) {
            Object n = d.get("name");
            if (n != null) {
                models.put(String.valueOf(n), d);
            }
        }
        // 构造时一次算完:模型此后不变,而运行期每次现算是纯粹的浪费
        for (String name : models.keySet()) {
            List<String> chain = computeChain(name);
            chains.put(name, List.copyOf(chain));
            chainSets.put(name, Set.copyOf(chain));
        }
    }

    /**
     * 事件自身 + 全部祖先,由近及远。根事件的 source 指向自身,到此为止。
     *
     * <p>模型里没有的事件名不会被缓存 —— 现算一条只含它自己的链返回,与原行为一致。
     * 这条路径在生产中不该出现(事件名不在模型里说明上游发了未知类型),但静默
     * 返回空会让调用方看到「这条事件不属于任何链」而不是「这个名字是错的」。
     */
    public List<String> chainOf(String name) {
        List<String> cached = chains.get(name);
        return cached != null ? cached : computeChain(name);
    }

    @SuppressWarnings("unchecked")
    private List<String> computeChain(String name) {
        List<String> out = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        String cur = name;
        while (cur != null && guard.add(cur)) {
            out.add(cur);
            Map<String, Object> d = models.get(cur);
            if (d == null) {
                break;
            }
            Object src = d.get("source");
            String parent = null;
            if (src instanceof List<?> l && !l.isEmpty()
                    && l.get(0) instanceof Map<?, ?> m && m.get("name") != null) {
                parent = String.valueOf(m.get("name"));
            }
            if (parent == null || parent.equals(cur)) {
                break;
            }
            cur = parent;
        }
        return out;
    }

    public boolean isA(String name, String target) {
        Set<String> cached = chainSets.get(name);
        return cached != null ? cached.contains(target) : computeChain(name).contains(target);
    }

    public Set<String> names() {
        return models.keySet();
    }
}
