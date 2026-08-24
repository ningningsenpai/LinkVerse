package ning.linkverse.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.messaging.outbox.OutboxRelay;
import ning.linkverse.trade.application.TradeBusinessMetrics;
import ning.linkverse.trade.application.seckill.SeckillApplicationService;
import ning.linkverse.trade.application.seckill.SeckillMessageService;
import ning.linkverse.trade.application.seckill.SeckillOrderTransactionService;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TradeSeckillMessaging 声明秒杀削峰与结果投影队列，并启用 Trade Outbox Relay。
 *
 * @author ning
 * @date 2026-08-24
 */
@Configuration(proxyBeanMethods = false)
public class TradeSeckillMessaging {

    public static final String REQUEST_QUEUE = "linkverse.trade.seckill.request";
    public static final String RESULT_QUEUE = "linkverse.trade.seckill.result";
    public static final String PARKING_EXCHANGE = TradePaymentMessaging.EXCHANGE + ".parking";
    public static final String PARKING_QUEUE = "linkverse.trade.parking";

    @Bean
    Queue seckillRequestQueue() {
        return QueueBuilder.durable(REQUEST_QUEUE)
                .deadLetterExchange(PARKING_EXCHANGE)
                .deadLetterRoutingKey(REQUEST_QUEUE)
                .build();
    }

    @Bean
    Queue seckillResultQueue() {
        return QueueBuilder.durable(RESULT_QUEUE)
                .deadLetterExchange(PARKING_EXCHANGE)
                .deadLetterRoutingKey(RESULT_QUEUE)
                .build();
    }

    @Bean
    Binding seckillRequestBinding(
            Queue seckillRequestQueue,
            @Qualifier("linkVerseEventExchange") TopicExchange linkVerseEventExchange
    ) {
        return BindingBuilder.bind(seckillRequestQueue).to(linkVerseEventExchange).with("seckill.request");
    }

    @Bean
    Binding seckillResultBinding(
            Queue seckillResultQueue,
            @Qualifier("linkVerseEventExchange") TopicExchange linkVerseEventExchange
    ) {
        return BindingBuilder.bind(seckillResultQueue).to(linkVerseEventExchange).with("seckill.result");
    }

    @Bean
    TopicExchange linkVerseParkingExchange() {
        return new TopicExchange(PARKING_EXCHANGE, true, false);
    }

    @Bean
    Queue tradeParkingQueue() {
        return new Queue(PARKING_QUEUE, true);
    }

    @Bean
    Binding tradeParkingBinding(
            Queue tradeParkingQueue,
            @Qualifier("linkVerseParkingExchange") TopicExchange linkVerseParkingExchange
    ) {
        return BindingBuilder.bind(tradeParkingQueue).to(linkVerseParkingExchange).with("#");
    }

    @Bean
    OutboxRelay tradeOutboxRelay(
            SeckillRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        return new OutboxRelay(repository, rabbitTemplate, clock, meterRegistry);
    }

    /**
     * Listener 将请求建单和结果 Redis 投影分别保持幂等。
     */
    @Component
    public static class Listener {

        private final ObjectMapper objectMapper;
        private final SeckillOrderTransactionService orderService;
        private final SeckillMessageService messageService;
        private final TradeBusinessMetrics metrics;

        @Autowired
        public Listener(
                ObjectMapper objectMapper,
                SeckillOrderTransactionService orderService,
                SeckillMessageService messageService,
                TradeBusinessMetrics metrics
        ) {
            this.objectMapper = objectMapper;
            this.orderService = orderService;
            this.messageService = messageService;
            this.metrics = metrics;
        }

        public Listener(
                ObjectMapper objectMapper,
                SeckillOrderTransactionService orderService,
                SeckillMessageService messageService
        ) {
            this(objectMapper, orderService, messageService, null);
        }

        @RabbitListener(queues = REQUEST_QUEUE, autoStartup = "${linkverse.trade.seckill-events-enabled:true}")
        public void consumeRequest(byte[] rawBody) throws Exception {
            long started = System.nanoTime();
            JsonNode root = objectMapper.readTree(rawBody);
            if (!"SeckillRequested".equals(required(root, "event_type"))) {
                return;
            }
            String eventId = required(root, "event_id");
            String reservationNo = root.path("payload").path("reservation_no").asText();
            try (MDC.MDCCloseable ignoredEvent = MDC.putCloseable("event_id", eventId)) {
                if (!reservationNo.isBlank()) {
                    MDC.put("reservation_no", reservationNo);
                }
                try {
                    try {
                        orderService.consume(eventId);
                    } catch (SeckillOrderTransactionService.SeckillStockConflictException conflict) {
                        orderService.recordStockFailure(conflict);
                    }
                    consumeMetric("seckill_request", "success", started);
                } catch (Exception exception) {
                    consumeMetric("seckill_request", "failure", started);
                    throw exception;
                } finally {
                    if (!reservationNo.isBlank()) {
                        MDC.remove("reservation_no");
                    }
                }
            }
        }

