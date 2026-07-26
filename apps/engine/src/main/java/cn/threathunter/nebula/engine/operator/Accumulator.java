package cn.threathunter.nebula.engine.operator;

import java.util.Map;

/**
 * 聚合算子的状态机。
 *
 * <p>本接口及其实现是 {@code docs/reference/operators.md} 的 Java 版本。规格与实现
 * 不一致时以规格为准;若发现规格本身有歧义,先修规格再改这里。
 *
 * <p>与 {@code packages/reference-engine}(JS 参考实现)的语义必须<b>完全一致</b>。
 * 两者读同一份共享测试向量({@code tests/golden/vectors/operators.json}),任何一方
 * 改了行为,另一方立刻会失败。
 *
 * <p>算子层刻意不依赖 Flink:它是纯计算,便于单元测试、便于与参考实现逐条对照,
 * 也便于在 Flink 之外复用。Flink 的 AggregateFunction 封装在上层。
 */
public interface Accumulator {

    /**
     * 累加一条输入。
     *
     * <p>规格「阅读约定」:{@code null} 输入一律跳过,不当作 0 或空串。
     * 该过滤由 {@link AbstractAccumulator} 统一处理。
     */
    void add(Object value, EventMeta meta);

    /** 当前窗口内的累计值。空窗口的返回值由各算子按规格单独规定。 */
    Object value();

    /** 算子名,与变量定义中的 {@code function.method} 一致。 */
    String name();

    /** 附加信息,如去重计数是否降级为近似。缺省无。 */
    default Map<String, Object> meta() {
        return Map.of();
    }

    /**
     * 导出算子的内部状态,用于 Flink Checkpoint。
     *
     * <p>返回值必须可序列化,且只含普通数据(数值、字符串、集合)—— 不允许把算子
     * 对象本身塞进去,否则恢复时会依赖类的具体实现,重构即失效。
     */
    java.io.Serializable snapshot();

    /** 从快照恢复内部状态。传入的必须是同一算子 {@link #snapshot()} 的产物。 */
    void restore(java.io.Serializable state);
}
