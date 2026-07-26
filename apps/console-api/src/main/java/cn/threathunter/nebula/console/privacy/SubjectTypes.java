package cn.threathunter.nebula.console.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 主体类型 —— 名单的 check_type 与它在事件明细表里对应的列。
 *
 * <p><b>从 {@code domain-schema/enums.json} 读,不在 Java 里再抄一份。</b>
 * 这张对应关系已经存在于领域模型里({@code check_type.values[*].event_field}),
 * 抄一份的后果不是编译错误而是静默漏删:以后新增一个 check_type,名单照常写入,
 * 但主体删除接口不认识它 —— 用户以为数据删了,实际上名单里还留着。
 *
 * <p>「同一个领域概念在两处各写一份、逐渐漂移」正是 1.x 最大的结构性问题,
 * 这里不重复它。
 */
@Component
public class SubjectTypes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 隐私文档里最初写的路径参数写法。保留为别名,免得已经按文档接好的调用方失效;
     * 规范写法是 check_type 本身。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "uid", "USER", "did", "DeviceID", "ip", "IP");

    /** check_type -> 事件表列名 */
    private final Map<String, String> columns = new LinkedHashMap<>();

    public SubjectTypes() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("domain-schema/enums.json")) {
            if (in == null) {
                throw new IOException("jar 内缺少 domain-schema/enums.json");
            }
            JsonNode values = MAPPER.readTree(in).path("check_type").path("values");
            values.fields().forEachRemaining(e ->
                    columns.put(e.getKey(), e.getValue().path("event_field").asText()));
        }
        if (columns.isEmpty()) {
            throw new IOException("enums.json 中没有 check_type.values");
        }
    }

    /** 归一化路径参数:接受 check_type 本身,也接受隐私文档里的别名。 */
    public String canonical(String type) {
        if (type == null) {
            return null;
        }
        if (columns.containsKey(type)) {
            return type;
        }
        String alias = ALIASES.get(type.toLowerCase());
        return alias != null && columns.containsKey(alias) ? alias : null;
    }

    /** 该主体类型在事件明细表中的列名。 */
    public String column(String canonicalType) {
        return columns.get(canonicalType);
    }

    /** 全部 check_type,用于遍历名单键与给出报错提示。 */
    public Set<String> all() {
        return columns.keySet();
    }
}
