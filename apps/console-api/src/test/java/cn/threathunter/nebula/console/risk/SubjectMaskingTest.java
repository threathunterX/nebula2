package cn.threathunter.nebula.console.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主体展示脱敏。
 *
 * <p>核心断言不是「输出长什么样」,而是<b>原值不能出现在输出里</b> —— 掩码写错的
 * 典型后果是短值原样透出,而短值恰恰是最敏感的那一类(4 位工号、6 位账号)。
 */
class SubjectMaskingTest {

    @Test
    @DisplayName("IP 保留网段,末段隐去")
    void ipKeepsSubnet() {
        assertEquals("198.51.100.*", SubjectMasking.mask("IP", "198.51.100.10"));
        assertEquals("203.0.113.*", SubjectMasking.mask("ip", "203.0.113.7"));
    }

    @Test
    @DisplayName("非 IPv4 形态的值走通用规则,不按 IP 处理")
    void nonIpv4FallsThrough() {
        String masked = SubjectMasking.mask("IP", "2001:db8::1");
        assertFalse(masked.contains("db8"), "IPv6 不该被当成 IPv4 只截末段");
    }

    @Test
    @DisplayName("手机号只留首尾各三位")
    void phoneKeepsEnds() {
        assertEquals("138*****000", SubjectMasking.mask("USER", "13800138000"));
    }

    @Test
    @DisplayName("短值必须全遮或几乎全遮 —— 短值最容易漏")
    void shortValuesAreCovered() {
        assertEquals("**", SubjectMasking.mask("DeviceID", "d9"));
        assertEquals("*", SubjectMasking.mask("DeviceID", "d"));
        assertEquals("a****f", SubjectMasking.mask("DeviceID", "abcdef"));
        for (String v : new String[]{"a", "ab", "abc", "abcd", "abcde", "abcdef"}) {
            String m = SubjectMasking.mask("DeviceID", v);
            assertEquals(v.length(), m.length(), "掩码不应改变长度:" + v);
            assertFalse(m.equals(v), "原值原样透出了:" + v);
        }
    }

    @Test
    @DisplayName("空值与 null 原样返回,不构造出假数据")
    void emptyIsUntouched() {
        assertEquals(null, SubjectMasking.mask("IP", null));
        assertEquals("", SubjectMasking.mask("IP", ""));
    }
}
