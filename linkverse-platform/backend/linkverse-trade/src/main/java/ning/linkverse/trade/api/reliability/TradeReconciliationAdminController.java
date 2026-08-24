package ning.linkverse.trade.api.reliability;

import ning.linkverse.trade.infrastructure.reliability.TradeReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TradeReconciliationAdminController 仅为本机验收脚本提供只读对账触发入口。
 *
 * @author ning
 * @date 2026-08-24
 */
@Profile({"local", "test"})
@RestController
@RequestMapping("/internal/v1/reconciliation")
public class TradeReconciliationAdminController {

    private final TradeReconciliationService service;

    public TradeReconciliationAdminController(TradeReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public TradeReconciliationService.Snapshot reconcile() {
        return service.reconcile();
    }
}
