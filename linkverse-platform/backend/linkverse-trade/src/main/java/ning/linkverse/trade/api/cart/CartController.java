package ning.linkverse.trade.api.cart;

import jakarta.validation.Valid;
import ning.linkverse.trade.application.cart.CartApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CartController 暴露当前用户的购物车查询、幂等加购和删除接口。
 *
 * @author ning
 * @date 2026-09-03
 */
@RestController
@RequestMapping("/api/v1/cart/items")
public class CartController {

    private final CartApplicationService service;

    public CartController(CartApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<CartItemResponse> find(@AuthenticationPrincipal Jwt jwt) {
        return service.find(userId(jwt)).stream().map(CartItemResponse::from).toList();
    }

    @PutMapping("/{listingId}")
    public List<CartItemResponse> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long listingId,
            @Valid @RequestBody(required = false) PutCartItemRequest request
    ) {
        Long deliveryId = request == null ? null : request.recommendationDeliveryId();
        return service.add(userId(jwt), listingId, deliveryId).stream().map(CartItemResponse::from).toList();
    }

    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long listingId) {
        service.delete(userId(jwt), listingId);
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("用户令牌中的主体不是有效用户编号", exception);
        }
    }
}
