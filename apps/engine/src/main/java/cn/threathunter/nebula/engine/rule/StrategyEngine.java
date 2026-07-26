package cn.threathunter.nebula.engine.rule;

import cn.threathunter.nebula.engine.condition.Conditions;
import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import java.util.ArrayDeque;
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

    /**
     * 已挂起、等待延迟到期的判定。
     *
     * <p>延迟策略表达的是<b>缺席</b>:「主体做了 A,但随后 N 秒内没有做 B」。这类模式
     * 用条件树表达不了 —— 条件树只能对当下这条事件求值,而「没有发生」要等一段时间
     * 之后才能确认。
     *
     * <p>语义与参考引擎一致:主条件命中时挂起,到期时再求 {@code delay.condition},
     * <b>那时窗口里已经积累了这段时间的数据</b>,「B 出现过没有」才有答案。
     */
    private final List<Pending> pending = new ArrayList<>();

    /**
     * 序列匹配状态:{@code 策略名|分组键} -> 未完成匹配。
     *
     * <p><b>为什么不用 Flink CEP。</b>路线图原本写的是「引入 flink-cep」,查下来
     * flink-cep 1.20.5 里没有 {@code dynamic} 包也没有 {@code PatternProcessor} ——
     * 模式在<b>构图时</b>编译进作业图。而 2.0 的策略是<b>热更新</b>的:改一条序列
     * 策略就得重启作业,连带丢掉全部已累积的窗口状态。这与 v0.3.0 落地的热更新
     * 直接冲突。
     *
     * <p>另一条理由更根本:参考引擎(JS)必须实现同样的语义供金标准向量对照,
     * 它不可能用 Flink CEP。也就是说无论如何都要手写一份,引入 CEP 只是多一份
     * 需要与手写实现保持一致的东西。
     */
    private final Map<String, List<Partial>> partials = new HashMap<>();

    /** 一个未完成的序列匹配。events 只留时间戳 —— 全量事件会让状态大小失控。 */
    private static final class Partial {
        int stepIndex;
        final long startedAt;
        final List<Long> stepTimes = new ArrayList<>();

        Partial(long startedAt) {
            this.startedAt = startedAt;
            this.stepIndex = 1;
            this.stepTimes.add(startedAt);
        }
    }

    /** 因超出单键上限而被丢弃的未完成匹配数。漏检必须可观测,不静默丢。 */
    private long sequencePartialDropped;

    public long sequencePartialDropped() {
        return sequencePartialDropped;
    }

    /** 一次挂起的判定。 */
    private record Pending(long fireAt, Map<String, Object> strategy,
                           Map<String, Object> event, List<Trace> trace) {
    }
    private final List<Notice> notices = new ArrayList<>();

    /**
     * 告警历史索引,供 CEL 的 {@code checkNotice} 做策略级联判定。
     *
     * <p>只存判定需要的四个字段 —— 存整条告警会让这份索引跟着告警体积一起长。
     * 保留期取全部策略里最大的那个回溯窗口:<b>不设上限就是内存泄漏</b>,一条长期
     * 运行的流会把所有历史告警都留在内存里。没有策略用 checkNotice 时保留期为 0,
     * 索引完全不建。
     */
    private record NoticeRef(long ts, String checkType, String key, String strategy) {
    }

    private final ArrayDeque<NoticeRef> noticeIndex = new ArrayDeque<>();
    private final long noticeRetentionMs;
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
        this.noticeRetentionMs = maxCheckNoticeWindowMs(this.strategies);
    }

    /** 扫出全部 checkNotice 调用里最大的回溯窗口,用于界定历史保留期。 */
    private static long maxCheckNoticeWindowMs(List<Map<String, Object>> strategies) {
        long max = 0;
        for (Map<String, Object> st : strategies) {
            max = Math.max(max, scanCheckNotice(st));
        }
        return max;
    }

    private static final java.util.regex.Pattern CHECK_NOTICE_WINDOW =
            java.util.regex.Pattern.compile("checkNotice\\s*\\([^)]*?,\\s*(\\d+)\\s*\\)");

    private static long scanCheckNotice(Object node) {
        long max = 0;
        if (node instanceof Map<?, ?> m) {
            Object cel = m.get("cel");
            if (cel instanceof String expr) {
                java.util.regex.Matcher mt = CHECK_NOTICE_WINDOW.matcher(expr);
                while (mt.find()) {
                    max = Math.max(max, Long.parseLong(mt.group(1)) * 1000L);
                }
            }
            for (Object v : m.values()) {
                max = Math.max(max, scanCheckNotice(v));
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                max = Math.max(max, scanCheckNotice(v));
            }
        }
        return max;
    }

    /**
     * 供 CEL 求值时查询告警历史。
     *
     * <p>区间是 <b>[fromTs, toTs)</b> —— 起点含,终点<b>不含</b>。终点不含是关键:
     * 当前这条事件正在被处理,同一条事件里先求值的策略可能刚产出一条告警。把它算
     * 进来会让结果依赖<b>策略的求值顺序</b>,而顺序不是契约。
     *
     * <p>换句话说:checkNotice 看到的是「在这条事件之前已经报过的告警」。
     * 语义与参考引擎逐条对齐。
     */
    private int countNotices(String checkType, String key, String strategyName,
                             long fromTs, long toTs) {
        int n = 0;
        for (NoticeRef r : noticeIndex) {
            if (r.ts() >= fromTs && r.ts() < toTs
                    && r.checkType().equals(checkType) && r.key().equals(key)
                    && r.strategy().equals(strategyName)) {
                n++;
            }
        }
        return n;
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
        fireDueDelays(ts, localValues);
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
        fireDueDelays(ts, values);
    }

    @SuppressWarnings("unchecked")
    private void evaluate(Map<String, Object> event, String eventName, long ts,
                          ValueProvider values) {

        for (Map<String, Object> st : strategies) {
            // 序列策略不走条件树 —— 它的判定跨多条事件。trigger.event 对它没有意义:
            // 每一步各自声明要匹配的事件。
            if (st.get("sequence") instanceof Map<?, ?> seq) {
                advanceSequence(st, (Map<String, Object>) seq, event, eventName, ts, values);
                continue;
            }
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
            // 延迟条件里的计数器必须在**每条事件**上累积,不能只在到期时算一次。
            // 内联 counter 的状态是「求值时顺带累加」的,而延迟条件只在到期时求值一次 ——
            // 那样它只能看到到期那一刻的那条事件,窗口内实际发生过什么完全不知道。
            // 这里为副作用求值一次,结果丢弃;真正的判定在 fireDueDelays 里做。
            Object delayDef = st.get("delay");
            if (delayDef instanceof Map<?, ?> dd && dd.get("condition") != null) {
                try {
                    evalCondition((Map<String, Object>) dd.get("condition"), event, ts,
                            String.valueOf(st.get("name")), "d", new ArrayList<>(), values);
                } catch (RuntimeException ignored) {
                    // 累积用的求值失败不该打断主判定;真正求值时会再抛一次
                }
            }

            if (ok) {
                Object delay = st.get("delay");
                if (delay instanceof Map<?, ?> d && d.get("condition") != null) {
                    long seconds = d.get("duration_seconds") instanceof Number n
                            ? n.longValue() : 0L;
                    pending.add(new Pending(ts + seconds * 1000L, st, event, trace));
                    continue;
                }
                emit(st, event, ts, trace);
            }
        }
    }

    // ---------------------------------------------------------------- 多步序列

    /** 分组键。by 为空时全局一组 —— schema 里写明了那通常不是想要的。 */
    @SuppressWarnings("unchecked")
    private static String sequenceKey(Map<String, Object> st, Map<String, Object> seq,
                                      Map<String, Object> event) {
        Object by = seq.get("by");
        if (!(by instanceof List<?> fields) || fields.isEmpty()) {
            return st.get("name") + "|";
        }
        StringBuilder sb = new StringBuilder(String.valueOf(st.get("name"))).append('|');
        for (Object f : fields) {
            Object v = event.get(String.valueOf(f));
            sb.append(v == null ? "" : String.valueOf(v)).append('\u0001');
        }
        return sb.toString();
    }

    /**
     * 用一条事件推进某条序列策略的匹配。
     *
     * <p>语义与参考引擎逐条对齐,见 {@code strategy.schema.json} 的
     * {@code sequence.description} 与 {@code reference-engine/test/sequence.test.js}。
     * 几处容易写错的:
     *
     * <ul>
     *   <li><b>一条事件只推进一个未完成匹配</b>,取进度最靠前的那个。否则一条 B 会同时
     *       推进所有停在 A 的匹配,产出一堆重复告警。</li>
     *   <li><b>每一步严格晚于前一步</b>。同一毫秒的两条事件不构成先后。</li>
     *   <li><b>超窗的匹配由事件时间清理</b>。流里长时间没有新事件时不会被清 ——
     *       与延迟判定同一个取舍:回放一致性优先于及时性。</li>
     *   <li><b>匹配完成后移出队列</b>,构成它的事件不再参与后续匹配。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void advanceSequence(Map<String, Object> st, Map<String, Object> seq,
                                 Map<String, Object> event, String eventName, long ts,
                                 ValueProvider values) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) seq.get("steps");
        if (steps == null || steps.size() < 2) {
            return;
        }
        long windowMs = seq.get("within_seconds") instanceof Number n ? n.longValue() * 1000L : 0L;
        int cap = seq.get("max_partial_per_key") instanceof Number n ? n.intValue() : 16;
        String key = sequenceKey(st, seq, event);

        List<Partial> list = partials.computeIfAbsent(key, k -> new ArrayList<>());
        list.removeIf(p -> ts - p.startedAt > windowMs);

        // 推进进度最靠前的那个
        List<Partial> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(b.stepIndex, a.stepIndex));
        for (Partial p : sorted) {
            if (ts <= p.stepTimes.get(p.stepTimes.size() - 1)) {
                continue;
            }
            if (!stepMatches(steps.get(p.stepIndex), st, event, eventName, ts, values)) {
                continue;
            }
            p.stepIndex++;
            p.stepTimes.add(ts);
            if (p.stepIndex >= steps.size()) {
                list.remove(p);
                List<Trace> trace = new ArrayList<>(steps.size());
                for (int i = 0; i < steps.size(); i++) {
                    trace.add(new Trace("第 " + (i + 1) + " 步 " + steps.get(i).get("event"),
                            p.stepTimes.get(i), "happened_at", seq.get("within_seconds")));
                }
                emit(st, event, ts, trace);
            }
            break;
        }

        // 起新匹配:第一步命中就开一个,即便刚推进过别的匹配 ——
        // A A B 里第二个 A 也应当能作为新序列的起点
        if (stepMatches(steps.get(0), st, event, eventName, ts, values)) {
            List<Partial> cur = partials.computeIfAbsent(key, k -> new ArrayList<>());
            if (cur.size() >= cap) {
                sequencePartialDropped++;
                cur.remove(0);
            }
            cur.add(new Partial(ts));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean stepMatches(Map<String, Object> step, Map<String, Object> st,
                                Map<String, Object> event, String eventName, long ts,
                                ValueProvider values) {
        String want = String.valueOf(step.get("event"));
        if (!eventName.equals(want)
                && !(eventModel != null && eventModel.isA(eventName, want))) {
            return false;
        }
        Object cond = step.get("condition");
        if (cond == null) {
            return true;
        }
        try {
            return evalCondition((Map<String, Object>) cond, event, ts,
                    String.valueOf(st.get("name")), "s", new ArrayList<>(), values);
        } catch (RuntimeException e) {
            // 步骤条件求值失败按不匹配处理:一条策略的写法问题不该让整个流停下来。
            // 与主条件不同 —— 主条件失败会抛,因为那是策略的核心判定。
            return false;
        }
    }

    /**
     * 触发已到期的延迟判定。
     *
     * <p>由事件时间驱动,不是挂钟时间 —— 回放历史数据时结果必须与实时处理一致,
     * 否则同一批事件在两种模式下会产出不同的告警。代价是:流里长时间没有新事件时,
     * 已到期的延迟不会被触发。这是事件时间语义的固有取舍,不是缺陷。
     */
    @SuppressWarnings("unchecked")
    private void fireDueDelays(long now, ValueProvider values) {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> due = new ArrayList<>();
        pending.removeIf(p -> {
            if (p.fireAt() <= now) {
                due.add(p);
                return true;
            }
            return false;
        });
        for (Pending p : due) {
            Map<String, Object> delay = (Map<String, Object>) p.strategy().get("delay");
            Map<String, Object> cond = (Map<String, Object>) delay.get("condition");
            List<Trace> trace = new ArrayList<>(p.trace());
            boolean ok;
            try {
                ok = evalCondition(cond, p.event(), p.fireAt(),
                        String.valueOf(p.strategy().get("name")), "d", trace, values);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "策略「" + p.strategy().get("name") + "」的延迟条件求值失败: "
                                + e.getMessage(), e);
            }
            if (ok) {
                // 告警时间用到期时刻而不是原事件时刻 —— 判定是在那一刻成立的
                emit(p.strategy(), p.event(), p.fireAt(), trace);
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
            return Cel.eval(String.valueOf(cond.get("cel")), event, this::countNotices);
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

        // 登记到历史索引。放在去重判定之后 —— 数的是**已产出**的告警,被去重压掉的
        // 那些不算(与参考引擎一致,理由见 Cel.call 里 checkNotice 的说明)。
        if (noticeRetentionMs > 0) {
            noticeIndex.addLast(new NoticeRef(ts,
                    String.valueOf(action.get("check_type")), key, name));
            long cutoff = ts - noticeRetentionMs;
            while (!noticeIndex.isEmpty() && noticeIndex.peekFirst().ts() < cutoff) {
                noticeIndex.pollFirst();
            }
        }
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
