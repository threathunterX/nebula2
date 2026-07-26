package cn.threathunter.nebula.engine.flink;

import cn.threathunter.nebula.engine.graph.EventModel;
import cn.threathunter.nebula.engine.graph.VariableDef;
import cn.threathunter.nebula.engine.graph.VariableGraph;
import cn.threathunter.nebula.engine.rule.StrategyEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 把变量图与策略引擎接入 Flink 的算子。
 *
 * <p><b>Flink 只出现在本包中。</b>算子、条件、窗口、变量图、规则五层都不依赖它,
 * 因此可以脱离集群单元测试,也可以在离线回放等场景复用。本类只做「把 Flink 的
 * 生命周期与数据流接到引擎上」这一件事,不重复实现任何判定语义。
 *
 * <h2>关于并行度</h2>
 *
 * <p>当前实现要求<b>并行度为 1</b>。原因是变量按不同维度分组(ip / uid / did /
 * page),一次 {@code keyBy} 无法同时满足:按 IP 分区后,uid 维度的变量就会被拆到
 * 不同的并行实例上,状态不完整。
 *
 * <p>正确的做法是按维度拆成多条链路(events → keyBy(ip) → IP 维度变量;
 * events → keyBy(uid) → 账号维度变量;……),再汇聚做策略判定。这是下一步的工作,
 * 也是这套架构最实质的工程难点。在此之前,单并行度实现保证了<b>语义正确</b>,
 * 可用于验证链路、小流量场景与回归对照。
 */
public final class RiskDetectionFunction
        extends ProcessFunction<Map<String, Object>, StrategyEngine.Notice> {

    private static final long serialVersionUID = 1L;

    private final List<Map<String, Object>> strategies;
    private final List<Map<String, Object>> variableDefs;
    private final List<Map<String, Object>> eventDefs;

    private transient StrategyEngine engine;
    private transient VariableGraph graph;
    private transient EventModel model;
    private transient int emitted;
    /** 当前生效的元数据版本。仅用于日志与诊断,判定逻辑不依赖它。 */
    private transient long metadataVersion;

    public RiskDetectionFunction(List<Map<String, Object>> strategies,
                                 List<Map<String, Object>> variableDefs,
                                 List<Map<String, Object>> eventDefs) {
        this.strategies = strategies;
        this.variableDefs = variableDefs;
        this.eventDefs = eventDefs;
    }

    @Override
    public void open(Configuration parameters) {
        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : variableDefs) {
            vars.add(new VariableDef(m));
        }
        EventModel em = new EventModel(eventDefs);

        // 只构建策略实际引用到的变量闭包 —— 253 个变量里通常只用到几十个
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> st : strategies) {
            collectVariableRefs(st, referenced);
        }
        this.model = em;
        this.graph = referenced.isEmpty() ? null : new VariableGraph(vars, referenced, em);
        engine = new StrategyEngine(strategies, graph, em);
        emitted = 0;
        metadataVersion = 0;
    }

    /**
     * 热更新:换掉策略,**保留已累积的变量状态**。
     *
     * <p>重建整张变量图会让所有窗口计数归零 —— 「IP 5 分钟内登录失败次数」在改完阈值的
     * 那一刻变回 0,攻击正好在那个窗口里溜过去。所以这里复用同一个 {@link VariableGraph}
     * 实例、只调用 {@link VariableGraph#extendTo} 补上新引用的变量。
     *
     * <p>新引入的变量必然冷启动,日志里会写出来 —— 运维需要知道它们要经过一个完整
     * 窗口期才给出有意义的值。
     *
     * <p>变量定义本身发生结构性变化(比如窗口长度改了)时,已累积的状态在语义上未必
     * 还成立。当前实现保留它 —— 丢弃会让每次改动都付出清空代价,而变量定义的改动远比
     * 策略阈值的改动少见。这一点写在了[路线图](../../../../../../../../docs/development/roadmap.md)里。
     *
     * @return 本次冷启动的变量名
     */
    public Set<String> reload(List<Map<String, Object>> newStrategies,
                              List<Map<String, Object>> newVariableDefs,
                              long version) {
        List<VariableDef> vars = new ArrayList<>();
        for (Map<String, Object> m : newVariableDefs) {
            vars.add(new VariableDef(m));
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> st : newStrategies) {
            collectVariableRefs(st, referenced);
        }

        Set<String> cold;
        if (graph == null) {
            // 此前没有任何变量引用,现在有了 —— 只能新建,全部冷启动
            graph = referenced.isEmpty() ? null : new VariableGraph(vars, referenced, model);
            cold = referenced;
        } else {
            cold = graph.extendTo(vars, referenced);
        }
        // 复用取值提供者:内联 counter 的窗口状态存在它里面,不在变量图里。
        // 新建一个会让所有内联计数归零 —— 这正是热更新最容易出错、且失效最静默的地方。
        engine = new StrategyEngine(newStrategies, graph, model, engine.valueProvider());
        metadataVersion = version;
        return cold;
    }

    public long metadataVersion() {
        return metadataVersion;
    }

    @Override
    public void processElement(Map<String, Object> event,
                               Context ctx,
                               Collector<StrategyEngine.Notice> out) {
        Object nameObj = event.get("name");
        Object tsObj = event.get("timestamp");
        if (nameObj == null || !(tsObj instanceof Number ts)) {
            return; // 缺少事件名或时间戳的记录无法参与计算
        }
        engine.process(event, String.valueOf(nameObj), ts.longValue());

        // 引擎内部累积告警,这里把新增的部分发往下游
        List<StrategyEngine.Notice> all = engine.notices();
        for (int i = emitted; i < all.size(); i++) {
            out.collect(all.get(i));
        }
        emitted = all.size();
    }

    @SuppressWarnings("unchecked")
    private static void collectVariableRefs(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> m) {
            if ("variable".equals(m.get("kind")) && m.get("variable") != null) {
                out.add(String.valueOf(m.get("variable")));
            }
            m.values().forEach(v -> collectVariableRefs(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectVariableRefs(v, out));
        }
    }
}
