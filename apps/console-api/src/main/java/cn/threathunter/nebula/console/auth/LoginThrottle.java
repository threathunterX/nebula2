package cn.threathunter.nebula.console.auth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 登录失败计数与锁定。
 *
 * <p>Argon2id 让<b>离线</b>爆破变得昂贵,但对<b>在线</b>爆破没有任何帮助 —— 后者只受限于
 * 网络带宽。认证做完了却没有这一层,等于把口令强度当成唯一防线。
 *
 * <h2>为什么按账号 + 来源 IP 两个维度分别计数</h2>
 *
 * 只按账号计:攻击者换 IP 继续撞同一个账号,计数照样累积 —— 这个维度是有效的,
 * 但它同时意味着<b>任何人都能锁死别人的账号</b>,只要故意输错几次。
 *
 * <p>只按 IP 计:攻击者从同一台机器撞不同账号时能拦住,但代理池一换就绕过。
 *
 * <p>两个维度同时用,并且<b>都只锁定「这个组合」而不是账号本身</b>:同一个 IP 对同一个
 * 账号失败 N 次后,该组合被锁;换 IP 要重新累积,换账号也要重新累积。真实用户从自己
 * 常用的机器登录不会受别人影响。
 *
 * <h2>锁定期内即便口令正确也拒绝</h2>
 *
 * 否则攻击者可以用「是否立刻返回成功」判断某次尝试是否猜中,锁定就退化成延迟而已。
 */
@Component
public class LoginThrottle {

    /** 单个「IP + 账号」组合允许的连续失败次数。 */
    private final int maxFailures;
    /** 达到上限后的锁定时长。 */
    private final Duration lockout;
    /** 计数窗口 —— 失败之间隔得足够久就不该累积。 */
    private final Duration window;

    private final JedisPool pool;

    public LoginThrottle(
            @Value("${nebula.redis.host:127.0.0.1}") String host,
            @Value("${nebula.redis.port:6379}") int port,
            @Value("${nebula.redis.password}") String password,
            @Value("${nebula.auth.max-login-failures:5}") int maxFailures,
            @Value("${nebula.auth.lockout-seconds:900}") long lockoutSeconds,
            @Value("${nebula.auth.failure-window-seconds:900}") long windowSeconds) {
        this.maxFailures = maxFailures;
        this.lockout = Duration.ofSeconds(lockoutSeconds);
        this.window = Duration.ofSeconds(windowSeconds);
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(8);
        this.pool = new JedisPool(cfg, host, port, 2000, password);
    }

    private static String failKey(String username, String ip) {
        return "nebula:login:fail:" + ip + "|" + username;
    }

    private static String lockKey(String username, String ip) {
        return "nebula:login:lock:" + ip + "|" + username;
    }

    /** 该组合当前是否处于锁定期。 */
    public boolean locked(String username, String ip) {
        try (Jedis j = pool.getResource()) {
            return j.exists(lockKey(username, ip));
        } catch (RuntimeException e) {
            // Redis 不可用时不锁人 —— 否则一次缓存故障就把所有人挡在门外。
            // 这是刻意的:可用性优先于这一层的防护,因为口令本身仍然要验。
            return false;
        }
    }

    /** 记一次失败;达到上限则加锁。返回剩余可尝试次数,0 表示已锁定。 */
    public int recordFailure(String username, String ip) {
        try (Jedis j = pool.getResource()) {
            String k = failKey(username, ip);
            long n = j.incr(k);
            if (n == 1) {
                j.expire(k, window.toSeconds());
            }
            if (n >= maxFailures) {
                j.setex(lockKey(username, ip), lockout.toSeconds(), "1");
                j.del(k);
                return 0;
            }
            return (int) (maxFailures - n);
        } catch (RuntimeException e) {
            return maxFailures;
        }
    }

    /** 登录成功,清掉该组合的失败计数。 */
    public void recordSuccess(String username, String ip) {
        try (Jedis j = pool.getResource()) {
            j.del(failKey(username, ip));
        } catch (RuntimeException e) {
            // 清不掉只会让计数多留一会儿,不影响正确性
        }
    }

    public int maxFailures() {
        return maxFailures;
    }

    public Duration lockout() {
        return lockout;
    }
}
