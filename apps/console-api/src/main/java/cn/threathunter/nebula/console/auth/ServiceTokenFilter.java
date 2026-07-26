package cn.threathunter.nebula.console.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 服务令牌认证。业务系统用 {@code Authorization: Bearer <token>} 调用
 * {@code /checkRisk}。
 *
 * <p><b>令牌与来源 IP 是 AND 关系:</b>令牌有效 <b>且</b> 来源在允许网段内
 * (未配置网段则不限制来源)。
 *
 * <p>写成「来源在白名单 <b>或</b> 令牌匹配」是常见的写法,但那样两道防线各自都能被
 * 单独绕过 —— 等于只有一道,而看起来像有两道。
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

    private final ServiceTokenStore tokens;

    public ServiceTokenFilter(ServiceTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented = header.substring(7).trim();
            Optional<ServiceTokenStore.ServiceToken> found = tokens.verify(presented);
            if (found.isPresent()) {
                ServiceTokenStore.ServiceToken t = found.get();
                if (sourceAllowed(t.allowedCidrs(), req.getRemoteAddr())) {
                    List<SimpleGrantedAuthority> auths = t.scopes().stream()
                            .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                            .toList();
                    var auth = new UsernamePasswordAuthenticationToken(
                            t.tokenId(), null, auths);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
                // 来源不允许时不设置认证 —— 后续由授权规则拒绝,并留下审计
            }
        }
        chain.doFilter(req, resp);
    }

    /**
     * 空网段列表表示不限制来源;否则来源必须落在其中之一。
     *
     * <p>来源取 TCP 连接的对端地址,<b>不读 {@code X-Forwarded-For}</b> —— 那个头
     * 由客户端自己填,信它等于把网段限制拱手让人。部署在反向代理之后时,应由代理
     * 做网段限制,或让代理以 PROXY protocol 传递真实来源。
     */
    static boolean sourceAllowed(List<String> cidrs, String remoteAddr) {
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        for (String cidr : cidrs) {
            if (matches(cidr, remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    /** 仅支持 IPv4 CIDR。不支持的写法一律判为不匹配,不做宽松解释。 */
    static boolean matches(String cidr, String addr) {
        try {
            if (!cidr.contains("/")) {
                return cidr.equals(addr);
            }
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1]);
            long net = ipv4ToLong(parts[0]);
            long ip = ipv4ToLong(addr);
            if (net < 0 || ip < 0 || prefix < 0 || prefix > 32) {
                return false;
            }
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (net & mask) == (ip & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long ipv4ToLong(String s) {
        String[] o = s.split("\\.");
        if (o.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : o) {
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255) {
                return -1;
            }
            v = (v << 8) | n;
        }
        return v;
    }
}
