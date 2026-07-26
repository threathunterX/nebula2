package cn.threathunter.nebula.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件继承链的契约。
 *
 * <p>此前 {@code EventModel} 没有任何直接测试 —— 它一直是被 VariableGraph 与
 * StrategyEngine 间接覆盖的。间接覆盖漏掉了一处:两边调用 {@code isA} 之前都先做了
 * {@code equals} 判断,于是「链里含不含自身」这个契约从来没被验证过。把继承链改成
 * 只存祖先,全部 170 个引擎用例照样绿。
 *
 * <p>这类漏洞在把 {@code chainOf} 改成构造时预计算之后更值得钉住 —— 预计算写错一个
 * 边界,影响的是所有事件的所有匹配。
 */
class EventModelTest {

    /** A ← B ← C 的三层链,外加一个无关的 D。 */
    private static EventModel model() {
        return new EventModel(List.of(
                Map.of("name", "A", "source", List.of(Map.of("name", "A"))),
                Map.of("name", "B", "source", List.of(Map.of("name", "A"))),
                Map.of("name", "C", "source", List.of(Map.of("name", "B"))),
                Map.of("name", "D", "source", List.of(Map.of("name", "D")))));
    }

    @Test
    @DisplayName("链含自身,由近及远")
    void chainIncludesSelf() {
        assertEquals(List.of("C", "B", "A"), model().chainOf("C"));
        assertEquals(List.of("A"), model().chainOf("A"), "根事件的 source 指向自身,到此为止");
    }

    @Test
    @DisplayName("isA 对自身成立 —— 调用方都先判了 equals,这条一直没被验证过")
    void isASelf() {
        EventModel m = model();
        for (String n : List.of("A", "B", "C", "D")) {
            assertTrue(m.isA(n, n), n + " 应当 isA 自身");
        }
    }

    @Test
    @DisplayName("isA 沿链向上成立,反向不成立")
    void isAWalksUpOnly() {
        EventModel m = model();
        assertTrue(m.isA("C", "B"));
        assertTrue(m.isA("C", "A"), "跨两层也要成立");
        assertFalse(m.isA("A", "C"), "父不是子");
        assertFalse(m.isA("C", "D"), "无关事件");
    }

    @Test
    @DisplayName("模型里没有的事件名:只含它自己,不抛也不返回空")
    void unknownEventName() {
        EventModel m = model();
        assertEquals(List.of("ZZZ"), m.chainOf("ZZZ"));
        assertTrue(m.isA("ZZZ", "ZZZ"));
        assertFalse(m.isA("ZZZ", "A"));
    }

    @Test
    @DisplayName("自引用的环不会死循环")
    void cyclicSourceTerminates() {
        // 1.x 的存量数据里出现过互指的 source。构造时预计算意味着一个环会在**启动时**
        // 挂死,而不是运行到某条事件时才挂 —— 更要确保这里终止。
        EventModel m = new EventModel(List.of(
                Map.of("name", "X", "source", List.of(Map.of("name", "Y"))),
                Map.of("name", "Y", "source", List.of(Map.of("name", "X")))));
        assertEquals(List.of("X", "Y"), m.chainOf("X"));
        assertEquals(List.of("Y", "X"), m.chainOf("Y"));
    }

    @Test
    @DisplayName("重复取值结果一致 —— 预计算的链是不可变的")
    void chainIsStable() {
        EventModel m = model();
        List<String> first = m.chainOf("C");
        assertEquals(first, m.chainOf("C"));
    }
}
