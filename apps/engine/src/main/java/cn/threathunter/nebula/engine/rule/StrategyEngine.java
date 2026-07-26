package cn.threathunter.nebula.engine.rule;

import cn.threathunter.nebula.engine.condition.Conditions;
import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableGraph;
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
    private final LocalValueProvider localValues;
    private final Map<String, Long> dedup = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private long watermark = Long.MIN_VALUE;

    public long evaluated;
    public long hits;
    public long deduped;

    /**
     * 是否在本引擎内做告警去重。
     *
     * <p>单并行度时为 true。并行拓扑中必须置为 false —— 去重的分组键是「策略 + 主体」,
     * 而汇聚阶段按事件 ID 分区,同一主体的事件会落到不同实例上,各自持有一份去重
     * 状态、互相看不见,结果是同一主体被重复告警。并行拓扑把去重独立成一个按主体
     * 分区的阶段。
     */
    private boolean dedupEnabled = true;

    public void setDedupEnabled(boolean enabled) {
        this.dedupEnabled = enabled;
    }

    public StrategyEngine(List<Map<String, Object>> strategies,
                          VariableGraph graph,
                          EventModel eventModel) {
        this(strategies, graph, eventModel, null);
    }

    /**
     * 复用已有的 {@link LocalValueProvider} 构造 —— **策略热更新用这个入口**。
     *
     * <p>内置策略大量使用<b>内联 counter</b>(条件里直接写聚合定义,而不是引用具名变量),
     * 它们的窗口状态存在 LocalValueProvider 里,<b>不在 VariableGraph 里</b>。
     * 热更新时只复用变量图是不够的:内联 counter 会随新建的 provider 一起归零,
     * 「IP 5 分钟内登录失败次数」在改完阈值的那一刻变回 0。
     *
     * <p>这一点是被测试抓出来的 —— 只凭「保留变量图」的直觉会漏掉它,而漏掉的后果是
     * 静默的:不报错、不中断,只是告警在那一刻之后少了一批。
     *
     * @param reuse 复用的取值提供者;为 null 时新建
     */
    public StrategyEngine(List<Map<String, Object>> strategies,
                          VariableGraph graph,
                          EventModel eventModel,
                          LocalValueProvider reuse) {
        for (Map<String, Object> s : strategies) {
            String status = String.valueOf(s.getOrDefault("status", ""));
            // 只有 online 与 test 状态参与计算;test 产出的告警标记 test=true
            if ("online".equals(status) || "test".equals(status)) {
                this.strategies.add(s);
            }
        }
        this.graph = graph;
        this.eventModel = eventModel;
        this.localValues = reuse != null ? reuse : new LocalValueProvider(graph);
    }

    /** 供热更新复用,以保住内联 counter 的窗口状态。 */
    public LocalValueProvider valueProvider() {
        return localValues;
    }

    public List<Notice> notices() {
        return List.copyOf(notices);
    }

    // ---------------------------------------------------------------- 主流程

    /** 单并行度:变量图与计数器都在本进程现算。 */
    public void process(Map<String, Object> event, String eventName, long ts) {
        watermark = Math.max(watermark, ts);
        localValues.advanceWatermark(ts);
        if (graph != null) {
            graph.process(event, eventName, ts); // 变量图先于策略求值
        }
        evaluate(event, eventName, ts, localValues);
    }

    /**
     * 并行模式:变量与计数器的值已在各维度分区中算好。
     *
     * <p>判定逻辑与上面完全相同 —— 差别只在值从哪来。
     */
    public void processWith(Map<String, Object> event, String eventName, long ts,
                            ValueProvider values) {
        watermark = Math.max(watermark, ts);
        evaluate(event, eventName, ts, values);
    }

    @SuppressWarnings("unchecked")
    private void evaluate(Map<String, Object> event, String eventName, long ts,
                          ValueProvider values) {

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
                        event, ts, String.valueOf(st.get("name")), "c", trace, values);
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
                                  List<Trace> trace, ValueProvider values) {
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
                    if (!evalCondition(subs.get(i), event, ts, strategyName, path + "." + i, trace, values)) {
                        return false; // 短路
                    }
                }
                return true;
            }
            if ("or".equals(op)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (evalCondition(subs.get(i), event, ts, strategyName, path + "." + i, trace, values)) {
                        return true; // 短路
                    }
                }
                return false;
            }
            return !subs.isEmpty()
                    && !evalCondition(subs.get(0), event, ts, strategyName, path + ".0", trace, values);
        }

        if (cond.get("cel") != null) {
            return Cel.eval(String.valueOf(cond.get("cel")), event);
        }

        Map<String, Object> left = (Map<String, Object>) cond.get("left");
        Map<String, Object> right = (Map<String, Object>) cond.get("right");
        Object lv = resolve(left, event, ts, strategyName, path, values);
        Object rv = right == null ? null : resolve(right, event, ts, strategyName, path, values);
        boolean result = Conditions.eval(String.valueOf(op), lv, rv);

        if (left != null && !"constant".equals(left.get("kind"))) {
            trace.add(new Trace(describe(left), lv, String.valueOf(op), rv));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Map<String, Object> operand, Map<String, Object> event,
                           long ts, String strategyName, String path, ValueProvider values) {
        if (operand == null) {
            return null;
        }
        String kind = String.valueOf(operand.get("kind"));
        return switch (kind) {
            case "constant" -> operand.get("value");
            case "event_field" -> event.get(String.valueOf(operand.get("field")));
            case "variable" -> values.variable(
                    String.valueOf(operand.get("variable")), event, ts);
            case "counter" -> values.counter(strategyName, path,
                    (Map<String, Object>) operand.get("counter"), event, ts);
            default -> throw new IllegalArgumentException("未知的操作数类型: " + kind);
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
        if (dedupEnabled) {
            Long last = dedup.get(dedupKey);
            if (last != null && ts - last < dedupWindow) {
                deduped++;
                return;
            }
            dedup.put(dedupKey, ts);
        }
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
