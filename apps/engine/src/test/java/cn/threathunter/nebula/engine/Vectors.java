package cn.threathunter.nebula.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 共享测试向量的加载与类型转换。JS 与 Java 读同一批文件。 */
final class Vectors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Vectors() {
    }

    static JsonNode load(String name) {
        // 从 apps/engine 向上两级到仓库根
        Path p = Path.of("..", "..", "tests", "golden", "vectors", name)
                .toAbsolutePath().normalize();
        try {
            return MAPPER.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new IllegalStateException("读取共享测试向量失败: " + p, e);
        }
    }

    /**
     * JsonNode 转 Java 值。整数值统一转为 Long —— JS 只有一种数字类型,
     * {@code 10} 与 {@code 10.0} 不可区分,两侧必须按同一规则归一。
     */
    static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            double d = n.asDouble();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        }
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            n.forEach(x -> out.add(toJava(x)));
            return out;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), toJava(e.getValue())));
            return out;
        }
        return n.asText();
    }
}
