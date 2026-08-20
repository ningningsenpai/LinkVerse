package ning.linkverse.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * IdentityApplication 启动 LinkVerse 身份服务及其认证配置。
 *
 * @author ning
 * @date 2026-08-19
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
