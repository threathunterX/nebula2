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

    public EventModel(List<Map<String, Object>> defs) {
        for (Map<String, Object> d : defs) {
            Object n = d.get("name");
            if (n != null) {
                models.put(String.valueOf(n), d);
            }
        }
    }

    /** 事件自身 + 全部祖先,由近及远。根事件的 source 指向自身,到此为止。 */
    @SuppressWarnings("unchecked")
    public List<String> chainOf(String name) {
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
        return chainOf(name).contains(target);
    }

    public Set<String> names() {
        return models.keySet();
    }
}
