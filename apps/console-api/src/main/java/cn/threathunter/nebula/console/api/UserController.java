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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * 当前登录者是谁、有哪些角色。
     *
     * <p>任何已认证的角色都能读<b>自己</b>的信息 —— 界面需要它来决定显示什么。
     * 没有这个接口,前端只能把管理员才能用的入口显示给所有人,等到点进去才报 403;
     * 或者反过来靠试探请求推断角色,那会在每次加载时打一串注定失败的请求。
     *
     * <p>返回的是<b>认证结果</b>里的角色,不是重新查库 —— 这样它反映的正是当前这次
     * 请求实际带着的权限,与授权判定用的是同一份事实。
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        if (auth == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        }
        List<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        return ResponseEntity.ok(Map.of("username", auth.getName(), "roles", roles));
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

    public record EnabledRequest(Boolean enabled) {
    }

    /**
     * 停用或启用账号。
     *
     * <p><b>不允许停用自己。</b>管理员把自己停掉之后就再也登不进来改回去了 ——
     * 只能去数据库里手动改。这不是理论风险,是点错一行就会发生的事。
     */
    @PutMapping("/{username}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable String username,
                                                          @RequestBody EnabledRequest req,
                                                          Authentication auth,
                                                          HttpServletRequest http) {
        if (req == null || req.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 enabled"));
        }
        if (!req.enabled() && auth != null && username.equals(auth.getName())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "不能停用自己的账号 —— 停用后无法再登录改回去"));
        }
        boolean changed = users.setEnabled(username, req.enabled());
        audit.record(auth == null ? "anonymous" : auth.getName(),
                req.enabled() ? "enable_user" : "disable_user", "user", username,
                Map.of(), http.getRemoteAddr(), changed);
        if (!changed) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "账号不存在,或已经是该状态"));
        }
        return ResponseEntity.ok(Map.of("username", username, "enabled", req.enabled()));
    }

    /** 重置口令。新口令只在这次响应中出现。 */
    @PostMapping("/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String username,
                                                             Authentication auth,
                                                             HttpServletRequest http) {
        String password;
        try {
            password = users.resetPassword(username);
        } catch (IllegalArgumentException e) {
            audit.record(auth == null ? "anonymous" : auth.getName(),
                    "reset_password", "user", username, Map.of(), http.getRemoteAddr(), false);
            return ResponseEntity.status(404).body(Map.of("error", "账号不存在"));
        }
        audit.record(auth == null ? "anonymous" : auth.getName(),
                "reset_password", "user", username, Map.of(), http.getRemoteAddr(), true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("notice", "此口令只显示这一次,请交付本人后立即让其更换。");
        return ResponseEntity.ok(body);
    }
}
