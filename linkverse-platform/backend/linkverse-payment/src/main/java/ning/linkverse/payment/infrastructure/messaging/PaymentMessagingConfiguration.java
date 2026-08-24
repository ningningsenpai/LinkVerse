package ning.linkverse.payment.infrastructure.messaging;

import ning.linkverse.messaging.outbox.OutboxRelay;
import ning.linkverse.payment.domain.PaymentRepository;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * PaymentMessagingConfiguration 声明支付事实拓扑与通用 Outbox Relay。
 *
 * @author ning
 * @date 2026-08-24
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PaymentMessagingConfiguration {

    public static final String EXCHANGE = "linkverse.events";
    public static final String TRADE_QUEUE = "linkverse.trade.payment";
    public static final String ROUTING_KEY = "payment.fact";

    @Bean
    TopicExchange linkVerseEventExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue tradePaymentQueue() {
        return new Queue(TRADE_QUEUE, true);
    }

    @Bean
    Binding tradePaymentBinding(Queue tradePaymentQueue, TopicExchange linkVerseEventExchange) {
        return BindingBuilder.bind(tradePaymentQueue).to(linkVerseEventExchange).with(ROUTING_KEY);
    }

    @Bean
    OutboxRelay paymentOutboxRelay(
            PaymentRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock
    ) {
        return new OutboxRelay(repository, rabbitTemplate, clock);
    }
}
