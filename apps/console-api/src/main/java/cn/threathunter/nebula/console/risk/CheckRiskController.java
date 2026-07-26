package cn.threathunter.nebula.console.risk;

import cn.threathunter.nebula.console.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /checkRisk} —— 业务系统的同步风险查询。
 *
 * <p><b>与 1.x 保持契约兼容</b>:请求与响应结构不变,业务系统的接入代码无需改动。
 * 这是 2.0 明确承诺不变更的对外契约之一,见 docs/migration/from-1x.md。
 *
 * <p>延迟敏感路径:直接查 Redis 名单,不经过数据库、不做复杂计算。
 */
@RestController
public class CheckRiskController {

    private final NoticeStore notices;
    private final AuditLog audit;

    public CheckRiskController(NoticeStore notices, AuditLog audit) {
        this.notices = notices;
        this.audit = audit;
    }

    /** 请求中的一个待查项。 */
    public record CheckItem(String type, String value) {
    }

    public record CheckRiskRequest(List<CheckItem> check_item, String scene_type,
                                   Boolean full_respond) {
    }

    @PostMapping("/checkRisk")
    public ResponseEntity<Map<String, Object>> check(@RequestBody CheckRiskRequest req,
                                                     Authentication auth,
                                                     HttpServletRequest http) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("error_code", "");
        resp.put("scene_type", req.scene_type() == null ? "" : req.scene_type());

        List<Map<String, Object>> hits = new ArrayList<>();
        if (req.check_item() != null) {
            for (CheckItem item : req.check_item()) {
                if (item == null || item.type() == null || item.value() == null) {
                    continue;
                }
                for (Map<String, Object> n : notices.get(item.type(), item.value())) {
                    // 测试态策略的告警不参与线上决策。
                    //
                    // 这是 test 状态的**全部意义**:照常计算、照常产出告警供校准,但
                    // 不影响真实流量。少了这一句,test 与 online 对调用方毫无区别 ——
                    // 而内置模板 170 条**全部**以 test 状态分发,也就是说全新部署会让
                    // 这 170 条统统真的拦线上流量,与文档承诺的完全相反。
                    //
                    // 过滤放在这里而不是引擎侧:引擎要把 test 告警写进 ClickHouse 供
                    // 运营观察误报率,那是它存在的理由。真正不能受影响的是这个同步
                    // 决策接口。
                    if (Boolean.TRUE.equals(n.get("test"))) {
                        continue;
                    }
                    if (req.scene_type() != null && !req.scene_type().isBlank()
                            && !req.scene_type().equalsIgnoreCase(String.valueOf(n.get("scene_name")))) {
                        continue;
                    }
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("rule_name", n.get("strategy_name"));
                    hit.put("key_hit", item.type());
                    hit.put("key_value", item.value());
                    hit.put("decision", n.get("decision"));
                    hit.put("remark", n.getOrDefault("remark", ""));
                    hits.add(hit);
                }
            }
        }

        // 最终决策取最严格的一个:reject > review > accept
        String finalDecision = "accept";
        Map<String, Object> strongest = null;
        for (Map<String, Object> h : hits) {
            String d = String.valueOf(h.get("decision"));
            if (rank(d) > rank(finalDecision)) {
                finalDecision = d;
                strongest = h;
            }
        }

        resp.put("rule_hits", hits);
        resp.put("final_decision", finalDecision);
        resp.put("final_rule_hit", strongest == null ? "" : strongest.get("rule_name"));
        resp.put("final_key_hit", strongest == null ? "" : strongest.get("key_hit"));
        resp.put("final_value_hit", strongest == null ? "" : strongest.get("key_value"));
        resp.put("final_desc", strongest == null ? "" : strongest.get("remark"));

        // 只记录查询条件与命中量,不记录返回的个人信息本身
        // 记录是哪个服务令牌发起的查询
        audit.record(auth == null ? "anonymous" : auth.getName(), "checkRisk", "notice",
                req.scene_type() == null ? "" : req.scene_type(),
                Map.of("items", req.check_item() == null ? 0 : req.check_item().size(),
                        "hits", hits.size(), "decision", finalDecision),
                http.getRemoteAddr(), true);

        return ResponseEntity.ok(resp);
    }

    private static int rank(String decision) {
        return switch (decision == null ? "" : decision) {
            case "reject" -> 3;
            case "review" -> 2;
            case "accept" -> 1;
            default -> 0;
        };
    }
}
