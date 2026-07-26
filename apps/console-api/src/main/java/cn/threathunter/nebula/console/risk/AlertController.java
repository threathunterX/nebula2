package cn.threathunter.nebula.console.risk;

import cn.threathunter.nebula.console.audit.AuditLog;
import cn.threathunter.nebula.console.store.ClickHouseClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警查询。
 *
 * <p>引擎产出的告警此前只写进 ClickHouse 和 Redis,没有任何读取入口 —— 系统在
 * 报什么,运营看不到。名单查询({@code /checkRisk})回答的是「这个主体现在有没有
 * 风险」,回答不了「昨天哪条策略在报、报了多少、依据是什么」。
 *
 * <p>全部查询强制带时间范围。ClickHouse 的分区键是 {@code toDate(notice_time)},
 * 不给范围就是全表扫描 —— 一个手滑的请求会把线上写入拖垮。
 */
@RestController
@RequestMapping("/api/v2/alerts")
public class AlertController {

    /** 最长查询跨度。与 notices 表 90 天的 TTL 对齐,查更久也没有数据。 */
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final int MAX_PAGE_SIZE = 500;

    private static final Set<String> DECISIONS = Set.of("accept", "review", "reject");
    /** 排序列白名单。列名不能参数化,只能用白名单 —— 拼接即注入。 */
    private static final Set<String> SORTABLE = Set.of("notice_time", "risk_score");

    private final ClickHouseClient clickhouse;
    private final AuditLog audit;

