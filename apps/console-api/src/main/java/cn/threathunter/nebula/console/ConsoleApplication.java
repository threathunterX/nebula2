package cn.threathunter.nebula.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 星云 2.0 控制面。
 *
 * <p>合并了 1.x 的三个服务(apiserver 自研 Netty 框架、nebula_web Tornado、
 * nebula_query_web Flask)。合并的理由不只是简化部署:那三者当年用两种语言实现、
 * 各维护一份领域模型,是模型漂移的直接来源。
 *
 * <p>对外提供两类接口:
 * <ul>
 *   <li><b>管理接口</b> {@code /api/v2/*} —— 策略与变量的增删改查,写操作记审计</li>
 *   <li><b>{@code /checkRisk}</b> —— 业务系统的同步风险查询。<b>延迟敏感</b>,
 *       直接查 Redis 名单,不经过数据库。与 1.x 保持契约兼容。</li>
 * </ul>
 */
@SpringBootApplication
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
