package cn.threathunter.nebula.engine.operator;

/** 统一处理 null 过滤,子类只需实现 {@link #doAdd}。 */
public abstract class AbstractAccumulator implements Accumulator {

    @Override
    public final void add(Object value, EventMeta meta) {
        if (value == null) {
            return; // 规格「阅读约定」:null 一律跳过
        }
        doAdd(value, meta == null ? EventMeta.EMPTY : meta);
    }

    protected abstract void doAdd(Object value, EventMeta meta);

    /** 把输入转成 double。类型不符时抛出,便于在测试中尽早暴露。 */
    protected static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(
                "算子 " + "需要数值输入,实际收到 " + v.getClass().getSimpleName() + ": " + v);
    }

    /**
     * 数值结果的规范化:整数值以 Long 返回,否则以 Double 返回。
     *
     * <p>这样做是为了与 JS 参考实现的行为对齐 —— JS 只有一种数字类型,
     * {@code 10.0} 与 {@code 10} 不可区分。共享向量里的 {@code "expect": 10}
     * 在两边都应判定相等。
     */
    protected static Object normalizeNumber(double d) {
        if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return (long) d;
        }
        return d;
    }
}
