package ning.linkverse.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.trade.application.closing.OrderClosingService;
import ning.linkverse.trade.application.payment.PaymentEventService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TradePaymentMessaging 声明支付事实队列并按事件类型分派幂等消费者。
 *
 * @author ning
 * @date 2026-08-24
 */
@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableScheduling
public class TradePaymentMessaging {

    public static final String EXCHANGE = "linkverse.events";
    public static final String QUEUE = "linkverse.trade.payment";

    @Bean
    TopicExchange linkVerseEventExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue tradePaymentQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    Binding tradePaymentBinding(Queue tradePaymentQueue, TopicExchange linkVerseEventExchange) {
        return BindingBuilder.bind(tradePaymentQueue).to(linkVerseEventExchange).with("payment.fact");
    }

    /**
     * Listener 将 JSON 信封解析与业务事务分离，解析失败交由容器重试策略处理。
     *
     * @author ning
     * @date 2026-08-24
     */
    @Component
    public static class Listener {

        private final ObjectMapper objectMapper;
        private final PaymentEventService eventService;
        private final OrderClosingService closingService;

        public Listener(
                ObjectMapper objectMapper,
                PaymentEventService eventService,
                OrderClosingService closingService
        ) {
            this.objectMapper = objectMapper;
            this.eventService = eventService;
            this.closingService = closingService;
        }

        @RabbitListener(
                queues = QUEUE,
                autoStartup = "${linkverse.trade.payment-events-enabled:true}"
        )
        public void consume(byte[] rawBody) throws Exception {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = required(root, "event_type");
            String eventId = required(root, "event_id");
            JsonNode payload = root.path("payload");
            if ("PaymentSucceeded".equals(eventType)) {
                eventService.acceptSucceeded(
                        eventId,
                        required(payload, "intent_no"),
                        required(payload, "order_no"),
                        new BigDecimal(required(payload, "amount")),
                        required(payload, "currency"),
                        Instant.parse(required(root, "occurred_at"))
                );
            } else if ("PaymentClosed".equals(eventType)) {
                String orderNo = required(payload, "order_no");
                if (!closingService.reconcile(orderNo)) {
                    throw new IllegalStateException("支付关闭事件尚未完成订单收敛");
                }
                eventService.recordClosed(eventId, required(root, "occurred_at"));
            }
        }

        private String required(JsonNode node, String field) {
            String value = node.path(field).asText();
            if (value.isBlank()) {
                throw new IllegalArgumentException("支付事件缺少字段：" + field);
            }
            return value;
        }
    }
}
