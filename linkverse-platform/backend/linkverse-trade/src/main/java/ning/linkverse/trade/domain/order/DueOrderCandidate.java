package ning.linkverse.trade.domain.order;

import java.time.Instant;

/**
 * DueOrderCandidate 表示阶段3只读扫描得到的到期订单候选。
 *
 * @author ning
 * @date 2026-08-24
 */
public record DueOrderCandidate(long id, String orderNo, Instant expireAt) {
}
