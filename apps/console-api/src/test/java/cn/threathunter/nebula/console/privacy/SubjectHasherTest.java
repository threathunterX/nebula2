package cn.threathunter.nebula.console.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 控制面算出的哈希必须与引擎写库时用的完全一致。
 *
 * <h2>为什么用固定向量而不是「两边互相比对」</h2>
 *
 * 引擎的 {@code PiiHmac} 与这里的 {@code SubjectHasher} 在<b>两个不打包在一起的
 * 模块</b>里,测试代码互相引用不到。固定向量是把它们钉在一起的办法:两边各自断言
 * 同一组 (密钥, 输入) -> 同一个输出,任何一侧改了算法都会红。
 *
 * <p>这条约束不是形式主义 —— 两边不一致时<b>不会报错</b>,表现是导出返回空、
 * 删除删不掉,而调用方会把「查不到」理解成「这个人本来就没数据」。
 *
 * <p>同一组向量在引擎侧的断言见 {@code PiiHmacTest#matchesConsoleGoldenVector}。
 */
class SubjectHasherTest {

    private static final String KEY = "nebula-golden-key";
    private static final String GOLDEN_ALICE = "74e7cd01745295c4d3174b0d00f1bea8d64e411d55d70051caf8e86a64632094";

    private static SubjectHasher hasher(String key, String columns) {
        return new SubjectHasher(key, columns);
    }

    @Test
    void 与引擎共用的金标准向量() {
        assertEquals(GOLDEN_ALICE, hasher(KEY, "uid,did,sid").storedValue("uid", "alice"));
    }

    @Test
    void 只对配置的列生效() {
        SubjectHasher h = hasher(KEY, "uid");
        assertEquals(GOLDEN_ALICE, h.storedValue("uid", "alice"));
        // did 不在保护列里,按原值查 —— 否则会拿一个哈希去匹配明文,永远查不到
        assertEquals("alice", h.storedValue("did", "alice"));
    }

    @Test
    void 未配置密钥时退回原值() {
        SubjectHasher h = hasher("", "uid,did,sid");
        assertTrue(!h.keyConfigured());
        // 引擎没开保护时库里就是明文,控制面也必须按明文查
        assertEquals("alice", h.storedValue("uid", "alice"));
    }

    @Test
    void 换密钥算出不同的值() {
        assertNotEquals(hasher(KEY, "uid").storedValue("uid", "alice"),
                hasher("another-key", "uid").storedValue("uid", "alice"));
    }

    @Test
    void 空值与null原样返回() {
        SubjectHasher h = hasher(KEY, "uid");
        assertEquals("", h.storedValue("uid", ""));
        assertEquals(null, h.storedValue("uid", null));
    }

    @Test
    void 列名两边的空白被忽略() {
        assertEquals(GOLDEN_ALICE, hasher(KEY, " uid , did ").storedValue("uid", "alice"));
    }

    /**
     * 注解里写死的默认列必须与 {@link SubjectHasher#DEFAULT_COLUMNS} 一致,
     * 后者又必须与引擎的 {@code PiiHmac.DEFAULT_COLUMNS} 一致。
     *
     * <p>缺陷注入时发现:把默认列改成 {@code uid,sid} 之后所有测试照样通过 ——
     * 因为每个测试都显式传了列。默认值恰恰是部署时最常用的那条路径。
     */
    @Test
    void 注解默认值与常量一致() throws Exception {
        var ctor = SubjectHasher.class.getDeclaredConstructors()[0];
        var annotations = ctor.getParameterAnnotations()[1];
        String spel = null;
        for (var a : annotations) {
            if (a instanceof org.springframework.beans.factory.annotation.Value v) {
                spel = v.value();
            }
        }
        assertEquals("${nebula.pii.hmac-columns:${NEBULA_PII_HMAC_COLUMNS:"
                + SubjectHasher.DEFAULT_COLUMNS + "}}", spel);
    }

    /** 默认列本身:与引擎 PiiHmac.DEFAULT_COLUMNS 钉在一起。 */
    @Test
    void 默认列与引擎一致() {
        assertEquals("uid,did,sid", SubjectHasher.DEFAULT_COLUMNS);
    }

    @Test
    void 按默认列构造时did受保护() {
        SubjectHasher h = hasher(KEY, SubjectHasher.DEFAULT_COLUMNS);
        assertNotEquals("d-1", h.storedValue("did", "d-1"));
        assertNotEquals("s-1", h.storedValue("sid", "s-1"));
        // order_id 不在默认列里 —— 引擎也不哈希它,所以按明文查
        assertEquals("o-1", h.storedValue("order_id", "o-1"));
    }
}
