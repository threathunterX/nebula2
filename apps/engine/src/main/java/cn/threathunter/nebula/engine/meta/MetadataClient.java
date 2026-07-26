package cn.threathunter.nebula.engine.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 从控制面拉取元数据。
 *
 * <p>此前控制面把策略写进 PostgreSQL,引擎从本地 {@code seeds/} 目录加载:<b>同一份
 * 领域模型有两个事实来源</b>。运营在控制面改完策略,引擎毫无察觉,而两边的分歧不会
 * 有任何报错。这正是 1.x 走过的路 —— Python 侧的 {@code nebula_meta} 与 Java 侧的
 * {@code com.threathunter.variable} 各写一份领域模型,逐渐分歧到谁也说不清哪个算数。
 *
 * <p>数据库是唯一事实来源;{@code seeds/} 退回它本来的角色:首次导入的种子数据。
 *
 * <p>凭据只从环境变量取。命令行参数在进程列表里对同机所有用户可见 —— 一个
 * {@code ps aux} 就能拿到能读全部策略的令牌。
 */
public final class MetadataClient implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final int timeoutMs;

    public MetadataClient(String baseUrl, String token, int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.timeoutMs = timeoutMs <= 0 ? 10_000 : timeoutMs;
    }

    /** 缺少令牌时直接失败,不静默降级为匿名拉取(那会拿到 401 然后误判为「没有策略」)。 */
    public static MetadataClient fromEnv(String baseUrl) {
        String token = System.getenv("NEBULA_CONSOLE_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 NEBULA_CONSOLE_TOKEN(需要 metadata:read 作用域的服务令牌)。"
                            + "签发方式见 apps/console-api/README.md");
        }
        return new MetadataClient(baseUrl, token, 10_000);
    }

    /** 元数据快照。version 用于判断是否需要重新加载。 */
    public record Bundle(long version,
                         List<Map<String, Object>> events,
                         List<Map<String, Object>> variables,
                         List<Map<String, Object>> strategies) implements Serializable {
    }

    public long version() throws IOException {
        Map<String, Object> m = getJson("/api/v2/metadata/version");
        Object v = m.get("version");
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    public Bundle bundle() throws IOException {
        Map<String, Object> m = getJson("/api/v2/metadata/bundle");
        Object v = m.get("version");
        return new Bundle(
                v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v)),
                listOf(m.get("events")),
                listOf(m.get("variables")),
                listOf(m.get("strategies")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private Map<String, Object> getJson(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL()
                .openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code == 401 || code == 403) {
            throw new IOException("控制面拒绝了引擎的令牌(HTTP " + code
                    + ")。令牌需要 metadata:read 作用域,且来源 IP 要在允许网段内");
        }
        if (code < 200 || code >= 300) {
            throw new IOException("拉取元数据失败,HTTP " + code + " " + baseUrl + path);
        }
        try (var in = conn.getInputStream()) {
            return MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {
                    });
        }
    }
}
