package cn.threathunter.nebula.console.privacy;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.risk.SubjectMasking;
import cn.threathunter.nebula.console.store.ClickHouseClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据主体权利 —— 导出与删除。
 *
 * <p>个人信息保护法要求个人有权查阅、复制、删除其个人信息。隐私设计里写了这套接口,
 * 但一直没有实现;这是该文档中最后一处「规定了但没做」。
 *
 * <h2>删除怎么在 HMAC 之后仍然可行</h2>
 *
 * 事件明细的 {@code uid} / {@code did} / {@code sid} 已经是 HMAC 存储的,库里没有原值。
 * 但 HMAC 是<b>确定性</b>的:用同一把密钥对主体标识算一次,就能得到库里那个值,
 * 按它删即可。这不是巧合 —— 正是「可查询性」这个设计目标带来的副产品。
 *
 * <p>反过来说:<b>密钥轮换之后就删不掉轮换前的数据了</b>,因为算不出当时的哈希。
 * 这一点必须在轮换前想清楚,文档里也写了。
 *
 * <h2>审计日志不删</h2>
 *
 * 审计记录的是「谁在什么时候查了谁」,删掉它等于销毁问责链条 —— 而问责链条恰恰是
 * 保护数据主体的机制之一。个保法要求的是删除<b>个人信息</b>,不是删除对个人信息的
 * 操作记录;后者属于履行安全保护义务所必需。审计表里存的本来也是掩码值。
 *
 * <h2>身份核验不在这里</h2>
 *
 * 「提出删除请求的人确实是该主体本人」的核验流程由部署方按自身合规要求实现 ——
 * 它涉及业务侧的身份体系,系统只提供执行能力。这条在隐私文档里也写明了。
 */
@RestController
@RequestMapping("/api/v2/privacy/subject")
public class SubjectRightsController {

    private final ClickHouseClient clickhouse;
    private final SubjectHasher hasher;
    private final NoticePurger notices;
    private final SubjectTypes types;
    private final EventFieldResolver fields;
    private final AuditLog audit;

    public SubjectRightsController(ClickHouseClient clickhouse, SubjectHasher hasher,
                                   NoticePurger notices, SubjectTypes types,
                                   EventFieldResolver fields, AuditLog audit) {
        this.clickhouse = clickhouse;
        this.hasher = hasher;
        this.notices = notices;
        this.types = types;
        this.fields = fields;
        this.audit = audit;
    }

    private ResponseEntity<Map<String, Object>> badType(String type) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "不支持的主体类型:" + type, "allowed", types.all()));
    }

    /** 导出该主体的全部数据。 */
    @GetMapping("/{type}/{value}/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> export(@PathVariable String type,
                                                      @PathVariable String value,
                                                      Authentication auth,
                                                      HttpServletRequest http) {
        String canonical = types.canonical(type);
        if (canonical == null) {
            return badType(type);
        }
        String field = types.column(canonical);
        if (!clickhouse.configured()) {
            return ResponseEntity.status(503).body(Map.of("error", "未配置 ClickHouse 凭据"));
        }

        try {
            // 有的主体字段是物理列,有的在 attrs 里 —— 两者的 WHERE 写法不同
            String where = fields.expression(field);
            // 事件明细里存的可能是 HMAC 值,按同样的方式算一次再匹配
            String stored = hasher.storedValue(field, value);
            Map<String, String> params = Map.of("v", stored, "raw", value);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subject", Map.of("type", type, "value", value));
            body.put("events", clickhouse.query(
                    "SELECT event_time, event_name, page, uri_stem, method, status, "
                            + "geo_province, geo_city FROM nebula.events "
                            + "WHERE " + where + " = {v:String} "
                            + "ORDER BY event_time LIMIT 10000", params));
            body.put("notices", clickhouse.query(
                    "SELECT notice_time, strategy_name, scene_name, decision, risk_score, "
                            + "remark, variable_values FROM nebula.notices "
                            + "WHERE subject_key = {raw:String} "
                            + "ORDER BY notice_time LIMIT 10000", params));
            body.put("note", "事件明细中的个人标识以 HMAC 存储,此处按同一密钥换算后匹配。"
                    + "导出不含审计记录 —— 那是对本主体数据的操作记录,不是本主体的个人信息。");

            audited(auth, http, "privacy_export", type, value, true);
            return ResponseEntity.ok(body);
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            audited(auth, http, "privacy_export", type, value, false);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除该主体的全部数据。**不可撤销。** */
    @DeleteMapping("/{type}/{value}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> erase(@PathVariable String type,
                                                     @PathVariable String value,
                                                     Authentication auth,
                                                     HttpServletRequest http) {
        String canonical = types.canonical(type);
        if (canonical == null) {
            return badType(type);
        }
        String field = types.column(canonical);
        if (!clickhouse.configured()) {
            return ResponseEntity.status(503).body(Map.of("error", "未配置 ClickHouse 凭据"));
        }

        try {
            String where = fields.expression(field);
            String stored = hasher.storedValue(field, value);
            Map<String, String> params = Map.of("v", stored, "raw", value);
            // ClickHouse 的 DELETE 是异步 mutation:提交后立即返回,后台执行。
            // 因此接口不能承诺「返回时数据已消失」—— 响应里写清楚这一点,
            // 否则合规报告会引用一个比实际更强的保证。
            clickhouse.mutate("ALTER TABLE nebula.events DELETE WHERE "
                    + where + " = {v:String}", params);
            clickhouse.mutate("ALTER TABLE nebula.notices DELETE WHERE "
                    + "subject_key = {raw:String}", params);

            // 小时聚合表按部分主体字段分组(比如 c_ip),那里也留着这个主体的行。
            // 只有当它确实是聚合表的一个物理列时才删 —— 其余字段在聚合里只以
            // uniqState 草图的形式存在,既不可寻址也不可逆,删不了也不需要删。
            List<String> rollups = new ArrayList<>();
            for (String table : List.of("events_hourly")) {
                if (fields.columnsOf(table).contains(field)) {
                    clickhouse.mutate("ALTER TABLE nebula." + table + " DELETE WHERE "
                            + field + " = {v:String}", params);
                    rollups.add(table);
                }
            }

            int redis = notices.purge(value);

            audited(auth, http, "privacy_erase", type, value, true);
            return ResponseEntity.ok(Map.of(
                    "subject", Map.of("type", type, "value", value),
                    "redis_keys_removed", redis,
                    "rollup_tables_purged", rollups,
                    "note", "事件与告警的删除已提交给 ClickHouse,由后台 mutation 异步执行,"
                            + "不保证返回时已完成。审计记录按设计保留 —— 它是对本主体数据的"
                            + "操作记录,删掉等于销毁问责链条。",
                    "irreversible", true));
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            audited(auth, http, "privacy_erase", type, value, false);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 记审计。
     *
     * <p>存的是<b>掩码后</b>的主体值:要回答的是「谁在什么时候对谁做了什么」,
     * 不需要在一张保留期更长的表里再抄一份原始标识 —— 尤其这张表记录的正是
     * 「某人要求删除自己的数据」这件事。
     */
    private void audited(Authentication auth, HttpServletRequest http,
                         String action, String type, String value, boolean ok) {
        audit.record(auth == null ? "anonymous" : auth.getName(), action, "subject",
                SubjectMasking.mask(type, value),
                Map.of("subject_type", type), http.getRemoteAddr(), ok);
    }

}
