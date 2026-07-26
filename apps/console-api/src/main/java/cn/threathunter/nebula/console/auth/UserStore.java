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

    /**
     * 停用或启用账号。
     *
     * <p>停用而不是删除:账号名会出现在审计日志的 {@code actor} 列里,删掉行会让
     * 那些记录指向一个查不到的人。而追查「当时是谁做的」这件事,通常正发生在这个人
     * 的账号被停用之后。
     *
     * <p>{@code UserDetailsService} 已经过滤 {@code enabled},置位即刻生效 ——
     * Basic 认证不签发会话,不存在「已登录的会话还能继续用」的窗口。
     *
     * @return 是否确实改到了一行
     */
    public boolean setEnabled(String username, boolean enabled) {
        return jdbc.update("UPDATE users SET enabled = ? WHERE username = ? AND enabled <> ?",
                enabled, username, enabled) > 0;
    }

    /**
     * 重置口令,返回新的明文。
     *
     * <p>不接受调用方指定口令 —— 那意味着它会出现在请求体、反向代理日志、shell 历史
     * 和 CI 变量里。与建账号时同一条理由。
     */
    public String resetPassword(String username) {
        String password = randomPassword();
        int n = jdbc.update("UPDATE users SET password_hash = ? WHERE username = ?",
                encoder.encode(password), username);
        if (n == 0) {
            throw new IllegalArgumentException("账号不存在: " + username);
        }
        return password;
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
