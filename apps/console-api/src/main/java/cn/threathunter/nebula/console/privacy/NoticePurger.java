package cn.threathunter.nebula.console.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 从 Redis 名单中清除某个主体。
 *
 * <p>名单里的主体标识是<b>明文</b>的 —— 它要被 {@code /checkRisk} 按原值查,
 * 与事件明细的 HMAC 存储不同。所以这里直接用原值构造键。
 */
@Component
public class NoticePurger {

    private final JedisPool pool;
    private final String prefix;
    private final SubjectTypes types;

    public NoticePurger(
            @Value("${nebula.redis.host:127.0.0.1}") String host,
            @Value("${nebula.redis.port:6379}") int port,
            @Value("${nebula.redis.password}") String password,
            @Value("${nebula.notice-key-prefix:nebula:notice:}") String prefix,
            SubjectTypes types) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        this.pool = new JedisPool(cfg, host, port, 2000, password);
        this.prefix = prefix;
        this.types = types;
    }

    /**
     * 删除该主体在各 check_type 下的名单键,返回删除的键数。
     *
     * <p>同一个标识可能同时作为不同 check_type 落名单(比如既是 USER 又出现在
     * OrderID 上),所以按全部类型逐个删,而不是只删请求指定的那一个。
     *
     * <p>按已知类型构造键名,<b>不用 KEYS 或 SCAN 通配</b> —— 前者会阻塞 Redis,
     * 后者在大库上要遍历全部键。主体删除是低频操作,但它不该因为低频就允许
     * 写出会拖垮线上的实现。
     */
    public int purge(String value) {
        int removed = 0;
        try (Jedis j = pool.getResource()) {
            for (String type : types.all()) {
                removed += j.del(prefix + type + ":" + value) > 0 ? 1 : 0;
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("清理 Redis 名单失败: " + e.getMessage(), e);
        }
        return removed;
    }
}
