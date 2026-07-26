package cn.threathunter.nebula.console.risk;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.auth.LoginThrottle;
import cn.threathunter.nebula.console.auth.ServiceTokenStore;
import cn.threathunter.nebula.console.auth.UserStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 测试态策略不得影响线上决策。
 *
 * <h2>为什么单独一个测试类</h2>
 *
 * 这条语义在四处文档里被承诺过(迁移指南、策略参考、策略指南、README),而实现里
 * 它只是<b>一个 if</b>。删掉那个 if 不会有任何报错,只会让所有测试态策略开始真的
 * 拦线上流量 —— 而内置模板 170 条全部以 test 状态分发,也就是说全新部署会把
 * 「先观察不决策」变成「全部生效」。
 *
 * <p>发现方式:写威胁模型文档时想引用「测试模式保护线上」这条,去核实,发现不成立 ——
 * 对照组(test=false)与实验组(test=true)在真实链路上都返回了 reject。
 *
 * <p>这里关掉安全过滤链:要验证的是决策逻辑,不是授权。授权由
 * {@code AuthorizationMatrixTest} 单独钉着。
 */
@WebMvcTest(controllers = CheckRiskController.class)
@AutoConfigureMockMvc(addFilters = false)
class CheckRiskTestModeTest {

    /** 同一个主体上挂两条名单:一条测试态,一条正式态。 */
    private static final String SUBJECT_BOTH = "both";
    /** 只挂一条测试态名单的主体。 */
    private static final String SUBJECT_TEST_ONLY = "test-only";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NoticeStore notices;
    @MockitoBean
    private AuditLog audit;
    // 安全过滤链虽然关了,上下文里的过滤器 bean 仍会被创建,它们的依赖也要有
    @MockitoBean
    private LoginThrottle throttle;
    @MockitoBean
    private ServiceTokenStore tokens;
    @MockitoBean
    private UserStore users;

    private static Map<String, Object> notice(String name, boolean test, String decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy_name", name);
        m.put("scene_name", "login");
        m.put("decision", decision);
        m.put("risk_score", 90);
        m.put("remark", "");
        m.put("test", test);
        return m;
    }

    @BeforeEach
    void stubNotices() {
        when(notices.get(anyString(), anyString())).thenReturn(List.of());
        when(notices.get("USER", SUBJECT_TEST_ONLY))
                .thenReturn(List.of(notice("测试中的策略", true, "reject")));
        when(notices.get("USER", SUBJECT_BOTH)).thenReturn(List.of(
                notice("测试中的策略", true, "reject"),
                notice("正式策略", false, "review")));
    }

    private static String body(String subject) {
        return "{\"scene_type\":\"login\",\"check_item\":"
                + "[{\"type\":\"USER\",\"value\":\"" + subject + "\"}]}";
    }

    @Test
    @DisplayName("只有测试态命中时:不拦,且命中列表为空")
    void testOnlyNoticesDoNotBlock() throws Exception {
        mvc.perform(post("/checkRisk").contentType("application/json")
                        .content(body(SUBJECT_TEST_ONLY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.final_decision").value("accept"))
                // 不只是决策要对 —— 命中列表里也不能出现测试态策略的名字,
                // 否则调用方仍然会知道「这个人撞上了某条规则」并据此处置
                .andExpect(jsonPath("$.rule_hits").isEmpty())
                .andExpect(jsonPath("$.final_rule_hit").value(""));
    }

    @Test
    @DisplayName("测试态与正式态同时命中:只按正式态那条决策")
    void testNoticesAreIgnoredWhenMixedWithLive() throws Exception {
        mvc.perform(post("/checkRisk").contentType("application/json")
                        .content(body(SUBJECT_BOTH)))
                .andExpect(status().isOk())
                // 测试态那条是 reject,正式态那条是 review。若测试态被算进去,
                // 最终决策会是更严格的 reject —— 这正是这个用例要挡住的回归
                .andExpect(jsonPath("$.final_decision").value("review"))
                .andExpect(jsonPath("$.final_rule_hit").value("正式策略"))
                .andExpect(jsonPath("$.rule_hits.length()").value(1));
    }
}
