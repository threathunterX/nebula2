package cn.threathunter.nebula.engine.rule;

import cn.threathunter.nebula.engine.condition.Conditions;
import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.operator.EventMeta;
import cn.threathunter.nebula.engine.window.Period;
import cn.threathunter.nebula.engine.window.WindowedAggregate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略引擎:在事件流上求值策略条件,命中后产出风险告警。
 *
 * <p>消费 {@code seeds/strategies/} 下经过 schema 校验的 2.0 结构策略。与
 * {@code packages/reference-engine} 的语义一致,由 {@code tests/golden/vectors/}
 * 下的共享场景守住。
 *
 * <p>本类不依赖 Flink。Flink 的 KeyedProcessFunction 封装在上层,只做状态管理
 * 与调度,不重复实现判定语义。
 */
public final class StrategyEngine {

    /** 风险告警。字段与 {@code packages/domain-schema/notice.schema.json} 对应。 */
    public record Notice(
            long timestamp,
            String key,
            String checkType,
            String strategyName,
            String sceneName,
            String decision,
            int riskScore,
            long expire,
            String remark,
            List<String> tags,
            boolean test,
            Map<String, Object> variableValues) {
    }

    /** 条件求值过程中记录的一条依据,用于告警可解释性。 */
    private record Trace(String subject, Object value, String op, Object threshold) {
    }

    private final List<Map<String, Object>> strategies = new ArrayList<>();
    private final VariableGraph graph;
    private final EventModel eventModel;
    private final Map<String, WindowedAggregate> counters = new HashMap<>();
    private final Map<String, Long> dedup = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private long watermark = Long.MIN_VALUE;

    public long evaluated;
    public long hits;
    public long deduped;

    public StrategyEngine(List<Map<String, Object>> strategies,
                          VariableGraph graph,
                          EventModel eventModel) {
        for (Map<String, Object> s : strategies) {
            String status = String.valueOf(s.getOrDefault("status", ""));
            // 只有 online 与 test 状态参与计算;test 产出的告警标记 test=true
            if ("online".equals(status) || "test".equals(status)) {
                this.strategies.add(s);
            }
        }
        this.graph = graph;
        this.eventModel = eventModel;
    }

    public List<Notice> notices() {
        return List.copyOf(notices);
    }

    // ---------------------------------------------------------------- 主流程

