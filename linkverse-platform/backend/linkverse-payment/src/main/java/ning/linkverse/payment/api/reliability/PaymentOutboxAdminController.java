package ning.linkverse.payment.api.reliability;

import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.messaging.outbox.OutboxEventView;
import ning.linkverse.payment.domain.PaymentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * PaymentOutboxAdminController 仅在 local/test 提供脱敏查询和按原事件 ID 人工重放。
 *
 * @author ning
 * @date 2026-08-24
 */
@Profile({"local", "test"})
@RestController
@RequestMapping("/internal/v1/outbox-events")
public class PaymentOutboxAdminController {

    private final PaymentRepository repository;
    private final Clock clock;

    public PaymentOutboxAdminController(PaymentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/{eventId}")
    public OutboxEventView find(@PathVariable String eventId) {
        return repository.findByEventId(eventId)
                .orElseThrow(() -> new PlatformException(CommonErrorCode.NOT_FOUND));
    }

    @PostMapping("/{eventId}/replay")
    public OutboxEventView replay(@PathVariable String eventId) {
        if (!repository.replayParked(eventId, clock.instant())) {
            throw new PlatformException(CommonErrorCode.CONFLICT, "仅允许重放停车事件");
        }
        return find(eventId);
    }
}
