package ning.linkverse.payment.application;

import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * PaymentExpirationService 关闭失去 Trade 绑定或同步响应的过期支付意图。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentExpirationService {

    private final PaymentRepository repository;
    private final PaymentTransactionService transactionService;
    private final Clock clock;

    public PaymentExpirationService(
            PaymentRepository repository,
            PaymentTransactionService transactionService,
            Clock clock
    ) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${linkverse.payment.expiration-fixed-delay:5000}")
    public void closeDueIntents() {
        var now = clock.instant();
        for (PaymentIntent intent : repository.findDuePending(now, 50)) {
            if ("PENDING".equals(intent.status()) && !intent.expireAt().isAfter(now)) {
                transactionService.closePending(intent, now);
            }
        }
    }
}
