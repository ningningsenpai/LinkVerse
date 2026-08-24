package ning.linkverse.trade.api.seckill;

import ning.linkverse.trade.application.seckill.SeckillApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * SeckillController 暴露活动查询、幂等预约和预约所有者查询接口。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@RequestMapping("/api/v1/seckill")
public class SeckillController {

    private final SeckillApplicationService service;
    private final Clock clock;

    public SeckillController(SeckillApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/campaigns/{campaignId}")
    public SeckillCampaignResponse campaign(@PathVariable long campaignId) {
        return SeckillCampaignResponse.from(service.campaign(campaignId), clock.instant());
    }

    @PostMapping("/campaigns/{campaignId}/reservations")
    public ResponseEntity<SeckillReservationResponse> reserve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long campaignId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.accepted().body(SeckillReservationResponse.from(
                service.reserve(campaignId, userId(jwt), idempotencyKey)));
    }

    @GetMapping("/reservations/{reservationNo}")
    public SeckillReservationResponse reservation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String reservationNo
    ) {
        return SeckillReservationResponse.from(service.ownedReservation(reservationNo, userId(jwt)));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("用户令牌中的主体不是有效用户编号", exception);
        }
    }
}
