package cn.threathunter.nebula.console.auth;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
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
 * 去跑生产查询。1.x 用同一套 token 混用两种场景,拿到它既能查风险也能改配置。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Argon2id。参数取 Spring Security 的推荐默认值(16 字节盐、32 字节输出、
     * 1 并行度、16MB 内存、3 轮)。
     *
     * <p>1.x 用的是无盐单次 SHA1 —— 弱口令一撞即出,而且同口令的不同账号哈希相同。
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
    public SecurityFilterChain filterChain(HttpSecurity http, ServiceTokenFilter tokenFilter)
            throws Exception {
        http
            // 无状态 API,不需要 CSRF 令牌;凭据每次请求携带
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    // 错误页转发必须放行。容器把 403/404/500 转发到 /error 时会再走一遍
                    // 安全链,此时上下文已清空 —— 若不放行,denyAll 会把真实状态码统统
                    // 改写成 401 并附上 Basic 挑战,调用方看到的错误与实际原因无关。
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    // /checkRisk 只接受服务令牌,且必须带 checkRisk 权限
                    .requestMatchers("/checkRisk").hasAuthority("SCOPE_checkRisk")
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

    /**
     * 首次启动初始化。
     *
     * <p><b>零默认口令</b>:不内置 admin/admin,也不从配置文件读口令 —— 配置文件
     * 会被提交、备份、复制到测试环境。改为生成随机口令并<b>只打印一次</b>,
     * 运维必须当场记录。
     */
    @Bean
    public ApplicationRunner bootstrapAdmin(UserStore store) {
        return args -> {
            if (store.count() > 0) {
                return;
            }
            String password = UserStore.randomPassword();
            store.create("admin", password, "初始管理员", List.of("ADMIN"));
            System.out.println();
            System.out.println("=".repeat(72));
            System.out.println("  已创建初始管理员账号。此口令只显示这一次,请立即记录并尽快更换。");
            System.out.println();
            System.out.println("    用户名: admin");
            System.out.println("    口令:   " + password);
            System.out.println();
            System.out.println("=".repeat(72));
            System.out.println();
        };
    }
}