        @RabbitListener(queues = RESULT_QUEUE, autoStartup = "${linkverse.trade.seckill-events-enabled:true}")
        public void consumeResult(byte[] rawBody) throws Exception {
            long started = System.nanoTime();
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = required(root, "event_type");
            if (!"SeckillReservationSucceeded".equals(eventType)
                    && !"SeckillReservationRejected".equals(eventType)) {
                return;
            }
            JsonNode payload = root.path("payload");
            String eventId = required(root, "event_id");
            String reservationNo = required(payload, "reservation_no");
            try (MDC.MDCCloseable ignoredEvent = MDC.putCloseable("event_id", eventId);
                 MDC.MDCCloseable ignoredReservation = MDC.putCloseable("reservation_no", reservationNo)) {
                try {
                    messageService.project(
                            eventId, eventType, reservationNo,
                            Long.parseLong(required(payload, "campaign_id")),
                            Long.parseLong(required(payload, "campaign_version")),
                            new String(rawBody, StandardCharsets.UTF_8));
                    if (metrics != null) {
                        metrics.record("seckill_result",
                                "SeckillReservationSucceeded".equals(eventType) ? "succeeded" : "rejected");
                    }
                    consumeMetric("seckill_result", "success", started);
                } catch (Exception exception) {
                    consumeMetric("seckill_result", "failure", started);
                    throw exception;
                }
            }
        }

        private void consumeMetric(String stream, String result, long started) {
            if (metrics != null) {
                metrics.consume(stream, result, System.nanoTime() - started);
            }
        }

        private String required(JsonNode node, String field) {
            String value = node.path(field).asText();
            if (value.isBlank()) {
                throw new IllegalArgumentException("秒杀事件缺少字段：" + field);
            }
            return value;
        }
    }

    /**
     * Scheduler 负责发布重投、Redis 待发布恢复及稳定态库存对账。
     */
    @Component
    public static class Scheduler {

        private static final Logger LOGGER = LoggerFactory.getLogger(Scheduler.class);

        private final OutboxRelay relay;
        private final SeckillRepository repository;
        private final SeckillApplicationService applicationService;
        private final SeckillRedisStore redisStore;
        private final AtomicLong inventoryDifference = new AtomicLong();

        public Scheduler(
                OutboxRelay tradeOutboxRelay,
                SeckillRepository repository,
                SeckillApplicationService applicationService,
                SeckillRedisStore redisStore,
                MeterRegistry meterRegistry
        ) {
            this.relay = tradeOutboxRelay;
            this.repository = repository;
            this.applicationService = applicationService;
            this.redisStore = redisStore;
            Gauge.builder("linkverse.reconciliation.difference", inventoryDifference, AtomicLong::get)
                    .tag("domain", "trade")
                    .tag("type", "inventory_stock")
                    .register(meterRegistry);
        }

        @Scheduled(fixedDelayString = "${linkverse.trade.seckill-relay-delay:500ms}")
        public void relay() {
            relay.relayOnce(50);
        }

        @Scheduled(fixedDelayString = "${linkverse.trade.seckill-recovery-delay:2s}")
        public void recover() {
            long differences = 0;
            for (var campaign : repository.findCampaigns()) {
                applicationService.recoverPending(campaign.id(), null);
                int mysqlStock = repository.authoritativeAvailable(campaign.listingId());
                int redisStock = redisStore.stock(campaign.id());
                long redisPending = redisStore.pendingCount(campaign.id());
                if (redisStock >= 0 && redisStock != mysqlStock) {
                    differences++;
                    LOGGER.warn(
                            "秒杀库存差异 campaign_id={} mysql_stock={} redis_stock={} redis_pending={}",
                            campaign.id(), mysqlStock, redisStock, redisPending);
                }
                redisStore.alignStockIfIdle(campaign, mysqlStock);
            }
            inventoryDifference.set(differences);
        }
    }
}
