package cn.threathunter.nebula.console.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final Set<String> VALID_STATUS =
            Set.of("inedit", "test", "online", "outline");

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

    public record SaveResult(boolean ok, String error, int version) {
    }

    /**
     * 写入策略定义(新建或更新),并把改动后的完整快照记入修订历史。
     *
     * <p><b>乐观并发</b>:调用方必须带上它读到的 {@code expectedVersion}。两个人
     * 同时编辑同一条策略时,后提交的那个会失败而不是静默覆盖 —— 风控策略被无声
     * 覆盖的代价是「昨天调好的阈值今天没了,而且没人知道」。新建时传 0。
     *
     * <p>定义、历史、版本号三者必须同时成功,故整体在一个事务里。
     */
    @Transactional
    public SaveResult saveStrategy(String name, JsonNode definition, String actor,
                                   String changeNote, int expectedVersion) {
        String status = definition.hasNonNull("status")
                ? definition.get("status").asText() : "inedit";
        String category = definition.hasNonNull("category")
                ? definition.get("category").asText() : "OTHER";
        String visibleName = definition.hasNonNull("visible_name")
                ? definition.get("visible_name").asText() : name;
        int score = definition.hasNonNull("score") ? definition.get("score").asInt() : 0;

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = definition.get("tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode t : tagsNode) {
                tags.add(t.asText());
            }
        }
        String tagsLiteral = "{" + String.join(",", tags) + "}";
        String json = definition.toString();

        List<Integer> current = jdbc.query(
                "SELECT version FROM strategies WHERE name = ?", (rs, i) -> rs.getInt(1), name);
        int newVersion;
        if (current.isEmpty()) {
            if (expectedVersion != 0) {
                return new SaveResult(false,
                        "策略不存在;新建时 expected_version 应为 0", 0);
            }
            newVersion = 1;
            jdbc.update(
                    "INSERT INTO strategies (name, visible_name, category, status, score, "
                            + "tags, definition, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?::text[], ?::jsonb, 1)",
                    name, visibleName, category, status, score, tagsLiteral, json);
        } else {
            int actual = current.get(0);
            if (expectedVersion != actual) {
                return new SaveResult(false,
                        "版本冲突:你基于 v" + expectedVersion + " 修改,当前已是 v" + actual
                                + "。请重新拉取后再提交", actual);
            }
            newVersion = actual + 1;
            jdbc.update(
                    "UPDATE strategies SET visible_name = ?, category = ?, status = ?, "
                            + "score = ?, tags = ?::text[], definition = ?::jsonb, "
                            + "version = ?, updated_at = now() WHERE name = ?",
                    visibleName, category, status, score, tagsLiteral, json, newVersion, name);
        }

        jdbc.update(
                "INSERT INTO strategy_revisions "
                        + "(strategy_name, version, definition, status, changed_by, change_note) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?, ?)",
                name, newVersion, json, status, actor, changeNote == null ? "" : changeNote);
        bumpVersion();
        return new SaveResult(true, null, newVersion);
    }

    /** 某条策略的修订历史,新的在前。 */
    public List<Map<String, Object>> revisions(String name, int limit) {
        return jdbc.query(
                "SELECT version, status, changed_by, change_note, changed_at "
                        + "FROM strategy_revisions WHERE strategy_name = ? "
                        + "ORDER BY version DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", rs.getInt("version"));
                    m.put("status", rs.getString("status"));
                    m.put("changed_by", rs.getString("changed_by"));
                    m.put("change_note", rs.getString("change_note"));
                    m.put("changed_at", String.valueOf(rs.getTimestamp("changed_at")));
                    return m;
                }, name, limit);
    }

    public Optional<JsonNode> revision(String name, int version) {
        List<String> rows = jdbc.query(
                "SELECT definition::text FROM strategy_revisions "
                        + "WHERE strategy_name = ? AND version = ?",
                (rs, i) -> rs.getString(1), name, version);
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

    /**
     * 每个事件有哪些字段(含继承来的)。用于校验策略里 counter 的引用。
     *
     * <p>字段在 {@code properties[]},父事件在 {@code source[]} —— 是数组,一个事件
     * 可以有多个来源。ACCOUNT_LOGIN 的字段大部分来自 HTTP_DYNAMIC,不把父事件的
     * 字段并进来,合法策略会被判成「引用了不存在的字段」而无法保存。
     */
    public Map<String, Set<String>> eventFields() {
        Map<String, Set<String>> own = new LinkedHashMap<>();
        Map<String, List<String>> parents = new LinkedHashMap<>();
        jdbc.query("SELECT name, definition::text FROM event_models", rs -> {
            String name = rs.getString(1);
            Set<String> fields = new LinkedHashSet<>();
            List<String> src = new ArrayList<>();
            try {
                JsonNode def = MAPPER.readTree(rs.getString(2));
                JsonNode props = def.get("properties");
                if (props != null && props.isArray()) {
                    for (JsonNode f : props) {
                        if (f.hasNonNull("name")) {
                            fields.add(f.get("name").asText());
                        }
                    }
                }
                JsonNode sources = def.get("source");
                if (sources != null && sources.isArray()) {
                    for (JsonNode o : sources) {
                        if (o.hasNonNull("name")) {
                            src.add(o.get("name").asText());
                        }
                    }
                }
            } catch (Exception e) {
                // 定义解析不了时按「没有字段」处理,由 schema 校验去报结构问题
            }
            own.put(name, fields);
            parents.put(name, src);
        });

        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        for (String name : own.keySet()) {
            resolved.put(name, inherited(name, own, parents, new LinkedHashSet<>()));
        }
        return resolved;
    }

    /** 广度优先并入父事件字段。{@code seen} 同时充当环检测 —— 配置成环不能把服务转死。 */
    private static Set<String> inherited(String name, Map<String, Set<String>> own,
                                         Map<String, List<String>> parents,
                                         Set<String> seen) {
        Set<String> all = new LinkedHashSet<>();
        if (!seen.add(name)) {
            return all;
        }
        all.addAll(own.getOrDefault(name, Set.of()));
        for (String p : parents.getOrDefault(name, List.of())) {
            all.addAll(inherited(p, own, parents, seen));
        }
        return all;
    }

    /** 全部事件定义,用于向引擎下发。 */
    public List<JsonNode> allEventDefinitions() {
        return jdbc.query("SELECT definition::text FROM event_models ORDER BY name",
                (rs, i) -> parse(rs.getString(1)));
    }

    /** 全部变量定义。 */
    public List<JsonNode> allVariableDefinitions() {
        return jdbc.query("SELECT definition::text FROM variables ORDER BY name",
                (rs, i) -> parse(rs.getString(1)));
    }

    /**
     * 按状态筛选的策略定义。
     *
     * <p>状态取值走枚举校验:它会进 SQL 的 IN 列表,而 IN 列表的元素个数不定,
     * 只能拼参数占位符。校验之后拼的是 {@code ?} 的个数,值仍然走绑定参数。
     */
    public List<JsonNode> strategyDefinitionsByStatus(String statusCsv) {
        List<String> wanted = new ArrayList<>();
        for (String s : statusCsv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && VALID_STATUS.contains(t)) {
                wanted.add(t);
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        return jdbc.query(
                "SELECT definition::text FROM strategies WHERE status IN (" + placeholders + ") "
                        + "ORDER BY name",
                (rs, i) -> parse(rs.getString(1)), wanted.toArray());
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
