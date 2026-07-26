package cn.threathunter.nebula.console.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把领域 schema 原样下发给界面。
 *
 * <h2>为什么要有这个接口</h2>
 *
 * 策略编辑器需要知道:category 有哪些取值、比较算子有哪些、decision 有哪些、
 * 名单类型有哪些。这些<b>已经</b>定义在 {@code packages/domain-schema/} 里,而且
 * 服务端校验用的就是同一份。
 *
 * <p>前端另抄一份的后果不是编译错误,而是<b>界面允许的和服务端接受的不一样</b>:
 * schema 加了一个算子,界面选不到;schema 删了一个,界面还能选中然后保存时报 400。
 * 两种都表现为「这个功能好像坏了」,而没有任何地方会报警。
 *
 * <p>「同一个领域概念在两处各写一份、逐渐漂移」是 1.x 最大的结构性问题
 * (Python 的 {@code nebula_meta} 与 Java 的 {@code com.threathunter.variable}),
 * 这里不重复它。
 *
 * <h2>为什么原样下发而不是加工成「界面友好」的结构</h2>
 *
 * 加工就是第二次表达,而第二次表达同样会漂移 —— 只是把漂移从前端挪到了这里。
 * 原样下发之后,前端从 schema 里读什么、怎么读,是前端的事;schema 变了它自然
 * 跟着变。
 */
@RestController
@RequestMapping("/api/v2/schema")
public class SchemaController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 允许下发的文件白名单。
     *
     * <p>不接受任意路径:{@code @PathVariable} 来自请求,拼进 classpath 路径就是
     * 目录穿越。这里的取值全部来自本类,与请求无关。
     */
    private static final Map<String, String> FILES = Map.of(
            "strategy", "domain-schema/strategy.schema.json",
            "event-model", "domain-schema/event-model.schema.json",
            "variable-model", "domain-schema/variable-model.schema.json",
            "notice", "domain-schema/notice.schema.json",
            "enums", "domain-schema/enums.json");

    /** 有哪些可取的 schema。 */
    @GetMapping
    public Map<String, Object> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", FILES.keySet().stream().sorted().toList());
        body.put("note", "原样下发 packages/domain-schema/ 的内容,与服务端校验用的是同一份");
        return body;
    }

    @GetMapping("/{which}")
    public ResponseEntity<JsonNode> get(@PathVariable String which) throws IOException {
        String path = FILES.get(which);
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("jar 内缺少 " + path
                        + "(构建时应从 packages/domain-schema/ 复制)");
            }
            return ResponseEntity.ok(MAPPER.readTree(in));
        }
    }

    /** 供测试引用。 */
    static Set<String> available() {
        return FILES.keySet();
    }
}
