package cn.threathunter.nebula.engine.operator;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 去重计数。规格 §2.5。
 *
 * <p>默认<b>精确</b>去重;基数超过阈值(默认 100000)自动降级为 HyperLogLog,
 * 并在 {@link #meta()} 中标记 {@code approximate}。可通过配置显式指定
 * {@code distinct_mode = approx} 强制近似。
 *
 * <p>选择精确作为默认值,是因为风控场景中绝大多数去重计数的基数不大(单个 IP
 * 一小时内关联的设备数、单个账号的登录城市数),精确计算的成本可以接受,而精度
 * 对策略阈值的稳定性更重要。
 */
public final class DistinctCountAccumulator extends AbstractAccumulator {

    private final boolean forceApprox;
    private final long threshold;
    private final Set<String> exact = new HashSet<>();
    private HyperLogLog hll;
    private boolean degraded;

    public DistinctCountAccumulator(Map<String, Object> config) {
        Object mode = config == null ? null : config.get("distinct_mode");
        this.forceApprox = "approx".equals(String.valueOf(mode));
        Object t = config == null ? null : config.get("approx_threshold");
        long parsed = 100_000L;
        if (t != null) {
            try {
                parsed = Long.parseLong(String.valueOf(t));
            } catch (NumberFormatException ignored) {
                // 保持默认
            }
        }
        this.threshold = parsed;
    }

    @Override
    protected void doAdd(Object value, EventMeta meta) {
        String k = String.valueOf(value);
        if (hll != null) {
            hll.add(k);
            return;
        }
        exact.add(k);
        if (forceApprox || exact.size() > threshold) {
            hll = new HyperLogLog(14);
            for (String x : exact) {
                hll.add(x);
            }
            exact.clear();
            degraded = !forceApprox;
        }
    }

    @Override
    public Object value() {
        return hll != null ? hll.count() : (long) exact.size();
    }

    @Override
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approximate", hll != null);
        m.put("degraded", degraded);
        return m;
    }

    @Override
    public String name() {
        return "distinct_count";
    }

    /**
     * 快照。
     *
     * <p>降级为 HLL 之后无法还原出原始集合,因此快照里存的是 HLL 的 register 数组
     * —— 这也意味着<b>恢复后的基数估计与降级前完全一致</b>,不会因为重启而改变。
     */
    @Override
    public java.io.Serializable snapshot() {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        out.add(hll != null);
        out.add(degraded);
        out.add(hll != null ? hll.registersCopy() : new java.util.ArrayList<>(exact));
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(java.io.Serializable s) {
        java.util.List<?> l = (java.util.List<?>) s;
        boolean isHll = Boolean.TRUE.equals(l.get(0));
        degraded = Boolean.TRUE.equals(l.get(1));
        exact.clear();
        hll = null;
        if (isHll) {
            hll = HyperLogLog.fromRegisters((java.util.List<Number>) l.get(2));
        } else {
            for (Object x : (java.util.List<Object>) l.get(2)) {
                exact.add(String.valueOf(x));
            }
        }
    }
}
