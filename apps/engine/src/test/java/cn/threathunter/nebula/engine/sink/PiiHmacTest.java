package cn.threathunter.nebula.engine.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件明细的个人标识保护。
 *
 * <p>关键断言是<b>原值不出现在最终写库的那一行里</b>,而不是「函数返回了一个哈希」——
 * 后者可以在字段被漏接的情况下通过,而漏接正是这类改动最容易犯的错。
 */
class PiiHmacTest {

    private static final String KEY = "test-key-not-a-real-secret";
    private static final PiiHmac HMAC = PiiHmac.of(KEY, Set.of("uid", "did", "sid"));

    private static Map<String, Object> sampleEvent() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", "ACCOUNT_LOGIN");
        e.put("timestamp", 1784944800000L);
        e.put("c_ip", "198.51.100.7");
        e.put("uid", "alice@example.com");
        e.put("did", "device-abc-123");
        e.put("sid", "session-xyz-789");
        e.put("page", "/api/login");
        e.put("useragent", "Mozilla/5.0 Chrome/120.0.0.0");
        e.put("result", "F");
        return e;
    }

    @Test
    @DisplayName("受保护列的原值不出现在写库的那一行里")
    void plaintextIsAbsentFromTheRow() {
        String row = ClickHouseRows.event(sampleEvent(), HMAC);
        for (String secret : new String[]{"alice@example.com", "device-abc-123", "session-xyz-789"}) {
            assertFalse(row.contains(secret), "原值仍在行里: " + secret + "\n" + row);
        }
    }

    @Test
    @DisplayName("未配置的列保持原值 —— c_ip 的网段聚合是真实的风控手段,默认不能破坏")
    void unprotectedColumnsStayPlain() {
        String row = ClickHouseRows.event(sampleEvent(), HMAC);
        assertTrue(row.contains("198.51.100.7"), "c_ip 不该被改动");
        assertTrue(row.contains("/api/login"), "page 是 internal,不该被改动");
    }

    @Test
    @DisplayName("同一个值每次得到相同结果 —— 否则按 uid 分组统计会散开")
    void deterministic() {
        assertEquals(HMAC.apply("uid", "alice"), HMAC.apply("uid", "alice"));
    }

    @Test
    @DisplayName("不同值得到不同结果")
    void distinct() {
        assertNotEquals(HMAC.apply("uid", "alice"), HMAC.apply("uid", "bob"));
    }

    @Test
    @DisplayName("换密钥得到不同结果 —— 这正是密钥轮换不可逆的原因")
    void keyMatters() {
        PiiHmac other = PiiHmac.of("another-key", Set.of("uid"));
        assertNotEquals(HMAC.apply("uid", "alice"), other.apply("uid", "alice"));
    }

    @Test
    @DisplayName("空值原样返回,不产生一个可被计数的假标识")
    void emptyStaysEmpty() {
        assertEquals("", HMAC.apply("uid", ""));
        assertEquals(null, HMAC.apply("uid", null));
    }

    @Test
    @DisplayName("关闭时完全不改动")
    void disabledPassesThrough() {
        PiiHmac off = PiiHmac.disabled();
        assertFalse(off.enabled());
        assertEquals("alice", off.apply("uid", "alice"));
        assertTrue(ClickHouseRows.event(sampleEvent(), off).contains("alice@example.com"));
    }

    @Test
    @DisplayName("配置了列却没有密钥时必须失败,不能静默降级为明文")
    void missingKeyFailsLoudly() {
        // 静默降级意味着运维以为数据受保护、实际没有 —— 比明确的失败危险得多
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PiiHmac.of(null, Set.of("uid")));
        assertTrue(e.getMessage().contains("uid"), "错误信息要指出是哪些列: " + e.getMessage());
        assertThrows(IllegalStateException.class, () -> PiiHmac.of("  ", Set.of("uid")));
    }

    @Test
    @DisplayName("HMAC 后仍可做等值比较与分组 —— 这是风控唯一需要的用法")
    void equalityStillWorks() {
        String a1 = HMAC.apply("uid", "alice");
        String a2 = HMAC.apply("uid", "alice");
        String b = HMAC.apply("uid", "bob");
        assertEquals(a1, a2);
        assertNotEquals(a1, b);
    }

    /**
     * 与控制面共用的金标准向量。
     *
     * <p>控制面的 {@code SubjectHasher} 要用同一把密钥反算出库里的值,才能按主体
     * 导出或删除。两者在不同模块,测试引用不到对方,所以各自钉住同一组
     * (密钥, 输入) -> 输出。改动本类的算法会让<b>这一侧</b>先红,提醒去看另一侧。
     *
     * <p>对应断言见 console-api 的 {@code SubjectHasherTest#与引擎共用的金标准向量}。
     */
    @Test
    void matchesConsoleGoldenVector() {
        PiiHmac h = PiiHmac.of("nebula-golden-key", java.util.Set.of("uid"));
        assertEquals("74e7cd01745295c4d3174b0d00f1bea8d64e411d55d70051caf8e86a64632094", h.apply("uid", "alice"));
    }

    /** 默认列与控制面 SubjectHasher.DEFAULT_COLUMNS 钉在一起。 */
    @Test
    void defaultColumnsMatchConsole() {
        assertEquals("uid,did,sid", PiiHmac.DEFAULT_COLUMNS);
    }
}
