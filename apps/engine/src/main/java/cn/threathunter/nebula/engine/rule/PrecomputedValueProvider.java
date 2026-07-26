package cn.threathunter.nebula.engine.rule;

import java.util.Map;

/**
 * 并行模式下的值来源:变量与计数器的值已在各维度分区中算好,汇聚后传入。
 *
 * <p>判定逻辑与单并行度完全相同 —— {@link StrategyEngine} 不知道值是现算的还是
 * 汇聚来的,因此不会出现「并行版与单机版结果不一致」这类最难排查的问题。
 */
public final class PrecomputedValueProvider implements ValueProvider {

    private final Map<String, Object> variables;
    private final Map<String, Object> counters;

    public PrecomputedValueProvider(Map<String, Object> variables, Map<String, Object> counters) {
        this.variables = variables == null ? Map.of() : variables;
        this.counters = counters == null ? Map.of() : counters;
    }

    @Override
    public Object variable(String name, Map<String, Object> event, long ts) {
        return variables.get(name);
    }

    @Override
    public Object counter(String strategyName, String path, Map<String, Object> def,
                          Map<String, Object> event, long ts) {
        return counters.get(strategyName + "|" + path);
    }
}
