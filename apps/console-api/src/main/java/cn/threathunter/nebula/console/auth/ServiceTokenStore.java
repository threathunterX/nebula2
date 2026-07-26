package cn.threathunter.nebula.console.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 服务令牌 —— 业务系统调用 {@code /checkRisk} 使用,与人类账号分开。
 *
 * <p><b>只存哈希,不存原文。</b>令牌一旦签发就无法再读出,丢失只能重新签发。
 * 这与「令牌明文写在配置文件里」的做法形成对比:那样的令牌泄露后既无从察觉,
 * 也无法轮换。
 *
 * <p>令牌用 SHA-256 而非 Argon2:它是高熵随机串(不是人选的口令),不存在被
 * 字典攻击的问题,而 {@code /checkRisk} 是延迟敏感路径,Argon2 的故意慢速在这里
 * 是负担而非收益。
 */
@Repository
public class ServiceTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record ServiceToken(String tokenId, List<String> scopes,
                               List<String> allowedCidrs, boolean enabled) {
    }

    private final JdbcTemplate jdbc;

    public ServiceTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 令牌形态 {@code <tokenId>.<secret>},便于按 id 定位而无需全表扫哈希。 */
    public record Issued(String tokenId, String plaintext) {
    }

    public Issued issue(String description, List<String> scopes, List<String> allowedCidrs) {
        String tokenId = "svc_" + randomString(8);
        String secret = randomString(32);
        jdbc.update(
                "INSERT INTO service_tokens "
                        + "(token_id, token_hash, description, scopes, allowed_cidrs) "
                        + "VALUES (?, ?, ?, ?::text[], ?::text[])",
                tokenId, sha256(secret), description,
                "{" + String.join(",", scopes) + "}",
                "{" + String.join(",", allowedCidrs) + "}");
        return new Issued(tokenId, tokenId + "." + secret);
    }

    /**
     * 校验令牌。
     *
     * <p>返回空表示校验失败 —— 不区分「令牌不存在」「已停用」「已过期」「密文不符」,
     * 避免通过错误信息探测有效的 tokenId。
     */
    public Optional<ServiceToken> verify(String presented) {
        if (presented == null || !presented.contains(".")) {
            return Optional.empty();
        }
        int dot = presented.indexOf('.');
        String tokenId = presented.substring(0, dot);
        String secret = presented.substring(dot + 1);

        List<Object[]> rows = jdbc.query(
                "SELECT token_hash, scopes, allowed_cidrs, enabled, expires_at "
                        + "FROM service_tokens WHERE token_id = ?",
                (rs, i) -> {
                    List<String> scopes = arrayOf(rs.getArray("scopes"));
                    List<String> cidrs = arrayOf(rs.getArray("allowed_cidrs"));
                    return new Object[]{rs.getString("token_hash"), scopes, cidrs,
                            rs.getBoolean("enabled"), rs.getTimestamp("expires_at")};
                }, tokenId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        if (!Boolean.TRUE.equals(r[3])) {
            return Optional.empty();
        }
        java.sql.Timestamp exp = (java.sql.Timestamp) r[4];
        if (exp != null && exp.toInstant().isBefore(java.time.Instant.now())) {
            return Optional.empty();
        }
        // 定长比较,避免按字节短路带来的时序侧信道
        if (!MessageDigest.isEqual(
                sha256(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(r[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        jdbc.update("UPDATE service_tokens SET last_used_at = now() WHERE token_id = ?", tokenId);

        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) r[1];
        @SuppressWarnings("unchecked")
        List<String> cidrs = (List<String>) r[2];
        return Optional.of(new ServiceToken(tokenId, scopes, cidrs, true));
    }

    private static List<String> arrayOf(java.sql.Array arr) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String randomString(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
