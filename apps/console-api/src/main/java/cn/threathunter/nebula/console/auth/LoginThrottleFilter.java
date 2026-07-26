package cn.threathunter.nebula.console.auth;

import cn.threathunter.nebula.console.audit.AuditLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 Basic 认证之前拦住已被锁定的「来源 IP + 账号」组合。
 *
 * <p>放在认证之前是关键:锁定期内<b>即便口令正确也拒绝</b>。否则攻击者可以用「是否
 * 立刻返回成功」判断某次尝试是否猜中,锁定就退化成延迟而已。
 *
 * <p>锁定与失败计数走 {@link LoginThrottle}。这里只负责两件事:拦住已锁定的请求,
 * 以及在认证结束后把结果反馈回计数器。
 */
@Component
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;
    private final AuditLog audit;

    public LoginThrottleFilter(LoginThrottle throttle, AuditLog audit) {
        this.throttle = throttle;
        this.audit = audit;
    }

    /** 只从 Basic 头里取用户名 —— 服务令牌走 Bearer,不受这一层影响。 */
    private static String basicUsername(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? null : decoded.substring(0, colon);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String username = basicUsername(req);
        if (username == null) {
            chain.doFilter(req, resp);
            return;
        }
        String ip = req.getRemoteAddr();

        if (throttle.locked(username, ip)) {
            audit.record(username, "login_blocked", "user", username,
                    Map.of("reason", "该来源与账号的组合处于锁定期"), ip, false);
            resp.setStatus(429);  // Too Many Requests;Jakarta 的 HttpServletResponse 没有这个常量
            resp.setContentType("application/json;charset=UTF-8");
            // 不区分「账号不存在」「口令错」「已锁定」之外的细节,但锁定本身要说清楚,
            // 否则真实用户会以为是口令记错了而反复尝试,把锁定期一次次延长
            resp.getWriter().write("{\"error\":\"登录尝试过多,请在 "
                    + throttle.lockout().toMinutes() + " 分钟后重试\"}");
            return;
        }

        chain.doFilter(req, resp);

        boolean authenticated = SecurityContextHolder.getContext().getAuthentication() != null
                && resp.getStatus() != HttpServletResponse.SC_UNAUTHORIZED;
        if (authenticated) {
            throttle.recordSuccess(username, ip);
        } else if (resp.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            int left = throttle.recordFailure(username, ip);
            if (left == 0) {
                audit.record(username, "login_lockout", "user", username,
                        Map.of("failures", String.valueOf(throttle.maxFailures())), ip, false);
            }
        }
    }
}
