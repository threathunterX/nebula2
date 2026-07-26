package cn.threathunter.nebula.console.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 策略校验。
 *
 * <p>重点在<b>引用检查</b>那一层。结构错误会被 schema 挡住并给出明确报错;引用
 * 错误不会 —— {@code "event": "ORDER_SUBMITT"} 结构完全合法,策略保存成功、上线
 * 成功,然后永远不命中,也永远不报错。运营看到的只是「这条策略没量」。这类静默
 * 失效比直接报错危险得多,所以每种写法都要有对应的用例。
 */
class StrategyValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StrategyValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        MetadataStore store = mock(MetadataStore.class);
        when(store.eventFields()).thenReturn(Map.of(
                "HTTP_DYNAMIC", Set.of("c_ip", "page", "did"),
                // 继承已在 MetadataStore 里展开,这里拿到的是并好的结果
                "ORDER_SUBMIT", Set.of("c_ip", "page", "did", "order_id", "uid")));
        validator = new StrategyValidator(store);
    }

    private JsonNode seedStrategy() throws IOException {
        Path p = Path.of("../../seeds/strategies/IP下单不支付.json");
        return MAPPER.readTree(Files.readString(p));
    }

    @Test
    @DisplayName("仓库里的既有策略必须能通过校验 —— 否则校验器本身就是错的")
    void seedStrategyPasses() throws IOException {
        List<String> problems = validator.validate(seedStrategy());
        assertTrue(problems.isEmpty(), "既有策略被判不合规: " + problems);
    }

    @Test
    @DisplayName("counter 指向不存在的事件必须报出来")
    void unknownEventIsReported() throws IOException {
        String json = MAPPER.writeValueAsString(seedStrategy())
                .replace("\"ORDER_SUBMIT\"", "\"ORDER_SUBMITT\"");
        List<String> problems = validator.validate(MAPPER.readTree(json));
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ORDER_SUBMITT")),
                "没有指出拼错的事件名: " + problems);
    }

    @Test
    @DisplayName("counter 引用事件上没有的字段也要报出来")
    void unknownFieldIsReported() throws IOException {
        String json = MAPPER.writeValueAsString(seedStrategy())
                .replace("\"order_id\"", "\"orderid\"");
        List<String> problems = validator.validate(MAPPER.readTree(json));
        assertTrue(problems.stream().anyMatch(p -> p.contains("orderid")),
                "没有指出不存在的字段: " + problems);
    }

    @Test
    @DisplayName("结构不合法时只报结构问题,不叠加引用噪音")
    void structuralErrorsComeAlone() throws Exception {
        JsonNode bad = MAPPER.readTree("{\"name\":\"x\"}");
        List<String> problems = validator.validate(bad);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().noneMatch(p -> p.contains("counter 引用")),
                "结构都不对的时候不该再报引用问题: " + problems);
    }

    @Test
    @DisplayName("counter 引用能从嵌套的条件树里全部找出来")
    void refsAreCollectedRecursively() throws Exception {
        JsonNode n = MAPPER.readTree("""
                {"condition":{"conditions":[
                  {"conditions":[
                    {"left":{"kind":"counter","counter":{
                       "event":"E1","groupby":["a"],"operand":["b"],
                       "filter":{"object":"c"}}}}]},
                  {"left":{"kind":"counter","counter":{"event":"E2","groupby":["d"]}}}]}}
                """);
        var refs = StrategyValidator.counterRefs(n);
        assertEquals(2, refs.size());
        assertEquals(Set.of("a", "b", "c"), refs.get(0).fields());
        assertEquals("E2", refs.get(1).event());
    }
}
