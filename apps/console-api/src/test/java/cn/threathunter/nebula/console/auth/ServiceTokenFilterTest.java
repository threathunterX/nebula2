package cn.threathunter.nebula.console.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源网段判定。
 *
 * <p>这段逻辑值得单独测:它是「令牌泄露后能否被任意来源使用」的唯一防线,而
 * 它的错误方向是不对称的 —— 判宽了会静默放行攻击者,判严了只会让合法调用报错
 * 并立刻被发现。所以边界一律测「不该匹配的确实不匹配」。
 */
class ServiceTokenFilterTest {

    @Test
    @DisplayName("空网段列表表示不限制来源")
    void emptyMeansUnrestricted() {
        assertTrue(ServiceTokenFilter.sourceAllowed(List.of(), "203.0.113.9"));
        assertTrue(ServiceTokenFilter.sourceAllowed(null, "203.0.113.9"));
    }

    @Test
    @DisplayName("网段内放行,网段外拒绝")
    void cidrBoundaries() {
        assertTrue(ServiceTokenFilter.matches("10.0.0.0/8", "10.255.255.255"));
        assertTrue(ServiceTokenFilter.matches("10.0.0.0/8", "10.0.0.0"));
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/8", "192.0.2.1"));
    }

    /**
     * 掩码算错最容易表现为「网段边界外一个地址也被放行」。全部用 RFC 5737 的
     * 文档段 192.0.2.0/24 做相邻性测试 —— 既能验证到相邻地址,又不必在仓库里
     * 写任何真实公网地址。
     */
    @Test
    @DisplayName("网段上下边界各差一个地址都要判对")
    void offByOneAtBoundaries() {
        // 192.0.2.0/25 覆盖 .0 - .127
        assertTrue(ServiceTokenFilter.matches("192.0.2.0/25", "192.0.2.0"));
        assertTrue(ServiceTokenFilter.matches("192.0.2.0/25", "192.0.2.127"));
        assertFalse(ServiceTokenFilter.matches("192.0.2.0/25", "192.0.2.128"));
        // 192.0.2.128/25 覆盖 .128 - .255,下边界外一个是 .127
        assertFalse(ServiceTokenFilter.matches("192.0.2.128/25", "192.0.2.127"));
        assertTrue(ServiceTokenFilter.matches("192.0.2.128/25", "192.0.2.128"));
        assertTrue(ServiceTokenFilter.matches("192.0.2.128/25", "192.0.2.255"));
    }

    @Test
    @DisplayName("/32 只匹配单个地址")
    void singleHost() {
        assertTrue(ServiceTokenFilter.matches("192.0.2.7/32", "192.0.2.7"));
        assertFalse(ServiceTokenFilter.matches("192.0.2.7/32", "192.0.2.8"));
    }

    @Test
    @DisplayName("/0 匹配全部 —— 写了就等于没限制,但不能因此崩溃")
    void zeroPrefix() {
        assertTrue(ServiceTokenFilter.matches("0.0.0.0/0", "198.51.100.1"));
        assertTrue(ServiceTokenFilter.matches("0.0.0.0/0", "203.0.113.1"));
    }

    @Test
    @DisplayName("畸形写法一律判为不匹配,不做宽松解释")
    void malformedIsDenied() {
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/33", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/-1", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/abc", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("10.0.0/8", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("10.0.0.256/8", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("", "10.0.0.1"));
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/8", "not-an-ip"));
        // IPv6 来源遇上 IPv4 网段:不匹配,而不是异常或误放行
        assertFalse(ServiceTokenFilter.matches("10.0.0.0/8", "::1"));
    }

    @Test
    @DisplayName("多网段任一命中即放行")
    void anyOfSeveral() {
        List<String> cidrs = List.of("10.1.0.0/16", "192.0.2.0/24");
        assertTrue(ServiceTokenFilter.sourceAllowed(cidrs, "192.0.2.44"));
        assertFalse(ServiceTokenFilter.sourceAllowed(cidrs, "10.2.0.1"));
    }
}
