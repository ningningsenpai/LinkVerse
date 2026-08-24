package ning.linkverse.payment.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * PaymentRuntimeConfiguration 提供可被测试替换的统一 UTC 时钟。
 *
 * @author ning
 * @date 2026-08-24
 */
@Configuration(proxyBeanMethods = false)
public class PaymentRuntimeConfiguration {

    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }
}
