package cn.threathunter.nebula.console.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 元数据存取。
 *
 * <p>策略与变量以 JSONB 存储 —— 权威结构在 {@code packages/domain-schema/} 的
 * JSON Schema 中,拆成关系表等于把同一套结构维护两遍,那正是 1.x 领域模型漂移的
 * 根源。JSONB + GIN 索引既能按内容检索,又保持单一真相源。
 */
@Repository
public class MetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 策略

    public record StrategySummary(String name, String visibleName, String category,
                                  String status, int score, List<String> tags,
                                  boolean requiresConfig, int version) {
    }

    private static StrategySummary toSummary(ResultSet rs, int i) throws SQLException {
        java.sql.Array arr = rs.getArray("tags");
        List<String> tags = new ArrayList<>();
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) {
                tags.add(String.valueOf(o));
            }
        }
        return new StrategySummary(
                rs.getString("name"), rs.getString("visible_name"), rs.getString("category"),
                rs.getString("status"), rs.getInt("score"), tags,
                rs.getBoolean("requires_config"), rs.getInt("version"));
    }

    public List<StrategySummary> listStrategies(String category, String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT name, visible_name, category, status, score, tags, requires_config, version "
                        + "FROM strategies WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY name LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.query(sql.toString(), MetadataStore::toSummary, args.toArray());
    }

    public Optional<JsonNode> getStrategy(String name) {
        List<String> rows = jdbc.query(
                "SELECT definition::text FROM strategies WHERE name = ?",
                (rs, i) -> rs.getString(1), name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(parse(rows.get(0)));
    }

    /** 更新策略状态。返回受影响行数,0 表示策略不存在。 */
    @Transactional
    public int updateStrategyStatus(String name, String status) {
        int n = jdbc.update(
                "UPDATE strategies SET status = ?, "
                        + "definition = jsonb_set(definition, '{status}', to_jsonb(?::text)), "
                        + "version = version + 1, updated_at = now() WHERE name = ?",
                status, status, name);
        if (n > 0) {
            bumpVersion();
        }
        return n;
    }

    // ---------------------------------------------------------------- 变量

    public record VariableSummary(String name, String module, String dimension,
                                  String status, String sensitivity) {
    }

    public List<VariableSummary> listVariables(String module, String sensitivity, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT name, module, dimension, status, sensitivity FROM variables WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            sql.append(" AND module = ?");
            args.add(module);
        }
        if (sensitivity != null && !sensitivity.isBlank()) {
            sql.append(" AND sensitivity = ?");
            args.add(sensitivity);
        }
        sql.append(" ORDER BY name LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.query(sql.toString(),
                (rs, i) -> new VariableSummary(rs.getString("name"), rs.getString("module"),
                        rs.getString("dimension"), rs.getString("status"),
                        rs.getString("sensitivity")),
                args.toArray());
    }

    public Optional<JsonNode> getVariable(String name) {
        List<String> rows = jdbc.query(
                "SELECT definition::text FROM variables WHERE name = ?",
                (rs, i) -> rs.getString(1), name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(parse(rows.get(0)));
    }

    // ---------------------------------------------------------------- 统计与版本

    public Map<String, Object> stats() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("events", jdbc.queryForObject("SELECT count(*) FROM event_models", Long.class));
        out.put("variables", jdbc.queryForObject("SELECT count(*) FROM variables", Long.class));
        out.put("strategies", jdbc.queryForObject("SELECT count(*) FROM strategies", Long.class));
        out.put("tags", jdbc.queryForObject("SELECT count(*) FROM risk_tags", Long.class));
        out.put("strategies_requiring_config", jdbc.queryForObject(
                "SELECT count(*) FROM strategies WHERE requires_config", Long.class));
        out.put("pii_variables", jdbc.queryForObject(
                "SELECT count(*) FROM variables WHERE sensitivity = 'pii'", Long.class));
        out.put("metadata_version", metadataVersion());
        return out;
    }

    public long metadataVersion() {
        Long v = jdbc.queryForObject("SELECT version FROM metadata_version WHERE id = 1", Long.class);
        return v == null ? 0 : v;
    }

    private void bumpVersion() {
        jdbc.update("UPDATE metadata_version SET version = version + 1, updated_at = now() WHERE id = 1");
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("解析元数据 JSON 失败", e);
        }
    }
}
