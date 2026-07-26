package cn.threathunter.nebula.engine.sink;

import cn.threathunter.nebula.engine.rule.StrategyEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * 把告警写入 Redis 名单,供 {@code /checkRisk} 同步查询。
 *
 * <p>键的形态 {@code nebula:notice:{checkType}:{key}},TTL 取策略的名单有效期 ——
 * 名单到期自动失效,不需要清理任务。
 *
 * <p>直接用 RESP 协议而非引入 Jedis:只需要 RPUSH 与 EXPIRE 两个命令,为此多一个
 * 运行时依赖不划算。引擎的依赖面越小,私有化部署越省事。
 */
public final class RedisNoticeSink extends RichSinkFunction<StrategyEngine.Notice> {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final String password;
    private final String prefix;

    private transient Socket socket;
    private transient OutputStream out;
    private transient InputStream in;

    public RedisNoticeSink(String host, int port, String password, String prefix) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.prefix = prefix == null || prefix.isBlank() ? "nebula:notice:" : prefix;
    }

    @Override
    public void open(OpenContext ctx) throws Exception {
        connect();
    }

    private void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = socket.getInputStream();
        if (password != null && !password.isBlank()) {
            command("AUTH", password);
        }
    }

    @Override
    public void invoke(StrategyEngine.Notice n, Context ctx) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy_name", n.strategyName());
        payload.put("scene_name", n.sceneName());
        payload.put("decision", n.decision());
        payload.put("risk_score", n.riskScore());
        payload.put("remark", n.remark() == null ? "" : n.remark());
        payload.put("test", n.test());
        payload.put("timestamp", n.timestamp());

        String key = prefix + n.checkType() + ":" + n.key();
        int ttl = (int) Math.max(1, (n.expire() - n.timestamp()) / 1000);
        command("RPUSH", key, MAPPER.writeValueAsString(payload));
        command("EXPIRE", key, String.valueOf(ttl));
    }

    /** 发送一条 RESP 命令并读取响应。响应为错误时抛出,不静默忽略。 */
    private void command(String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        int c = in.read();
        if (c < 0) {
            throw new IOException("Redis 连接已关闭");
        }
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') {
            if (ch != '\r') {
                line.append((char) ch);
            }
        }
        if (c == '-') {
            throw new IOException("Redis 返回错误: " + line);
        }
    }

    @Override
    public void close() throws Exception {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
