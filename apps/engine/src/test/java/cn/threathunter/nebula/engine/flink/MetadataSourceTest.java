package cn.threathunter.nebula.engine.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 作业的元数据来源选择。
 *
 * <p>核心断言:<b>给了 --console-url 就绝不回落到本地文件</b>。回落看起来更健壮,
 * 实际是最糟的结果 —— 作业带着一份不知多旧的策略跑起来,而且没有任何迹象表明它
 * 没连上控制面。运营改完策略以为生效了,线上判定还是旧的。宁可起不来。
 */
class MetadataSourceTest {

    @Test
    @DisplayName("不给 console-url 时读本地 seeds")
    void fallsBackToSeedsWhenNoConsole() {
        NebulaJob.Metadata m = NebulaJob.loadMetadata(null, "../../seeds");
        assertEquals(17, m.events().size());
        assertEquals(170, m.strategies().size());
        assertTrue(m.origin().contains("seeds"), m.origin());
    }

    @Test
    @DisplayName("控制面不可达时启动失败,不悄悄用本地文件顶上")
    void neverFallsBackWhenConsoleConfigured() {
        // 端口 9 不监听;seeds 目录就在旁边且完全可用 —— 正是最容易被“兜底”的场景
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NebulaJob.loadMetadata("http://127.0.0.1:9", "../../seeds"));
        assertTrue(e.getMessage().contains("作业不启动"), e.getMessage());
    }
}
