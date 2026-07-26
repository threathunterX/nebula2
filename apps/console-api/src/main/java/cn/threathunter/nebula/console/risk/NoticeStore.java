package cn.threathunter.nebula.console.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;

/**
 * 风险名单的读写。
 *
 * <p>名单存 Redis 而非数据库:{@code /checkRisk} 是业务系统在登录、下单、支付
 * 这类关键动作前调用的<b>同步</b>接口,延迟直接影响用户体验。名单的失效由 Redis
 * 的 TTL 自动完成,不需要清理任务。
 *
 * <p>键的形态:{@code nebula:notice:{checkType}:{key}},值为告警 JSON 列表。
 * 与引擎写入侧约定一致。
 */
@Component
public class NoticeStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JedisPool pool;
    private final String prefix;

    public NoticeStore(@Value("${nebula.redis.host}") String host,
                       @Value("${nebula.redis.port}") int port,
                       @Value("${nebula.redis.password}") String password,
                       @Value("${nebula.notice-key-prefix}") String prefix) {
        if (password == null || password.isBlank()) {
            // 零默认口令:缺少凭据时启动即失败,不静默连接无密码实例
            throw new IllegalStateException(
                    "缺少 Redis 口令。请设置 REDIS_PASSWORD(见 deploy/compose/gen-env.sh)");
        }
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(32);
        cfg.setMaxIdle(8);
        this.pool = new JedisPool(cfg, host, port, 2000, password);
        this.prefix = prefix;
    }

    private String keyOf(String checkType, String value) {
        return prefix + checkType + ":" + value;
    }

    /** 写入一条名单。ttlSeconds 到期后自动失效。 */
    public void put(String checkType, String value, Map<String, Object> notice, int ttlSeconds) {
        try (Jedis j = pool.getResource()) {
            String key = keyOf(checkType, value);
            try {
                j.rpush(key, MAPPER.writeValueAsString(notice));
            } catch (Exception e) {
                throw new IllegalStateException("序列化名单失败", e);
            }
            j.expire(key, ttlSeconds);
        }
    }

    /** 查询某主体当前命中的全部名单。未命中返回空列表。 */
    public List<Map<String, Object>> get(String checkType, String value) {
        try (Jedis j = pool.getResource()) {
            List<String> rows = j.lrange(keyOf(checkType, value), 0, -1);
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (String r : rows) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = MAPPER.readValue(r, Map.class);
                    out.add(m);
                } catch (Exception ignored) {
                    // 单条解析失败不应让整次查询失败 —— 风控查询宁可少一条也不能挂
                }
            }
            return out;
        }
    }

    public boolean ping() {
        try (Jedis j = pool.getResource()) {
            return "PONG".equalsIgnoreCase(j.ping());
        }
    }

    @PreDestroy
    public void close() {
        pool.close();
    }
}
