package cn.threathunter.nebula.engine.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * checkNotice 的参数校验。
 *
 * <p>跨引擎向量测的是「查得到 / 查不到」,覆盖不到非法入参 —— 缺陷注入验证过:
 * 把「未知 keyType 抛错」改成「静默返回 IP」,8 个向量全部照样通过。
 *
 * <p>静默容错在这里是有害的:一条按 {@code page} 查的策略会永远查不到东西,
 * 表现是「这条级联策略从来不命中」,而没有任何地方会报错。
 */
class CheckNoticeArgsTest {

    private static final Map<String, Object> EVENT =
            Map.of("timestamp", 1_700_000_000_000L, "c_ip", "198.51.100.1");

    private static final Cel.NoticeHistory EMPTY = (t, k, s, f, to) -> 0;

    private static boolean eval(String expr) {
        return Cel.eval(expr, EVENT, EMPTY);
    }

    @Test
    @DisplayName("keyType 接受 check_type 本身")
    void acceptsCheckType() {
        for (String t : new String[] {"IP", "USER", "DeviceID", "OrderID"}) {
            assertEquals(false, eval("checkNotice(\"" + t + "\", c_ip, \"S\", 60) > 0"));
        }
    }

    @Test
    @DisplayName("keyType 接受 1.x 的别名")
    void acceptsLegacyAliases() {
        for (String t : new String[] {"ip", "uid", "did", "order_id"}) {
            assertEquals(false, eval("checkNotice(\"" + t + "\", c_ip, \"S\", 60) > 0"));
        }
    }

    @Test
    @DisplayName("未知 keyType 抛错,不静默当成某个默认值")
    void rejectsUnknownKeyType() {
        // page 是规格初稿里写的取值,但名单模型里没有对应的 check_type ——
        // 按它查永远查不到东西
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> eval("checkNotice(\"page\", c_ip, \"S\", 60) > 0"));
        assertTrue(e.getMessage().contains("page"), "报错要指出是哪个取值不合法");
    }

    @Test
    @DisplayName("withinSeconds 非正数抛错")
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> eval("checkNotice(\"ip\", c_ip, \"S\", 0) > 0"));
        assertThrows(IllegalArgumentException.class,
                () -> eval("checkNotice(\"ip\", c_ip, \"S\", -60) > 0"));
    }

    @Test
    @DisplayName("没有告警历史时抛错,不返回 0")
    void rejectsMissingHistory() {
        // 返回 0 会让一条永远不命中的策略看起来在正常工作
        assertThrows(IllegalStateException.class,
                () -> Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) > 0", EVENT, null));
    }

    @Test
    @DisplayName("数值比较四种算子都支持")
    void numericComparisons() {
        Cel.NoticeHistory three = (t, k, s, f, to) -> 3;
        assertTrue(Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) > 2", EVENT, three));
        assertTrue(Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) >= 3", EVENT, three));
        assertTrue(Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) < 4", EVENT, three));
        assertTrue(Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) <= 3", EVENT, three));
        assertEquals(false, Cel.eval("checkNotice(\"ip\", c_ip, \"S\", 60) > 3", EVENT, three));
    }
}
