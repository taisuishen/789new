package com.bingo789.payment.api;

import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

public interface PaymentApi {

    @PostMapping("/internal/payment/withdrawals/audit")
    void auditWithdrawal(@RequestBody WithdrawAuditCommand command);
}
