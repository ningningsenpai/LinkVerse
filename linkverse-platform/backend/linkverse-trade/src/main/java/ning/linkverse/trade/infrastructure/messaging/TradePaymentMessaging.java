package ning.linkverse.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.trade.application.TradeBusinessMetrics;
import ning.linkverse.trade.application.closing.OrderClosingService;
import ning.linkverse.trade.application.payment.PaymentEventService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
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
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(TradeSeckillMessaging.PARKING_EXCHANGE)
                .deadLetterRoutingKey(QUEUE)
                .build();
    }

    @Bean
    Binding tradePaymentBinding(
            Queue tradePaymentQueue,
            @Qualifier("linkVerseEventExchange") TopicExchange linkVerseEventExchange
    ) {
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
        private final TradeBusinessMetrics metrics;

        @Autowired
        public Listener(
                ObjectMapper objectMapper,
                PaymentEventService eventService,
                OrderClosingService closingService,
                TradeBusinessMetrics metrics
        ) {
            this.objectMapper = objectMapper;
            this.eventService = eventService;
            this.closingService = closingService;
            this.metrics = metrics;
        }

        public Listener(
                ObjectMapper objectMapper,
                PaymentEventService eventService,
                OrderClosingService closingService
        ) {
            this(objectMapper, eventService, closingService, null);
        }

        @RabbitListener(
                queues = QUEUE,
                autoStartup = "${linkverse.trade.payment-events-enabled:true}"
        )
        public void consume(byte[] rawBody) throws Exception {
            long started = System.nanoTime();
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = required(root, "event_type");
            String eventId = required(root, "event_id");
            JsonNode payload = root.path("payload");
            String orderNo = required(payload, "order_no");
            String intentNo = payload.path("intent_no").asText();
            try (MDC.MDCCloseable ignoredEvent = MDC.putCloseable("event_id", eventId);
                 MDC.MDCCloseable ignoredOrder = MDC.putCloseable("order_no", orderNo)) {
                if (!intentNo.isBlank()) {
                    MDC.put("intent_no", intentNo);
                }
                try {
                    if ("PaymentSucceeded".equals(eventType)) {
                        eventService.acceptSucceeded(
                                eventId,
                                required(payload, "intent_no"),
                                orderNo,
                                new BigDecimal(required(payload, "amount")),
                                required(payload, "currency"),
                                Instant.parse(required(root, "occurred_at"))
                        );
                    } else if ("PaymentRefunded".equals(eventType)) {
                        eventService.acceptRefunded(
                                eventId,
                                orderNo,
                                required(payload, "reason_code"),
                                Instant.parse(required(root, "occurred_at"))
                        );
                    } else if ("PaymentClosed".equals(eventType)) {
                        if (!closingService.reconcile(orderNo)) {
                            throw new IllegalStateException("支付关闭事件尚未完成订单收敛");
                        }
                        eventService.recordClosed(eventId, required(root, "occurred_at"));
                    }
                    consumeMetric(eventType, "success", started);
                } catch (Exception exception) {
                    consumeMetric(eventType, "failure", started);
                    throw exception;
                } finally {
                    if (!intentNo.isBlank()) {
                        MDC.remove("intent_no");
                    }
                }
            }
        }

        private void consumeMetric(String eventType, String result, long started) {
            if (metrics != null) {
                String stream = switch (eventType) {
                    case "PaymentSucceeded" -> "payment_succeeded";
                    case "PaymentClosed" -> "payment_closed";
                    case "PaymentRefunded" -> "payment_refunded";
                    default -> "payment_unknown";
                };
                metrics.consume(stream, result, System.nanoTime() - started);
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
