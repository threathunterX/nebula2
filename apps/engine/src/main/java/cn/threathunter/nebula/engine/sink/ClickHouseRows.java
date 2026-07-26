package cn.threathunter.nebula.engine.sink;

import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把事件与告警转成 ClickHouse 的 JSONEachRow 行。
 *
 * <p>表结构见 {@code deploy/schema/clickhouse/}。
 */
public final class ClickHouseRows {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ClickHouse 的 DateTime64(3) 接受 'yyyy-MM-dd HH:mm:ss.SSS'(UTC)。 */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /** 已在事件表中单独建列的字段,不再重复放进 attrs。 */
    private static final java.util.Set<String> PROMOTED = java.util.Set.of(
            "c_ip", "uid", "did", "sid", "name", "timestamp", "id",
            "host", "page", "uri_stem", "method", "status", "referer", "useragent",
            "geo_province", "geo_city");

    private ClickHouseRows() {
    }

    private static String str(Map<String, Object> e, String k) {
        Object v = e.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String ts(long millis) {
        return TS.format(Instant.ofEpochMilli(millis));
    }

    /** 事件行。 */
    public static String event(Map<String, Object> e) {
        Object tsObj = e.get("timestamp");
        long millis = tsObj instanceof Number n ? n.longValue() : System.currentTimeMillis();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c_ip", str(e, "c_ip"));
        row.put("uid", str(e, "uid"));
        row.put("did", str(e, "did"));
        row.put("sid", str(e, "sid"));
        row.put("event_name", str(e, "name"));
        row.put("event_time", ts(millis));
        row.put("event_id", str(e, "id"));
        row.put("host", str(e, "host"));
        row.put("page", str(e, "page"));
        row.put("uri_stem", str(e, "uri_stem"));
        row.put("method", str(e, "method"));
        row.put("status", e.get("status") instanceof Number n ? n.intValue() : 0);
        row.put("referer", str(e, "referer"));
        row.put("useragent", str(e, "useragent"));
        row.put("geo_province", str(e, "geo_province"));
        row.put("geo_city", str(e, "geo_city"));

        // 其余业务字段进 attrs —— 17 类事件字段各异,不为每类建表或堆稀疏列
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : e.entrySet()) {
            if (PROMOTED.contains(en.getKey()) || en.getKey().startsWith("__")) {
                continue;
            }
            attrs.put(en.getKey(), en.getValue() == null ? "" : String.valueOf(en.getValue()));
        }
        row.put("attrs", attrs);
        return write(row);
    }

    /** 告警行。 */
    public static String notice(StrategyEngine.Notice n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("notice_time", ts(n.timestamp()));
        row.put("subject_key", n.key());
        row.put("check_type", n.checkType());
        row.put("strategy_name", n.strategyName());
        row.put("scene_name", n.sceneName());
        row.put("decision", n.decision());
        row.put("risk_score", n.riskScore());
        row.put("expire_at", ts(n.expire()));
        row.put("tags", n.tags());
        row.put("is_test", n.test() ? 1 : 0);
        row.put("remark", n.remark() == null ? "" : n.remark());
        row.put("geo_province", "");
        row.put("geo_city", "");
        row.put("uri_stem", "");

        // 判定依据摊平成「指标 -> 当前值 op 阈值」,便于在 SQL 里直接读
        Map<String, String> vv = new LinkedHashMap<>();
        n.variableValues().forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                vv.put(k, String.valueOf(m.get("value")) + " "
                        + String.valueOf(m.get("operator")) + " "
                        + String.valueOf(m.get("threshold")));
            } else {
                vv.put(k, String.valueOf(v));
            }
        });
        row.put("variable_values", vv);
        return write(row);
    }

    private static String write(Map<String, Object> row) {
        try {
            return MAPPER.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 ClickHouse 行失败", e);
        }
    }
}
