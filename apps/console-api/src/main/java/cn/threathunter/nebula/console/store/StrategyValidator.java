package cn.threathunter.nebula.console.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 策略定义校验。
 *
 * <p>校验分两层,缺一层都不够:
 * <ol>
 *   <li><b>结构</b> —— 按 {@code packages/domain-schema/strategy.schema.json} 校验。
 *       用同一份 schema 而不是在 Java 里另写一套字段检查:后者会随时间与 schema
 *       分歧,而「同一个领域模型在两处各写一份、逐渐漂移」正是 1.x 最大的结构性
 *       问题(Python 的 nebula_meta 与 Java 的 com.threathunter.variable)。</li>
 *   <li><b>引用</b> —— 条件里 counter 指向的事件必须存在。schema 管不了这个:
 *       {@code "event": "ORDER_SUBMITT"} 在结构上完全合法,但策略上线后永不命中,
 *       <b>而且不会报错</b> —— 运营只会看到「这条策略没量」,查不出原因。这类
 *       静默失效比直接报错危险得多。</li>
 * </ol>
 */
@Component
public class StrategyValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchema schema;
    private final MetadataStore metadata;

    public StrategyValidator(MetadataStore metadata) throws IOException {
        this.metadata = metadata;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("domain-schema/strategy.schema.json")) {
            if (in == null) {
                throw new IOException("jar 内缺少 domain-schema/strategy.schema.json"
                        + "(构建时应从 packages/domain-schema/ 复制)");
            }
            this.schema = factory.getSchema(MAPPER.readTree(in), config);
        }
    }

    /** 返回全部问题;空列表表示通过。一次给全,不要让人改一条再提交一次。 */
    public List<String> validate(JsonNode definition) {
        List<String> problems = new ArrayList<>();
        for (ValidationMessage m : schema.validate(definition)) {
            problems.add(m.getMessage());
        }
        // 结构不合法时不做引用检查 —— 字段可能压根不是预期的形状,报出来的
        // 引用错误只会是噪音
        if (!problems.isEmpty()) {
            return problems;
        }

        Map<String, Set<String>> eventFields = metadata.eventFields();
        for (CounterRef ref : counterRefs(definition)) {
            Set<String> fields = eventFields.get(ref.event());
            if (fields == null) {
                problems.add("counter 引用了不存在的事件:" + ref.event()
                        + "(策略结构合法,但上线后永不命中,且不会报错)");
                continue;
            }
            for (String f : ref.fields()) {
                if (!fields.contains(f)) {
                    problems.add("事件 " + ref.event() + " 上没有字段 " + f);
                }
            }
        }
        return problems;
    }

    record CounterRef(String event, Set<String> fields) {
    }

    /** 递归收集条件树里的 counter 引用。 */
    static List<CounterRef> counterRefs(JsonNode node) {
        List<CounterRef> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, List<CounterRef> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode counter = node.get("counter");
            if (counter != null && counter.isObject()) {
                JsonNode event = counter.get("event");
                if (event != null && event.isTextual()) {
                    Set<String> fields = new LinkedHashSet<>();
                    addTexts(counter.get("groupby"), fields);
                    addTexts(counter.get("operand"), fields);
                    JsonNode filter = counter.get("filter");
                    if (filter != null && filter.hasNonNull("object")) {
                        fields.add(filter.get("object").asText());
                    }
                    out.add(new CounterRef(event.asText(), fields));
                }
            }
            node.fields().forEachRemaining(e -> collect(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }

    private static void addTexts(JsonNode array, Set<String> into) {
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    into.add(n.asText());
                }
            }
        }
    }
}
