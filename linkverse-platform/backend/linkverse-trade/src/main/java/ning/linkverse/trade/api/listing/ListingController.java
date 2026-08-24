package ning.linkverse.trade.api.listing;

import ning.linkverse.trade.application.listing.ListingQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ListingController 暴露商品详情查询接口。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final ListingQueryService listingQueryService;

    public ListingController(ListingQueryService listingQueryService) {
        this.listingQueryService = listingQueryService;
    }

    @GetMapping("/{listingId}")
    public ListingResponse find(@PathVariable long listingId) {
        return ListingResponse.from(listingQueryService.find(listingId));
    }
}
