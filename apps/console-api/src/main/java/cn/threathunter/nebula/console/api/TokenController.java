package cn.threathunter.nebula.console.api;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.auth.ServiceTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务令牌签发。仅管理员可用。 */
@RestController
@RequestMapping("/api/v2/tokens")
public class TokenController {

    private static final Set<String> VALID_SCOPES = Set.of("checkRisk", "metadata:read");

    private final ServiceTokenStore tokens;
    private final AuditLog audit;

    public TokenController(ServiceTokenStore tokens, AuditLog audit) {
        this.tokens = tokens;
        this.audit = audit;
    }

    public record IssueRequest(String description, List<String> scopes,
                               List<String> allowed_cidrs) {
    }

    /**
     * 签发服务令牌。
     *
     * <p><b>明文只在这次响应中出现,之后无法再读取</b> —— 库里只有哈希。丢失只能
     * 重新签发。这与 1.x 把 token 明文写进配置文件形成对比:那样泄露后既无从
     * 察觉也无法轮换。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> issue(@RequestBody IssueRequest req,
                                                     Authentication auth,
                                                     HttpServletRequest http) {
        List<String> scopes = req == null || req.scopes() == null ? List.of() : req.scopes();
        if (scopes.isEmpty() || !VALID_SCOPES.containsAll(scopes)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "scopes 取值非法或为空", "allowed", VALID_SCOPES));
        }
        List<String> cidrs = req.allowed_cidrs() == null ? List.of() : req.allowed_cidrs();
        ServiceTokenStore.Issued issued = tokens.issue(
                req.description() == null ? "" : req.description(), scopes, cidrs);

        // 审计里只记 tokenId,绝不记明文
        audit.record(auth == null ? "anonymous" : auth.getName(),
                "issue_service_token", "service_token", issued.tokenId(),
                Map.of("scopes", scopes, "allowed_cidrs", cidrs),
                http.getRemoteAddr(), true);

        return ResponseEntity.ok(Map.of(
                "token_id", issued.tokenId(),
                "token", issued.plaintext(),
                "scopes", scopes,
                "allowed_cidrs", cidrs,
                "notice", "此令牌明文只显示这一次,请立即保存。丢失只能重新签发。"));
    }
}
