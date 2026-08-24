package ning.linkverse.trade.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * TradeTimeConfiguration 提供可在测试中替换的 UTC 时钟。
 *
 * @author ning
 * @date 2026-08-24
 */
@Configuration(proxyBeanMethods = false)
public class TradeTimeConfiguration {

    @Bean
    Clock tradeClock() {
        return Clock.systemUTC();
    }
}
