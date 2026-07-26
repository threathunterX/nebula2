package cn.threathunter.nebula.console.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** 主体类型必须来自领域 schema,并覆盖全部 check_type。 */
class SubjectTypesTest {

    private final SubjectTypes types = new SubjectTypes();

    SubjectTypesTest() throws IOException {
    }

    @Test
    void 覆盖schema里的全部check_type() {
        // 少一个就意味着那类名单删不掉
        assertTrue(types.all().containsAll(
                java.util.Set.of("IP", "USER", "DeviceID", "OrderID")),
                "实际: " + types.all());
    }

    @Test
    void 列名取自schema的event_field() {
        assertEquals("c_ip", types.column("IP"));
        assertEquals("uid", types.column("USER"));
        assertEquals("did", types.column("DeviceID"));
        assertEquals("order_id", types.column("OrderID"));
    }

    @Test
    void 隐私文档里的别名仍然可用() {
        assertEquals("USER", types.canonical("uid"));
        assertEquals("DeviceID", types.canonical("did"));
        assertEquals("IP", types.canonical("ip"));
    }

    @Test
    void 规范名直接通过() {
        assertEquals("USER", types.canonical("USER"));
        assertEquals("OrderID", types.canonical("OrderID"));
    }

    @Test
    void 未知类型返回null而不是猜一个() {
        assertNull(types.canonical("email"));
        assertNull(types.canonical(""));
        assertNull(types.canonical(null));
    }
}
