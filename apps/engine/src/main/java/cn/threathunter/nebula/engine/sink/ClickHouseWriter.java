package cn.threathunter.nebula.engine.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ClickHouse 批量写入。
 *
 * <p>用 HTTP 接口而非 JDBC 驱动:采集侧到分析侧的写入是「大批量、低频次、只追加」
 * 的形态,HTTP + JSONEachRow 足够,而且免去一个运行时依赖。引擎的依赖面越小,
 * 私有化部署越省事。
 *
 * <p><b>凭据只从环境变量注入</b>,不接受写在配置文件或代码里 —— 见 SECURITY.md。
 */
public final class ClickHouseWriter implements Serializable, AutoCloseable {

    private static final long serialVersionUID = 1L;

    private final String baseUrl;
    private final String user;
    private final String password;
    private final int timeoutMs;

    public ClickHouseWriter(String baseUrl, String user, String password, int timeoutMs) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "http://127.0.0.1:8123" : baseUrl;
        this.user = user;
        this.password = password;
        this.timeoutMs = timeoutMs <= 0 ? 30_000 : timeoutMs;
    }

    /** 从环境变量构建。缺少凭据时直接失败,不静默降级为匿名访问。 */
    public static ClickHouseWriter fromEnv() {
        String user = System.getenv("CLICKHOUSE_USER");
        String password = System.getenv("CLICKHOUSE_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "缺少 ClickHouse 凭据。请设置环境变量 CLICKHOUSE_USER 与 "
                            + "CLICKHOUSE_PASSWORD(见 deploy/compose/gen-env.sh)");
        }
        String url = System.getenv().getOrDefault("CLICKHOUSE_URL", "http://127.0.0.1:8123");
        return new ClickHouseWriter(url, user, password, 30_000);
    }

    /**
     * 以 JSONEachRow 格式批量插入。
     *
     * @param table 目标表,如 {@code nebula.events}
     * @param rows  每行一个 JSON 对象的字符串
     */
    public void insert(String table, List<String> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String query = "INSERT INTO " + table + " FORMAT JSONEachRow";
        String url = baseUrl + "/?"
                + "user=" + enc(user)
                + "&password=" + enc(password)
                + "&query=" + enc(query)
                // 写入端已按 schema 生成字段,多余字段视为错误而不是静默丢弃
                + "&input_format_skip_unknown_fields=0";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Content-Type", "application/x-ndjson");

        try (OutputStream os = conn.getOutputStream()) {
            for (String row : rows) {
                os.write(row.getBytes(StandardCharsets.UTF_8));
                os.write('\n');
            }
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String body;
            try (var err = conn.getErrorStream()) {
                body = err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("写入 " + table + " 失败,HTTP " + code + ": "
                    + body.lines().findFirst().orElse(""));
        }
        conn.getInputStream().close();
    }

    /** 执行一条查询并返回响应体。用于建表校验与自检,不用于热路径。 */
    public String query(String sql) throws IOException {
        String url = baseUrl + "/?user=" + enc(user) + "&password=" + enc(password);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            try (var err = conn.getErrorStream()) {
                String body = err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("查询失败,HTTP " + code + ": "
                        + body.lines().findFirst().orElse(""));
            }
        }
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // HTTP 连接按请求关闭,无需额外释放
    }
}
