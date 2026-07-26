package cn.threathunter.nebula.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MurmurHash3 的跨语言一致性。
 *
 * <p>HLL 的去重计数结果依赖哈希逐位一致 —— 两种语言的实现哪怕差一位,同一批数据
 * 的基数估计就会不同,{@code tests/golden/} 的对照也就失去意义。因此哈希本身也用
 * 共享向量固定下来。
 */
class HashVectorTest {

    @Test
    @DisplayName("MurmurHash3 与共享向量一致(含 UTF-8 多字节与空串)")
    void matchesSharedVectors() throws Exception {
        Path p = Path.of("..", "..", "tests", "golden", "vectors", "murmur3.json")
                .toAbsolutePath().normalize();
        JsonNode suite;
        try {
            suite = new ObjectMapper().readTree(Files.readString(p));
        } catch (IOException e) {
            throw new IllegalStateException("读取共享哈希向量失败: " + p, e);
        }

        Method hash = Class.forName("cn.threathunter.nebula.engine.operator.HyperLogLog")
                .getDeclaredMethod("hash", String.class);
        hash.setAccessible(true);

        for (JsonNode c : suite.get("vectors")) {
            String in = c.get("input").asText();
            long want = c.get("expect").asLong();
            long got = Integer.toUnsignedLong((int) hash.invoke(null, in));
            String note = c.hasNonNull("note") ? "(" + c.get("note").asText() + ")" : "";
            assertEquals(want, got, "输入 \"" + in + "\"" + note);
        }
    }
}
