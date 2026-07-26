package cn.threathunter.nebula.engine.rule;

/**
 * 策略判定所需的值来源。
 *
 * <p>把「值怎么算出来」与「条件怎么判」分开,是并行化的前提。
 *
 * <p>单并行度时,值由本地的变量图与计数器现算({@code LocalValueProvider});
 * 并行运行时,值在各维度的分区里算好后汇聚过来({@code PrecomputedValueProvider})
 * —— 两种情况下 {@link StrategyEngine} 的判定逻辑完全相同,因此不会出现
 * 「并行版和单机版结果不一致」这类最难排查的问题。
 */
public interface ValueProvider {

    /** 取变量当前值。不存在时返回 null。 */
    Object variable(String name, java.util.Map<String, Object> event, long ts);

    /**
     * 取内联计数器的当前值。
     *
     * @param path 计数器在策略条件树中的位置,形如 {@code 策略名|c.2},用于区分同一
     *             策略内的多个计数器
     */
    Object counter(String strategyName, String path,
                   java.util.Map<String, Object> counterDef,
                   java.util.Map<String, Object> event, long ts);
}
