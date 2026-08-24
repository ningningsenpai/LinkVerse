package ning.linkverse.trade.application.order;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.TradeOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * OrderApplicationService 负责幂等外层恢复、订单所有者查询和只读到期扫描。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class OrderApplicationService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[\\x21-\\x7E]{8,64}");
    private static final int MAX_ORDER_NUMBER_ATTEMPTS = 3;

    private final TradeRepository tradeRepository;
    private final OrderTransactionService transactionService;
    private final Supplier<String> orderNumberSupplier;

    @Autowired
    public OrderApplicationService(
            TradeRepository tradeRepository,
            OrderTransactionService transactionService
    ) {
        this(tradeRepository, transactionService, () -> UUID.randomUUID().toString().replace("-", ""));
    }

    OrderApplicationService(
            TradeRepository tradeRepository,
            OrderTransactionService transactionService,
            Supplier<String> orderNumberSupplier
    ) {
        this.tradeRepository = tradeRepository;
        this.transactionService = transactionService;
        this.orderNumberSupplier = orderNumberSupplier;
    }

    public OrderCreationResult create(long buyerId, Long listingId, Integer quantity, String idempotencyKey) {
        validateRequest(listingId, quantity, idempotencyKey);
        String fingerprint = fingerprint(buyerId, listingId, quantity);

        TradeOrder existing = tradeRepository.findByBuyerAndIdempotencyKey(buyerId, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        DuplicateKeyException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ORDER_NUMBER_ATTEMPTS; attempt++) {
            try {
                return new OrderCreationResult(
                        transactionService.create(
                                buyerId,
                                listingId,
                                idempotencyKey,
                                fingerprint,
                                orderNumberSupplier.get()
                        ),
                        true
                );
            } catch (DuplicateKeyException exception) {
                // 幂等唯一键冲突优先恢复已提交赢家；查不到时按订单号碰撞有限重试。
                TradeOrder concurrent = tradeRepository.findByBuyerAndIdempotencyKey(buyerId, idempotencyKey)
                        .orElse(null);
                if (concurrent != null) {
                    return replay(concurrent, fingerprint);
                }
                lastConflict = exception;
            }
        }
        throw lastConflict;
    }

    public TradeOrder findOwned(String orderNo, long buyerId) {
        return tradeRepository.findByOrderNoAndBuyer(orderNo, buyerId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.ORDER_NOT_FOUND));
    }

    public List<DueOrderCandidate> findDuePending(
            Instant cutoff,
            Instant afterExpireAt,
            Long afterId,
            int limit
    ) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("扫描批量必须在1到200之间");
        }
        return tradeRepository.findDuePending(cutoff, afterExpireAt, afterId, limit);
    }

    private OrderCreationResult replay(TradeOrder existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new OrderCreationResult(existing, false);
    }

    private void validateRequest(Long listingId, Integer quantity, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        if (listingId == null || listingId < 1 || quantity == null || quantity != 1) {
            throw new PlatformException(ning.linkverse.core.error.CommonErrorCode.INVALID_REQUEST);
        }
    }

    private String fingerprint(long buyerId, long listingId, int quantity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(
                    ("v1|" + buyerId + "|" + listingId + "|" + quantity).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
