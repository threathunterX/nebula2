package cn.threathunter.nebula.console.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计日志。
 *
 * <p>全部管理操作与个人信息查询都要留痕 —— 1.x 完全没有审计能力,而个保法要求
 * 对个人信息的处理活动可追溯。
 *
 * <p><b>查询个人信息时只记录查询条件与命中量,不记录返回的数据本身</b> ——
 * 把个人信息复制进审计日志会让问题更严重,而不是更安全。
 */
@Component
public class AuditLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String resourceType, String resourceId,
                       Map<String, Object> detail, String clientIp, boolean success) {
        String json;
        try {
            json = MAPPER.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            json = "{}";
        }
        jdbc.update(
                "INSERT INTO audit_log "
                        + "(actor, action, resource_type, resource_id, detail, client_ip, outcome) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?::inet, ?)",
                actor, action, resourceType, resourceId, json,
                clientIp == null || clientIp.isBlank() ? null : clientIp,
                success ? "success" : "failure");
    }
}
