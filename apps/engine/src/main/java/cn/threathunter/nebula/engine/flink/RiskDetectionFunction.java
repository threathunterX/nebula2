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
    private transient int emitted;

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
        VariableGraph graph = referenced.isEmpty() ? null : new VariableGraph(vars, referenced, em);
        engine = new StrategyEngine(strategies, graph, em);
        emitted = 0;
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
