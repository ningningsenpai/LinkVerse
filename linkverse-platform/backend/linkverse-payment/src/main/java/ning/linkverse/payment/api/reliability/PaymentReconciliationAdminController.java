package ning.linkverse.payment.api.reliability;

import ning.linkverse.payment.infrastructure.reliability.PaymentReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PaymentReconciliationAdminController 仅为本机验收脚本提供只读对账触发入口。
 *
 * @author ning
 * @date 2026-08-24
 */
@Profile({"local", "test"})
@RestController
@RequestMapping("/internal/v1/reconciliation")
public class PaymentReconciliationAdminController {

    private final PaymentReconciliationService service;

    public PaymentReconciliationAdminController(PaymentReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public PaymentReconciliationService.Snapshot reconcile() {
        return service.reconcile();
    }
}
