package cn.threathunter.nebula.console.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.threathunter.nebula.console.api.MetadataController;
import cn.threathunter.nebula.console.api.TokenController;
import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.risk.CheckRiskController;
import cn.threathunter.nebula.console.risk.NoticeStore;
import cn.threathunter.nebula.console.store.MetadataStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 「谁能访问什么」的授权矩阵。
 *
 * <p>手工 curl 验证过一次不算数 —— 授权规则是那种改一行就静默放宽的东西:
 * 给 {@code /api/v2/**} 加一条 {@code permitAll} 不会有任何报错,只会让管理接口
 * 变成匿名可写。这份测试把矩阵钉死,任何放宽都必须先改测试。
 */
@WebMvcTest(controllers = {MetadataController.class, TokenController.class,
        CheckRiskController.class})
@Import({SecurityConfig.class, ServiceTokenFilter.class})
class AuthorizationMatrixTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PasswordEncoder encoder;

    @MockitoBean
    private UserStore users;
    @MockitoBean
    private ServiceTokenStore tokens;
    @MockitoBean
    private MetadataStore metadata;
    @MockitoBean
    private NoticeStore notices;
    @MockitoBean
    private AuditLog audit;

    private static final String SVC = "svc_test.secret";

    @BeforeEach
    void setUp() {
        when(users.count()).thenReturn(1L);
        for (String role : List.of("ADMIN", "OPERATOR", "VIEWER")) {
            when(users.find(role.toLowerCase())).thenReturn(Optional.of(
                    new UserStore.User(role.toLowerCase(), encoder.encode("pw"),
                            role, List.of(role), true)));
        }
        when(users.find("nobody")).thenReturn(Optional.empty());
        // 先兜底再特化:任何其他令牌都校验失败
        when(tokens.verify(anyString())).thenReturn(Optional.empty());
        when(tokens.verify(SVC)).thenReturn(Optional.of(new ServiceTokenStore.ServiceToken(
                "svc_test", List.of("checkRisk"), List.of(), true)));
        when(metadata.stats()).thenReturn(Map.of("events", 0));
        when(notices.get(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("未认证:管理接口与 /checkRisk 全部 401")
    void anonymousDenied() throws Exception {
        mvc.perform(get("/api/v2/stats")).andExpect(status().isUnauthorized());
        mvc.perform(post("/checkRisk").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/tokens").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("VIEWER 能读,不能写 —— 只读账号必须真的只读")
    void viewerIsReadOnly() throws Exception {
        mvc.perform(get("/api/v2/stats").with(httpBasic("viewer", "pw")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v2/strategies/x/status").with(httpBasic("viewer", "pw"))
                .contentType("application/json").content("{\"status\":\"offline\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPERATOR 能改策略状态,但不能签发令牌")
    void operatorCannotIssueTokens() throws Exception {
        mvc.perform(post("/api/v2/tokens").with(httpBasic("operator", "pw"))
                .contentType("application/json")
                .content("{\"scopes\":[\"checkRisk\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("人类账号一律不能调 /checkRisk —— 即使是管理员")
    void humansCannotCallCheckRisk() throws Exception {
        mvc.perform(post("/checkRisk").with(httpBasic("admin", "pw"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("服务令牌能调 /checkRisk,但碰不到任何管理接口")
    void serviceTokenIsScoped() throws Exception {
        mvc.perform(post("/checkRisk").header("Authorization", "Bearer " + SVC)
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // 403 而非 401:令牌本身有效,只是没有管理权限
        mvc.perform(get("/api/v2/stats").header("Authorization", "Bearer " + SVC))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/tokens").header("Authorization", "Bearer " + SVC)
                .contentType("application/json").content("{\"scopes\":[\"checkRisk\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("错误口令与不存在的账号都是 401,不区分")
    void badCredentials() throws Exception {
        mvc.perform(get("/api/v2/stats").with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/stats").with(httpBasic("nobody", "pw")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未在规则中列出的路径默认拒绝,不是默认放行")
    void unlistedPathsAreDenied() throws Exception {
        mvc.perform(get("/whatever").with(httpBasic("admin", "pw")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
