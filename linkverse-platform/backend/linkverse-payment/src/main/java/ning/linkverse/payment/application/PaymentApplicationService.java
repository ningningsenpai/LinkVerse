package ning.linkverse.payment.application;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentErrorCode;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * PaymentApplicationService 负责 Intent 创建、所有者查询和幂等关闭。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentApplicationService {

    private final PaymentRepository repository;
    private final PaymentTransactionService transactionService;
    private final Clock clock;

    public PaymentApplicationService(
            PaymentRepository repository,
            PaymentTransactionService transactionService,
            Clock clock
    ) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    public PaymentIntent create(CreatePaymentIntent command) {
        validate(command);
        if (!command.expireAt().isAfter(clock.instant())) {
            throw new PlatformException(PaymentErrorCode.INTENT_EXPIRED);
        }
        PaymentIntent existing = repository.findByOrderNo(command.orderNo()).orElse(null);
        if (existing != null) {
            return requireSame(existing, command);
        }
        try {
            transactionService.insertPending(command, compactUuid(), clock.instant());
        } catch (DuplicateKeyException exception) {
            PaymentIntent concurrent = repository.findByOrderNo(command.orderNo())
                    .orElseThrow(() -> exception);
            return requireSame(concurrent, command);
        }
        return repository.findByOrderNo(command.orderNo()).orElseThrow();
    }

    public PaymentIntent findOwned(String intentNo, long buyerId) {
        return repository.findOwned(intentNo, buyerId)
                .orElseThrow(() -> new PlatformException(PaymentErrorCode.INTENT_NOT_FOUND));
    }

    public PaymentIntent findByOrder(String orderNo) {
        return repository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PlatformException(PaymentErrorCode.INTENT_NOT_FOUND));
    }

    public PaymentIntent findByIntent(String intentNo) {
        return repository.findByIntentNo(intentNo)
                .orElseThrow(() -> new PlatformException(PaymentErrorCode.INTENT_NOT_FOUND));
    }

    public PaymentIntent close(CreatePaymentIntent command) {
        validate(command);
        Instant now = clock.instant();
        PaymentIntent existing = repository.findByOrderNo(command.orderNo()).orElse(null);
        if (existing == null) {
            try {
                return transactionService.insertClosed(command, compactUuid(), now);
            } catch (DuplicateKeyException exception) {
                existing = repository.findByOrderNo(command.orderNo()).orElseThrow(() -> exception);
            }
        }
        requireSame(existing, command);
        transactionService.closePending(existing, now);
        return repository.findByOrderNo(command.orderNo()).orElseThrow();
    }

    private PaymentIntent requireSame(PaymentIntent existing, CreatePaymentIntent command) {
        if (!existing.sameRequest(command)) {
            throw new PlatformException(PaymentErrorCode.INTENT_CONFLICT);
        }
        return existing;
    }

    private void validate(CreatePaymentIntent command) {
        if (command == null
                || command.orderNo() == null
                || !command.orderNo().matches("[0-9a-f]{32}")
                || command.buyerId() < 1
                || command.merchantId() < 1
                || command.amount() == null
                || command.amount().signum() <= 0
                || command.currency() == null
                || !command.currency().matches("[A-Z]{3}")
                || command.expireAt() == null) {
            throw new PlatformException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
