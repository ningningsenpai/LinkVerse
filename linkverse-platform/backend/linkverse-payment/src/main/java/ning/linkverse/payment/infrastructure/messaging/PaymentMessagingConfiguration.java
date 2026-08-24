package ning.linkverse.payment.infrastructure.messaging;

import ning.linkverse.messaging.outbox.OutboxRelay;
import ning.linkverse.payment.domain.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
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
    public static final String PARKING_EXCHANGE = EXCHANGE + ".parking";
    public static final String PARKING_QUEUE = "linkverse.payment.parking";

    @Bean
    TopicExchange linkVerseEventExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    TopicExchange linkVerseParkingExchange() {
        return new TopicExchange(PARKING_EXCHANGE, true, false);
    }

    @Bean
    Queue tradePaymentQueue() {
        return QueueBuilder.durable(TRADE_QUEUE)
                .deadLetterExchange(PARKING_EXCHANGE)
                .deadLetterRoutingKey(TRADE_QUEUE)
                .build();
    }

    @Bean
    Binding tradePaymentBinding(
            Queue tradePaymentQueue,
            @Qualifier("linkVerseEventExchange") TopicExchange linkVerseEventExchange
    ) {
        return BindingBuilder.bind(tradePaymentQueue).to(linkVerseEventExchange).with(ROUTING_KEY);
    }

    @Bean
    Queue paymentParkingQueue() {
        return new Queue(PARKING_QUEUE, true);
    }

    @Bean
    Binding paymentParkingBinding(
            Queue paymentParkingQueue,
            @Qualifier("linkVerseParkingExchange") TopicExchange linkVerseParkingExchange
    ) {
        return BindingBuilder.bind(paymentParkingQueue).to(linkVerseParkingExchange).with("#");
    }

    @Bean
    OutboxRelay paymentOutboxRelay(
            PaymentRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        return new OutboxRelay(repository, rabbitTemplate, clock, meterRegistry);
    }
}
