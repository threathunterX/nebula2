package cn.threathunter.nebula.console.api;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.auth.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号管理。仅管理员可用。
 *
 * <p>没有这个接口时,系统里永远只有引导阶段那一个管理员 —— 角色划分写在配置里
 * 却没人能被赋予 VIEWER 或 OPERATOR,「最小权限」只是一句话。
 *
 * <p><b>不接受调用方指定口令。</b>口令由服务端生成并只返回一次。让调用方传口令
 * 意味着它会出现在请求体、反向代理日志、shell 历史和 CI 变量里;而「管理员替别人
 * 设一个临时口令」这种流程,现实中设出来的几乎总是 Nebula@2026 这种。
 */
@RestController
@RequestMapping("/api/v2/users")
public class UserController {

    private static final Set<String> VALID_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");

    private final UserStore users;
    private final AuditLog audit;

    public UserController(UserStore users, AuditLog audit) {
        this.users = users;
        this.audit = audit;
    }

    public record CreateRequest(String username, String display_name, List<String> roles) {
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of("users", users.list()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req,
                                                      Authentication auth,
                                                      HttpServletRequest http) {
        if (req == null || req.username() == null
                || !req.username().matches("[a-z][a-z0-9._-]{2,31}")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "username 须为 3-32 位小写字母、数字、点、下划线或连字符,且以字母开头"));
        }
        List<String> roles = req.roles() == null ? List.of() : req.roles();
        if (roles.isEmpty() || !VALID_ROLES.containsAll(roles)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "roles 取值非法或为空", "allowed", VALID_ROLES));
        }
        if (users.find(req.username()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "账号已存在"));
        }

        String password = UserStore.randomPassword();
        users.create(req.username(), password,
                req.display_name() == null ? req.username() : req.display_name(), roles);

        // 审计记录里只有账号名与角色,没有口令
        audit.record(auth == null ? "anonymous" : auth.getName(),
                "create_user", "user", req.username(),
                Map.of("roles", roles), http.getRemoteAddr(), true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", req.username());
        body.put("roles", roles);
        body.put("password", password);
        body.put("notice", "此口令只显示这一次,请交付本人后立即让其更换。");
        return ResponseEntity.ok(body);
    }
}
