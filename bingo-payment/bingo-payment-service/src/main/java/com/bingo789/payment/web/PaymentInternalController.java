package com.bingo789.payment.web;

import com.bingo789.payment.api.PaymentApi;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.payment.service.WithdrawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Cluster-internal endpoints; never routed by the player gateway or the callback ingress. */
@RestController
@RequiredArgsConstructor
public class PaymentInternalController implements PaymentApi {

    private final WithdrawService withdrawService;

    /**
     * 2xx once the decision is recorded (payout submission failures are recovered asynchronously).
     * 409 for a conflicting decision; 5xx means "retry with the same decision".
     */
    @Override
    public void auditWithdrawal(@Valid @RequestBody WithdrawAuditCommand command) {
        withdrawService.audit(command);
    }
}
