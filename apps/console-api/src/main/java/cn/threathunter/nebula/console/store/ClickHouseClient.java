package cn.threathunter.nebula.console.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ClickHouse 只读查询客户端。
 *
 * <p>用 HTTP 接口而非 JDBC 驱动,理由与引擎侧的写入端一致:少一个运行时依赖,
 * 私有化部署少一份要审计的东西。
 *
 * <p><b>SQL 一律参数化。</b>查询条件来自 HTTP 请求,拼字符串就是注入。ClickHouse
 * 的 HTTP 接口支持 {@code {name:Type}} 占位符配合 {@code param_name=} 传值,值在
 * 服务端按声明类型解析,永远不会被当作 SQL 片段。本类<b>不提供</b>任何拼接 SQL 的
 * 入口,调用方想拼也拼不了。
 */
@Component
public class ClickHouseClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String user;
    private final String password;

    public ClickHouseClient(
            @Value("${nebula.clickhouse.url:http://127.0.0.1:8123}") String baseUrl,
            @Value("${nebula.clickhouse.user:}") String user,
            @Value("${nebula.clickhouse.password:}") String password) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
    }

    /** 未配置凭据时,与告警相关的接口整体不可用,而不是降级为匿名访问。 */
    public boolean configured() {
        return user != null && !user.isBlank() && password != null && !password.isBlank();
    }

    /**
     * 执行只读查询,返回每行一个 Map。
     *
     * @param sql    含 {@code {name:Type}} 占位符的 SQL
     * @param params 占位符取值
     */
    public List<Map<String, Object>> query(String sql, Map<String, String> params)
            throws IOException {
        if (!configured()) {
            throw new IllegalStateException(
                    "未配置 ClickHouse 凭据(NEBULA_CLICKHOUSE_USER / _PASSWORD),告警查询不可用");
        }
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/?user=").append(enc(user))
                .append("&password=").append(enc(password))
                // 只读会话:即便 SQL 被构造错了,也执行不了写操作
                .append("&readonly=1")
                .append("&default_format=JSONEachRow")
                // 单次查询的资源上限。控制面与线上告警写入共用一个 ClickHouse,
                // 一条失控的查询不能把写入拖垮。
                .append("&max_execution_time=15")
                .append("&max_result_rows=10000")
                .append("&result_overflow_mode=throw");
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append("&param_").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }

        HttpURLConnection conn = (HttpURLConnection) URI.create(url.toString()).toURL()
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(20_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sql.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            try (var err = conn.getErrorStream()) {
                String body = err == null ? ""
                        : new String(err.readAllBytes(), StandardCharsets.UTF_8);
                // 只向上抛第一行。ClickHouse 的错误里会带上完整 SQL,而 SQL 里
                // 可能含查询条件(比如被查的手机号)—— 那不该进日志。
                throw new IOException("ClickHouse 查询失败,HTTP " + code + ": "
                        + body.lines().findFirst().orElse(""));
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try (var in = conn.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : body.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(MAPPER.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() {
                }));
            }
        }
        return rows;
    }

    /**
     * 执行一条会改数据的语句(ALTER ... DELETE 等)。
     *
     * <p>与 {@link #query} 分开是刻意的:query 走 {@code readonly=1},即便 SQL 被构造错了
     * 也执行不了写操作。把两者合成一个方法会让那道保护失效。
     *
     * <p>ClickHouse 的 DELETE 是<b>异步 mutation</b>:提交后立即返回,后台执行。
     * 调用方不能假设返回时数据已消失。
     */
    public void mutate(String sql, Map<String, String> params) throws IOException {
        if (!configured()) {
            throw new IllegalStateException("未配置 ClickHouse 凭据");
        }
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/?user=").append(enc(user))
                .append("&password=").append(enc(password))
                .append("&mutations_sync=0");
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append("&param_").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(url.toString()).toURL()
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            try (var err = conn.getErrorStream()) {
                String body = err == null ? ""
                        : new String(err.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("ClickHouse 变更失败,HTTP " + code + ": "
                        + body.lines().findFirst().orElse(""));
            }
        }
        conn.getInputStream().close();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