    public AlertController(ClickHouseClient clickhouse, AuditLog audit) {
        this.clickhouse = clickhouse;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false, name = "check_type") String checkType,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "false", name = "include_test") boolean includeTest,
            @RequestParam(defaultValue = "notice_time") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth,
            HttpServletRequest http) {

        if (!clickhouse.configured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "未配置 ClickHouse 凭据,告警查询不可用",
                    "hint", "设置 NEBULA_CLICKHOUSE_USER 与 NEBULA_CLICKHOUSE_PASSWORD"));
        }

        Instant fromTs;
        Instant toTs;
        try {
            fromTs = Instant.parse(from);
            toTs = Instant.parse(to);
        } catch (DateTimeParseException e) {
            return bad("from / to 需为 ISO-8601 时刻,例如 2026-07-01T00:00:00Z");
        }
        if (!toTs.isAfter(fromTs)) {
            return bad("to 必须晚于 from");
        }
        if (Duration.between(fromTs, toTs).compareTo(MAX_RANGE) > 0) {
            return bad("查询跨度不能超过 " + MAX_RANGE.toDays() + " 天(与告警表的保留期一致)");
        }
        if (decision != null && !DECISIONS.contains(decision)) {
            return bad("decision 取值须为 " + DECISIONS);
        }
        if (!SORTABLE.contains(sort)) {
            return bad("sort 取值须为 " + SORTABLE);
        }
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * pageSize;

        StringBuilder where = new StringBuilder(
                "notice_time >= parseDateTime64BestEffort({from:String}) "
                        + "AND notice_time < parseDateTime64BestEffort({to:String})");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        if (!includeTest) {
            where.append(" AND is_test = 0");
        }
        addEq(where, params, "scene_name", "scene", scene);
        addEq(where, params, "strategy_name", "strategy", strategy);
        addEq(where, params, "decision", "decision", decision);
        addEq(where, params, "check_type", "check_type", checkType);
        addEq(where, params, "subject_key", "subject", subject);

        params.put("limit", String.valueOf(pageSize));
        params.put("offset", String.valueOf(offset));

        boolean full = canSeeFullSubject(auth);
        try {
            List<Map<String, Object>> rows = clickhouse.query(
                    // 不要写 toString(notice_time) AS notice_time —— ClickHouse 会让
                    // 这个别名在 WHERE 里覆盖同名的原列,于是时间比较变成
                    // 「String 与 DateTime64 比大小」直接报错。DateTime64 在
                    // JSONEachRow 里本来就渲染成字符串,不需要转。
                    "SELECT notice_time, subject_key, check_type, "
                            + "strategy_name, scene_name, decision, risk_score, "
                            + "expire_at, tags, is_test, remark, "
                            + "geo_province, geo_city, uri_stem, variable_values "
                            + "FROM nebula.notices WHERE " + where
                            + " ORDER BY " + sort + " DESC "
                            + "LIMIT {limit:UInt32} OFFSET {offset:UInt32}",
                    params);
            List<Map<String, Object>> total = clickhouse.query(
                    "SELECT count() AS n FROM nebula.notices WHERE " + where, params);

            if (!full) {
                for (Map<String, Object> r : rows) {
                    r.put("subject_key", SubjectMasking.mask(
                            String.valueOf(r.get("check_type")),
                            String.valueOf(r.get("subject_key"))));
                }
            }

            // 按主体精确查询是「查某个人」,与浏览告警列表不是一回事,单独记审计。
            //
            // 审计记的是掩码后的值:要能回答「谁在什么时候查了谁」,不需要在审计表里
            // 再存一份原始标识 —— 那等于把 PII 又抄了一遍到一张保留期更长的表里。
            //
            // 这里刻意不吞异常:审计写不进去就不返回数据。查询个人信息却没留下记录,
            // 正是审计最不能缺席的场景。
            if (subject != null && !subject.isBlank()) {
                String masked = SubjectMasking.mask(checkType, subject);
                audit.record(actor(auth), "query_alerts_by_subject", "notice", masked,
                        Map.of("check_type", checkType == null ? "" : checkType,
                                "from", from, "to", to, "matched", String.valueOf(rows.size())),
                        http.getRemoteAddr(), true);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("total", total.isEmpty() ? 0 : total.get(0).get("n"));
            body.put("page", page);
            body.put("size", pageSize);
            body.put("subject_masked", !full);
            body.put("items", rows);
            return ResponseEntity.ok(body);
        } catch (IOException | IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 按策略汇总的趋势,读小时聚合表而不是明细表。
     *
     * <p>明细表按 (scene_name, strategy_name, notice_time) 排序,做跨天的按小时
     * 汇总要扫全部分区;{@code notices_hourly} 由物化视图在写入时增量维护,查它
     * 是常数级的代价。
     */
    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "false", name = "include_test") boolean includeTest) {

        if (!clickhouse.configured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "未配置 ClickHouse 凭据,告警查询不可用"));
        }
        try {
            Instant f = Instant.parse(from);
            Instant t = Instant.parse(to);
            if (!t.isAfter(f)) {
                return bad("to 必须晚于 from");
            }
            if (Duration.between(f, t).compareTo(Duration.ofDays(365)) > 0) {
                return bad("趋势查询跨度不能超过 365 天");
            }
        } catch (DateTimeParseException e) {
            return bad("from / to 需为 ISO-8601 时刻");
        }

        StringBuilder where = new StringBuilder(
                "hour >= parseDateTimeBestEffort({from:String}) "
                        + "AND hour < parseDateTimeBestEffort({to:String})");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        if (!includeTest) {
            where.append(" AND is_test = 0");
        }
        addEq(where, params, "strategy_name", "strategy", strategy);

        try {
            List<Map<String, Object>> rows = clickhouse.query(
                    "SELECT hour, scene_name, strategy_name, decision, "
                            + "countMerge(notice_count) AS notices, "
                            + "uniqMerge(subject_count) AS subjects "
                            + "FROM nebula.notices_hourly WHERE " + where
                            + " GROUP BY hour, scene_name, strategy_name, decision "
                            + "ORDER BY hour", params);
            return ResponseEntity.ok(Map.of("buckets", rows));
        } catch (IOException | IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private static void addEq(StringBuilder where, Map<String, String> params,
                              String column, String param, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        where.append(" AND ").append(column).append(" = {").append(param).append(":String}");
        params.put(param, value);
    }

    /** OPERATOR / ADMIN 看原值,VIEWER 看掩码值。 */
    private static boolean canSeeFullSubject(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_OPERATOR".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
