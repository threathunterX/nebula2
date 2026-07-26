package cn.threathunter.nebula.console.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.threathunter.nebula.console.api.MetadataController;
import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.store.MetadataStore;
import cn.threathunter.nebula.console.store.StrategyValidator;
import java.time.Duration;
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
 * 登录节流。
 *
 * <p>Argon2id 让<b>离线</b>爆破变得昂贵,对<b>在线</b>爆破没有帮助 —— 后者只受限于网络
 * 带宽。这一层是认证的最后一块。
 *
 * <p>最关键的一条断言是<b>锁定期内即便口令正确也拒绝</b>:否则攻击者可以用「是否立刻
 * 返回成功」判断某次尝试是否猜中,锁定就退化成延迟而已。
 */
@WebMvcTest(controllers = MetadataController.class)
@Import({SecurityConfig.class, ServiceTokenFilter.class, LoginThrottleFilter.class})
class LoginThrottleFilterTest {

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
    private StrategyValidator validator;
    @MockitoBean
    private AuditLog audit;
    @MockitoBean
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        when(users.count()).thenReturn(1L);
        when(users.find("admin")).thenReturn(Optional.of(
                new UserStore.User("admin", encoder.encode("right"), "管理员",
                        List.of("ADMIN"), true)));
        when(tokens.verify(anyString())).thenReturn(Optional.empty());
        when(metadata.stats()).thenReturn(Map.of("events", 0));
        when(throttle.maxFailures()).thenReturn(5);
        when(throttle.lockout()).thenReturn(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("未锁定时,正确口令正常通过")
    void normalLoginWorks() throws Exception {
        when(throttle.locked(anyString(), anyString())).thenReturn(false);
        mvc.perform(get("/api/v2/stats").with(httpBasic("admin", "right")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("锁定期内即便口令正确也返回 429 —— 否则锁定只是延迟")
    void lockedRejectsEvenCorrectPassword() throws Exception {
        when(throttle.locked(anyString(), anyString())).thenReturn(true);
        // 用的是正确口令。如果这里返回 200,攻击者就能靠「是否立刻成功」
        // 确认自己猜中了哪个口令,锁定形同虚设。
        mvc.perform(get("/api/v2/stats").with(httpBasic("admin", "right")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("锁定只影响 Basic 认证,不影响服务令牌 —— 业务系统不该被人类的登录失败拖累")
    void bearerIsUnaffected() throws Exception {
        when(throttle.locked(anyString(), anyString())).thenReturn(true);
        // Bearer 请求不带 Basic 头,节流过滤器直接放行(随后因无权限被拒,但不是 429)
        mvc.perform(get("/api/v2/stats").header("Authorization", "Bearer svc_x.y"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("匿名请求不触发节流")
    void anonymousIsUnaffected() throws Exception {
        when(throttle.locked(anyString(), anyString())).thenReturn(true);
        mvc.perform(get("/api/v2/stats")).andExpect(status().isUnauthorized());
    }
}
