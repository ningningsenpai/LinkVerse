package ning.linkverse.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.messaging.outbox.OutboxRelay;
import ning.linkverse.trade.application.seckill.SeckillApplicationService;
import ning.linkverse.trade.application.seckill.SeckillMessageService;
import ning.linkverse.trade.application.seckill.SeckillOrderTransactionService;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

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

    @Bean
    Queue seckillRequestQueue() {
        return new Queue(REQUEST_QUEUE, true);
    }

    @Bean
    Queue seckillResultQueue() {
        return new Queue(RESULT_QUEUE, true);
    }

    @Bean
    Binding seckillRequestBinding(Queue seckillRequestQueue, TopicExchange linkVerseEventExchange) {
        return BindingBuilder.bind(seckillRequestQueue).to(linkVerseEventExchange).with("seckill.request");
    }

    @Bean
    Binding seckillResultBinding(Queue seckillResultQueue, TopicExchange linkVerseEventExchange) {
        return BindingBuilder.bind(seckillResultQueue).to(linkVerseEventExchange).with("seckill.result");
    }

    @Bean
    OutboxRelay tradeOutboxRelay(
            SeckillRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock
    ) {
        return new OutboxRelay(repository, rabbitTemplate, clock);
    }

    /**
     * Listener 将请求建单和结果 Redis 投影分别保持幂等。
     */
    @Component
    public static class Listener {

        private final ObjectMapper objectMapper;
        private final SeckillOrderTransactionService orderService;
        private final SeckillMessageService messageService;

        public Listener(
                ObjectMapper objectMapper,
                SeckillOrderTransactionService orderService,
                SeckillMessageService messageService
        ) {
            this.objectMapper = objectMapper;
            this.orderService = orderService;
            this.messageService = messageService;
        }

        @RabbitListener(queues = REQUEST_QUEUE, autoStartup = "${linkverse.trade.seckill-events-enabled:true}")
        public void consumeRequest(byte[] rawBody) throws Exception {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!"SeckillRequested".equals(required(root, "event_type"))) {
                return;
            }
            try {
                orderService.consume(required(root, "event_id"));
            } catch (SeckillOrderTransactionService.SeckillStockConflictException conflict) {
                orderService.recordStockFailure(conflict);
            }
        }

        @RabbitListener(queues = RESULT_QUEUE, autoStartup = "${linkverse.trade.seckill-events-enabled:true}")
        public void consumeResult(byte[] rawBody) throws Exception {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = required(root, "event_type");
            if (!"SeckillReservationSucceeded".equals(eventType)
                    && !"SeckillReservationRejected".equals(eventType)) {
                return;
            }
            JsonNode payload = root.path("payload");
            messageService.project(
                    required(root, "event_id"), eventType, required(payload, "reservation_no"),
                    Long.parseLong(required(payload, "campaign_id")),
                    Long.parseLong(required(payload, "campaign_version")),
                    new String(rawBody, StandardCharsets.UTF_8));
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

        public Scheduler(
                OutboxRelay tradeOutboxRelay,
                SeckillRepository repository,
                SeckillApplicationService applicationService,
                SeckillRedisStore redisStore
        ) {
            this.relay = tradeOutboxRelay;
            this.repository = repository;
            this.applicationService = applicationService;
            this.redisStore = redisStore;
        }

        @Scheduled(fixedDelayString = "${linkverse.trade.seckill-relay-delay:500ms}")
        public void relay() {
            relay.relayOnce(50);
        }

        @Scheduled(fixedDelayString = "${linkverse.trade.seckill-recovery-delay:2s}")
        public void recover() {
            for (var campaign : repository.findCampaigns()) {
                applicationService.recoverPending(campaign.id(), null);
                int mysqlStock = repository.authoritativeAvailable(campaign.listingId());
                int redisStock = redisStore.stock(campaign.id());
                long redisPending = redisStore.pendingCount(campaign.id());
                if (redisStock >= 0 && redisStock != mysqlStock) {
                    LOGGER.warn(
                            "秒杀库存差异 campaign_id={} mysql_stock={} redis_stock={} redis_pending={}",
                            campaign.id(), mysqlStock, redisStock, redisPending);
                }
                redisStore.alignStockIfIdle(campaign, mysqlStock);
            }
        }
    }
}
