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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** 列出全部令牌的元数据。绝不返回明文或哈希 —— 明文只在签发那一次出现过。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of("tokens", tokens.list()));
    }

    /**
     * 吊销令牌。
     *
     * <p>用 {@code DELETE} 是因为它在语义上就是「让这个令牌不再存在」,但落到存储上
     * 是置 {@code enabled = false} 而非删行 —— 理由见 {@code ServiceTokenStore#revoke}。
     *
     * <p>重复吊销返回 404 而不是 200:调用方以为自己刚吊销了一个还在用的令牌,和
     * 「它早就吊销了」是两种不同的处境,不该看到同样的结果。
     */
    @DeleteMapping("/{tokenId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String tokenId,
                                                      Authentication auth,
                                                      HttpServletRequest http) {
        boolean changed = tokens.revoke(tokenId);
        audit.record(auth == null ? "anonymous" : auth.getName(),
                "revoke_service_token", "service_token", tokenId,
                Map.of(), http.getRemoteAddr(), changed);
        if (!changed) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "令牌不存在,或已经是吊销状态"));
        }
        return ResponseEntity.ok(Map.of("token_id", tokenId, "enabled", false));
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
