package cn.threathunter.nebula.console.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

/**
 * 账号存取。
 *
 * <p><b>零默认口令</b>:首次启动时若无任何账号,生成一个随机口令的管理员并
 * 只打印一次。既不内置 admin/admin,也不从配置文件读口令 —— 配置文件会被提交、
 * 会被备份、会被复制到测试环境。
 */
@Repository
public class UserStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record User(String username, String passwordHash, String displayName,
                       List<String> roles, boolean enabled) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserStore(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public Optional<User> find(String username) {
        List<User> rows = jdbc.query(
                "SELECT username, password_hash, display_name, roles, enabled "
                        + "FROM users WHERE username = ?",
                (rs, i) -> {
                    List<String> roles = new ArrayList<>();
                    java.sql.Array arr = rs.getArray("roles");
                    if (arr != null) {
                        for (Object o : (Object[]) arr.getArray()) {
                            roles.add(String.valueOf(o));
                        }
                    }
                    return new User(rs.getString("username"), rs.getString("password_hash"),
                            rs.getString("display_name"), roles, rs.getBoolean("enabled"));
                }, username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 账号清单。<b>不返回 password_hash</b> —— 哈希没有任何展示用途,泄露只有坏处。 */
    public List<Map<String, Object>> list() {
        return jdbc.query(
                "SELECT username, display_name, roles, enabled, created_at "
                        + "FROM users ORDER BY created_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", rs.getString("username"));
                    m.put("display_name", rs.getString("display_name"));
                    List<String> roles = new ArrayList<>();
                    java.sql.Array arr = rs.getArray("roles");
                    if (arr != null) {
                        for (Object o : (Object[]) arr.getArray()) {
                            roles.add(String.valueOf(o));
                        }
                    }
                    m.put("roles", roles);
                    m.put("enabled", rs.getBoolean("enabled"));
                    m.put("created_at", String.valueOf(rs.getTimestamp("created_at")));
                    return m;
                });
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    public void create(String username, String rawPassword, String displayName,
                       List<String> roles) {
        jdbc.update(
                "INSERT INTO users (username, password_hash, display_name, roles) "
                        + "VALUES (?, ?, ?, ?::text[])",
                username, encoder.encode(rawPassword), displayName,
                "{" + String.join(",", roles) + "}");
    }

    /** 生成一个高熵随机口令。仅用于首次初始化,生成后只打印一次。 */
    public static String randomPassword() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
