package cn.threathunter.nebula.console.auth;

import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 首次启动的账号引导。
 *
 * <p>与 {@link SecurityConfig} 分开的原因不只是职责划分:授权规则的测试需要
 * 导入 SecurityConfig,而 {@code @WebMvcTest} 这类切片测试<b>会执行</b>
 * ApplicationRunner。两者放在一起时,每跑一次授权测试就会走一遍建号流程,并把
 * 一个随机口令打进测试输出 —— 那行日志出现在 CI 日志里,看起来和真的凭据泄露
 * 没有区别。
 */
@Configuration
public class BootstrapConfig {

    /**
     * 首次启动初始化。
     *
     * <p><b>零默认口令</b>:不内置 admin/admin,也不从配置文件读口令 —— 配置文件
     * 会被提交、备份、复制到测试环境。改为生成随机口令并<b>只打印一次</b>,
     * 运维必须当场记录。
     */
    @Bean
    public ApplicationRunner bootstrapAdmin(UserStore store) {
        return args -> {
            if (store.count() > 0) {
                return;
            }
            String password = UserStore.randomPassword();
            store.create("admin", password, "初始管理员", List.of("ADMIN"));
            System.out.println();
            System.out.println("=".repeat(72));
            System.out.println("  已创建初始管理员账号。此口令只显示这一次,请立即记录并尽快更换。");
            System.out.println();
            System.out.println("    用户名: admin");
            System.out.println("    口令:   " + password);
            System.out.println();
            System.out.println("=".repeat(72));
            System.out.println();
        };
    }
}
