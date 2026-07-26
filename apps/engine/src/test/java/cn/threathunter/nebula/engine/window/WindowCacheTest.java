package cn.threathunter.nebula.engine.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.threathunter.nebula.engine.operator.EventMeta;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 窗口取值缓存的失效边界。
 *
 * <p>缓存本身很简单:同一时刻的重复读复用上次结果。但它属于「错了不报错」的那类 ——
 * 少一处失效只会让某些情况下读到旧值,而旧值也是个合法的数,没有任何地方会告警。
 * 所以每条失效路径各钉一个用例。
 *
 * <p>加这个缓存的理由是实测:一条事件到达时多条策略各读同一个变量,而每次读都要
 * 裁剪 + 从头重算整个窗口。见 {@code docs/operations/capacity.md}。
 */
class WindowCacheTest {

    private static EventMeta meta(long ts) {
        return new EventMeta(ts, Long.MIN_VALUE, null);
    }

    private static WindowedAggregate sliding(int seconds) {
        return new WindowedAggregate("count",
                Period.parse("last_n_seconds", String.valueOf(seconds)), Map.of());
    }

    @Test
    @DisplayName("同一时刻重复读结果一致")
    void repeatedReadsAgree() {
        WindowedAggregate w = sliding(60);
        w.add("a", meta(1000));
        w.add("b", meta(2000));
        Object first = w.value(2000);
        assertEquals(first, w.value(2000));
        assertEquals(first, w.value(2000));
        assertEquals(2L, ((Number) first).longValue());
    }

    @Test
    @DisplayName("加了新事件之后必须重算 —— 这是最容易漏的一条")
    void addInvalidates() {
        WindowedAggregate w = sliding(60);
        w.add("a", meta(1000));
        assertEquals(1L, ((Number) w.value(2000)).longValue());
        w.add("b", meta(1500));
        // 时刻没变但集合变了。少一处失效的话这里会读到 1
        assertEquals(2L, ((Number) w.value(2000)).longValue());
    }

    @Test
    @DisplayName("时刻推进导致事件过期时必须重算")
    void timeAdvanceInvalidates() {
        WindowedAggregate w = sliding(10);
        w.add("a", meta(1000));
        w.add("b", meta(2000));
        assertEquals(2L, ((Number) w.value(5000)).longValue());
        // 推进到 t=13000:cutoff=3000,两条都过期了
        assertEquals(0L, ((Number) w.value(13000)).longValue());
    }

    @Test
    @DisplayName("被迟到丢弃的写入不改变取值")
    void lateDroppedDoesNotChangeValue() {
        // allowedLateness=0,水位线之前的事件会被丢弃。
        //
        // 失效逻辑放在 add 的最前面是防御性的,**不是在修一个现存缺陷**:丢弃路径
        // 没有改动窗口,缓存本来就还有效。缺陷注入验证过这一点 —— 把失效挪到方法
        // 末尾,这个用例照样通过。放在前面是为了让「以后往 add 里加一条中途 return
        // 的分支」不会悄悄留下一个读到旧值的窟窿。
        WindowedAggregate w = new WindowedAggregate("count",
                Period.parse("last_n_seconds", "60"), Map.of(), 0);
        w.add("a", new EventMeta(5000, 5000, null));
        long before = ((Number) w.value(5000)).longValue();
        w.add("late", new EventMeta(1000, 5000, null));   // 迟到,被丢弃
        assertEquals(before, ((Number) w.value(5000)).longValue(), "丢弃不该改变取值");
        assertEquals(1L, w.lateDropped(), "但它确实被记为丢弃了");
    }

    @Test
    @DisplayName("非滑动窗口同样走缓存,且窗口切换后重算")
    void tumblingAlsoCached() {
        WindowedAggregate w = new WindowedAggregate("count",
                Period.parse("last_n_seconds", "10"), Map.of());
        w.add("a", meta(1000));
        Object first = w.value(1000);
        assertEquals(first, w.value(1000));
        w.add("b", meta(2000));
        assertNotEquals(first, w.value(2000));
    }

    @Test
    @DisplayName("裁剪的副作用要真的发生 —— 缓存不能把过期事件留在窗口里")
    void expiryStillHappens() {
        WindowedAggregate w = sliding(10);
        for (int i = 1; i <= 5; i++) {
            w.add("v" + i, meta(i * 1000L));
        }
        assertEquals(5L, ((Number) w.value(5000)).longValue());
        // 推进到 t=14000:cutoff=4000,只剩 t=5000 那条
        assertEquals(1L, ((Number) w.value(14000)).longValue());
        // 再往前一点:仍然只剩那一条,而且不能因为缓存而漏掉裁剪
        assertEquals(1L, ((Number) w.value(14500)).longValue());
        assertEquals(0L, ((Number) w.value(16000)).longValue());
    }
}