    @SuppressWarnings("unchecked")
    public void process(Map<String, Object> event, String eventName, long ts) {
        watermark = Math.max(watermark, ts);
        if (graph != null) {
            graph.process(event, eventName, ts); // 变量图先于策略求值
        }

        for (Map<String, Object> st : strategies) {
            Map<String, Object> trigger = (Map<String, Object>) st.get("trigger");
            if (trigger != null && trigger.get("event") != null) {
                String want = String.valueOf(trigger.get("event"));
                if (!eventName.equals(want)
                        && !(eventModel != null && eventModel.isA(eventName, want))) {
                    continue;
                }
            }
            evaluated++;
            List<Trace> trace = new ArrayList<>();
            boolean ok;
            try {
                ok = evalCondition((Map<String, Object>) st.get("condition"),
                        event, ts, String.valueOf(st.get("name")), "c", trace);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "策略「" + st.get("name") + "」求值失败: " + e.getMessage(), e);
            }
            if (ok) {
                emit(st, event, ts, trace);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evalCondition(Map<String, Object> cond, Map<String, Object> event,
                                  long ts, String strategyName, String path,
                                  List<Trace> trace) {
        if (cond == null) {
            return true;
        }
        Object op = cond.get("op");
        if ("and".equals(op) || "or".equals(op) || "not".equals(op)) {
            List<Map<String, Object>> subs = (List<Map<String, Object>>) cond.get("conditions");
            if (subs == null) {
                return true;
            }
            if ("and".equals(op)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (!evalCondition(subs.get(i), event, ts, strategyName, path + "." + i, trace)) {
                        return false; // 短路
                    }
                }
                return true;
            }
            if ("or".equals(op)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (evalCondition(subs.get(i), event, ts, strategyName, path + "." + i, trace)) {
                        return true; // 短路
                    }
                }
                return false;
            }
            return !subs.isEmpty()
                    && !evalCondition(subs.get(0), event, ts, strategyName, path + ".0", trace);
        }

        if (cond.get("cel") != null) {
            return Cel.eval(String.valueOf(cond.get("cel")), event);
        }

        Map<String, Object> left = (Map<String, Object>) cond.get("left");
        Map<String, Object> right = (Map<String, Object>) cond.get("right");
        Object lv = resolve(left, event, ts, strategyName, path);
        Object rv = right == null ? null : resolve(right, event, ts, strategyName, path);
        boolean result = Conditions.eval(String.valueOf(op), lv, rv);

        if (left != null && !"constant".equals(left.get("kind"))) {
            trace.add(new Trace(describe(left), lv, String.valueOf(op), rv));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Map<String, Object> operand, Map<String, Object> event,
                           long ts, String strategyName, String path) {
        if (operand == null) {
            return null;
        }
        String kind = String.valueOf(operand.get("kind"));
        return switch (kind) {
            case "constant" -> operand.get("value");
            case "event_field" -> event.get(String.valueOf(operand.get("field")));
            case "variable" -> graph == null
                    ? null
                    : graph.valueOf(String.valueOf(operand.get("variable")), event, ts);
            case "counter" -> counter((Map<String, Object>) operand.get("counter"),
                    event, ts, strategyName, path);
            default -> throw new IllegalArgumentException("未知的操作数类型: " + kind);
        };
    }

    /** 内联计数器:策略内现场定义的窗口统计,等价于临时变量。 */
    @SuppressWarnings("unchecked")
    private Object counter(Map<String, Object> c, Map<String, Object> event,
                           long ts, String strategyName, String path) {
        List<String> groupby = (List<String>) c.getOrDefault("groupby", List.of());
        StringBuilder id = new StringBuilder(strategyName).append('|').append(path).append('|');
        for (String g : groupby) {
            Object v = event.get(g);
            id.append(v == null ? "" : v);
        }
        WindowedAggregate agg = counters.computeIfAbsent(id.toString(), k ->
                new WindowedAggregate(String.valueOf(c.get("algorithm")),
                        Period.parse("last_n_seconds", String.valueOf(c.get("window"))),
                        Map.of()));

        Map<String, Object> filter = (Map<String, Object>) c.get("filter");
        if (evalCounterFilter(filter, event)) {
            List<String> operand = (List<String>) c.getOrDefault("operand", List.of());
            Object v = operand.isEmpty() ? Long.valueOf(1) : event.get(operand.get(0));
            agg.add(v, new EventMeta(ts, watermark, null));
        }
        return agg.value(ts);
    }

    @SuppressWarnings("unchecked")
    private boolean evalCounterFilter(Map<String, Object> f, Map<String, Object> event) {
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
            case "and" -> subs.stream().allMatch(s -> evalCounterFilter(s, event));
            case "or" -> subs.stream().anyMatch(s -> evalCounterFilter(s, event));
            case "not" -> !subs.isEmpty() && !evalCounterFilter(subs.get(0), event);
            default -> throw new IllegalArgumentException("未知的条件组合类型: " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private void emit(Map<String, Object> st, Map<String, Object> event, long ts,
                      List<Trace> trace) {
        Map<String, Object> action = (Map<String, Object>) st.get("action");
        if (action == null) {
            return;
        }
        Object keyObj = event.get(String.valueOf(action.get("check_value")));
        if (keyObj == null || String.valueOf(keyObj).isEmpty()) {
            return; // 无主体则不产出
        }
        String key = String.valueOf(keyObj);
        String name = String.valueOf(st.get("name"));

        long dedupWindow = st.get("dedup_window") instanceof Number n
                ? n.longValue() * 1000L : 300_000L;
        String dedupKey = name + "|" + key;
        Long last = dedup.get(dedupKey);
        if (last != null && ts - last < dedupWindow) {
            deduped++;
            return;
        }
        dedup.put(dedupKey, ts);
        hits++;

        long ttl = action.get("ttl") instanceof Number n ? n.longValue() : 300L;
        Map<String, Object> vv = new LinkedHashMap<>();
        if (!Boolean.FALSE.equals(st.get("explain"))) {
            for (Trace t : trace) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("value", t.value());
                row.put("operator", t.op());
                row.put("threshold", t.threshold());
                vv.put(t.subject(), row);
            }
        }

        notices.add(new Notice(
                ts, key,
                String.valueOf(action.get("check_type")),
                name,
                String.valueOf(st.get("category")),
                String.valueOf(action.get("decision")),
                st.get("score") instanceof Number n ? n.intValue() : 0,
                ts + ttl * 1000L,
                String.valueOf(st.getOrDefault("remark", "")),
                (List<String>) st.getOrDefault("tags", List.of()),
                "test".equals(String.valueOf(st.get("status"))),
                vv));
    }

    @SuppressWarnings("unchecked")
    private static String describe(Map<String, Object> operand) {
        String kind = String.valueOf(operand.get("kind"));
        return switch (kind) {
            case "event_field" -> String.valueOf(operand.get("field"));
            case "variable" -> String.valueOf(operand.get("variable"));
            case "counter" -> {
                Map<String, Object> c = (Map<String, Object>) operand.get("counter");
                List<String> ops = (List<String>) c.getOrDefault("operand", List.of());
                List<String> grp = (List<String>) c.getOrDefault("groupby", List.of());
                yield c.get("algorithm") + "(" + (ops.isEmpty() ? "*" : String.join(",", ops))
                        + ") by " + String.join(",", grp) + " in " + c.get("window") + "s";
            }
            default -> kind;
        };
    }
}
