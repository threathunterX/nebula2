package cn.threathunter.nebula.console.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置。
 *
 * <p>两类主体走两条认证路径:
 * <ul>
 *   <li><b>人类账号</b> —— HTTP Basic + Argon2id,访问 {@code /api/v2/**} 管理接口,
 *       按角色授权</li>
 *   <li><b>业务系统</b> —— 服务令牌,只能访问 {@code /checkRisk}</li>
 * </ul>
 *
 * <p>两者刻意分开:业务系统不该持有能改策略的凭据,管理员也不该用自己的账号
 * 去跑生产查询。两者共用一套凭据时,一个业务侧令牌泄露就意味着策略配置同时失守。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Argon2id。参数取 Spring Security 的推荐默认值(16 字节盐、32 字节输出、
     * 1 并行度、16MB 内存、3 轮)。
     *
     * <p>不含盐的单次哈希对弱口令没有实质保护,而且同口令的不同账号哈希相同。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public UserDetailsService userDetailsService(UserStore store) {
        return username -> store.find(username)
                .filter(UserStore.User::enabled)
                .map(u -> User.withUsername(u.username())
                        .password(u.passwordHash())
                        .roles(u.roles().toArray(new String[0]))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在或已停用"));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ServiceTokenFilter tokenFilter,
                                          LoginThrottleFilter throttleFilter)
            throws Exception {
        http
            // 无状态 API,不需要 CSRF 令牌;凭据每次请求携带
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 两个都锚在 UsernamePasswordAuthenticationFilter 之前,按插入顺序生效 ——
            // addFilterBefore 的锚点必须是 Spring Security 已知的过滤器,不能是自定义的。
            //
            // 节流必须在认证之前:锁定期内即便口令正确也要拒绝,否则「是否立刻成功」
            // 会告诉攻击者哪次猜中了,锁定就退化成延迟。
            .addFilterBefore(throttleFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    // 错误页转发必须放行。容器把 403/404/500 转发到 /error 时会再走一遍
                    // 安全链,此时上下文已清空 —— 若不放行,denyAll 会把真实状态码统统
                    // 改写成 401 并附上 Basic 挑战,调用方看到的错误与实际原因无关。
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    // 指标端点**必须认证**。它会暴露请求量、错误率、各接口的调用分布 ——
                    // 也就是业务量级。裸奔的 /actuator/prometheus 是一个免费的商业情报接口。
                    // 抓取方用一个 VIEWER 账号即可。
                    .requestMatchers("/actuator/prometheus")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    // /checkRisk 只接受服务令牌,且必须带 checkRisk 权限
                    .requestMatchers("/checkRisk").hasAuthority("SCOPE_checkRisk")
                    // 元数据下发给引擎。引擎是服务不是人,走 metadata:read 作用域;
                    // 人类角色也允许读,便于排查「引擎到底拿到了哪一版」。
                    .requestMatchers(org.springframework.http.HttpMethod.GET,
                            "/api/v2/metadata/**")
                        .hasAnyAuthority("SCOPE_metadata:read",
                                "ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER")
                    // 「我是谁」:任何已认证角色都能读自己的信息。必须排在下面
                    // /api/v2/** 的角色规则之前 —— 服务令牌也会打到这里,让它拿到
                    // 401/403 而不是一个空的角色列表,前端才好区分。
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v2/users/me")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    // 主体权利接口仅管理员 —— 导出与删除个人信息不是日常运营操作。
                    //
                    // 必须排在下面那条通用 GET 规则<b>之前</b>:匹配是首条命中即生效,
                    // 排在后面的话 GET /api/v2/privacy/... 会先命中「读接口对三种角色
                    // 开放」,导出一个人的全部数据就成了 VIEWER 也能做的事。
                    // 这个顺序问题不会有任何报错 —— 是缺陷注入试出来的。
                    .requestMatchers("/api/v2/privacy/**").hasRole("ADMIN")
                    // 读接口:任意已认证的人类角色
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v2/**")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                    // 写接口:仅管理员与运营
                    .requestMatchers("/api/v2/**").hasAnyRole("ADMIN", "OPERATOR")
                    .anyRequest().denyAll())
            .httpBasic(basic -> {
            });
        return http.build();
    }
}
