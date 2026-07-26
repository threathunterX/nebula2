package cn.threathunter.nebula.console.api;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.store.MetadataStore;
import cn.threathunter.nebula.console.store.StrategyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 策略与变量的管理接口。写操作记审计。 */
@RestController
@RequestMapping("/api/v2")
public class MetadataController {

    private static final Set<String> VALID_STATUS =
            Set.of("inedit", "test", "online", "outline");

    private final MetadataStore store;
    private final AuditLog audit;
    private final StrategyValidator validator;

    public MetadataController(MetadataStore store, AuditLog audit,
                              StrategyValidator validator) {
        this.validator = validator;
        this.store = store;
        this.audit = audit;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return store.stats();
    }

    @GetMapping("/strategies")
    public List<MetadataStore.StrategySummary> listStrategies(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return store.listStrategies(category, status, limit);
    }

    @GetMapping("/strategies/{name}")
    public ResponseEntity<JsonNode> getStrategy(@PathVariable String name) {
        return store.getStrategy(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record StatusUpdate(String status) {
    }

    /**
     * 切换策略状态。
     *
     * <p>内置模板以 {@code test} 状态分发 —— 照常计算并产出告警,但标记 test=true
     * 不参与线上决策。切到 {@code online} 前应先校准阈值,见
     * docs/reference/strategies.md。
     */
    @PutMapping("/strategies/{name}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable String name,
            @RequestBody StatusUpdate body,
            Authentication auth,
            HttpServletRequest http) {
        String status = body == null ? null : body.status();
        if (status == null || !VALID_STATUS.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "status 取值非法",
                    "allowed", VALID_STATUS));
        }
        int n = store.updateStrategyStatus(name, status);
        boolean ok = n > 0;
        // 记录真实操作者,不是硬编码 —— 审计的意义就在于能追溯到人
        audit.record(auth == null ? "anonymous" : auth.getName(),
                "update_strategy_status", "strategy", name,
                Map.of("status", status), http.getRemoteAddr(), ok);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "name", name, "status", status,
                "metadata_version", store.metadataVersion()));
    }

    @GetMapping("/variables")
    public List<MetadataStore.VariableSummary> listVariables(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String sensitivity,
            @RequestParam(defaultValue = "100") int limit) {
        return store.listVariables(module, sensitivity, limit);
    }

    @GetMapping("/variables/{name}")
    public ResponseEntity<JsonNode> getVariable(@PathVariable String name) {
        return store.getVariable(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record StrategyWrite(JsonNode definition, Integer expected_version,
                                String change_note) {
    }

    /**
     * 新建或更新策略。
     *
     * <p>校验不通过时<b>一次返回全部问题</b>,不要让人改一条再提交一次。
     */
    @PutMapping("/strategies/{name}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Map<String, Object>> saveStrategy(
            @PathVariable String name,
            @RequestBody StrategyWrite body,
            Authentication auth,
            HttpServletRequest http) {

        if (body == null || body.definition() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 definition"));
        }
        JsonNode def = body.definition();
        if (!def.hasNonNull("name") || !def.get("name").asText().equals(name)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "definition.name 必须与路径中的策略名一致"));
        }
        List<String> problems = validator.validate(def);
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "策略定义校验未通过", "problems", problems));
        }

        String actor = auth == null ? "anonymous" : auth.getName();
        int expected = body.expected_version() == null ? 0 : body.expected_version();
        MetadataStore.SaveResult r = store.saveStrategy(
                name, def, actor, body.change_note(), expected);

        audit.record(actor, expected == 0 ? "create_strategy" : "update_strategy",
                "strategy", name,
                Map.of("expected_version", String.valueOf(expected),
                        "result_version", String.valueOf(r.version()),
                        "change_note", body.change_note() == null ? "" : body.change_note()),
                http.getRemoteAddr(), r.ok());

        if (!r.ok()) {
            // 409 而不是 400:请求本身没错,是状态变了
            return ResponseEntity.status(409).body(Map.of(
                    "error", r.error(), "current_version", r.version()));
        }
        return ResponseEntity.ok(Map.of("name", name, "version", r.version()));
    }

    /** 策略的修订历史。 */
    @GetMapping("/strategies/{name}/revisions")
    public ResponseEntity<Map<String, Object>> revisions(
            @PathVariable String name,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(Map.of(
                "name", name,
                "revisions", store.revisions(name, Math.clamp(limit, 1, 200))));
    }

    /** 某个历史版本的完整定义。回滚 = 把它作为新版本重新提交一次。 */
    @GetMapping("/strategies/{name}/revisions/{version}")
    public ResponseEntity<?> revision(@PathVariable String name, @PathVariable int version) {
        return store.revision(name, version)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(
                        Map.of("error", "没有这个版本")));
    }
}
