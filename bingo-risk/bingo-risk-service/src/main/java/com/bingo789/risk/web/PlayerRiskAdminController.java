package com.bingo789.risk.web;

import com.bingo789.common.core.Result;
import com.bingo789.risk.common.Operators;
import com.bingo789.risk.web.dto.WalletHoldRequest;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.enums.WalletStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


// TODO: RBAC + audit log (holds must be recorded with operator, reason and case reference)
@Slf4j
@RestController
@RequestMapping("/admin/risk/users/{userId}")
@RequiredArgsConstructor
public class PlayerRiskAdminController {

    /** Wallet lock key owned by risk; releasing it never lifts locks placed by user-service (SELF_EXCLUSION, COOL_OFF). */
    static final String LOCK_REASON = "AML_HOLD";

    private final WalletClient walletClient;

    /** AML hold: FROZEN refuses every debit (bets and withdrawals) in all currencies; credits still land. */
    @PostMapping("/hold")
    public Result<Void> hold(@PathVariable("userId") long userId, @Valid @RequestBody WalletHoldRequest request,
                             @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        String by = Operators.require(operator);
        walletClient.updateStatus(new UpdateWalletStatusCommand(userId, null, WalletStatus.FROZEN, LOCK_REASON));
        // TODO: persist operator, reason and case reference in a risk audit table (the wallet only keeps the lock key)
        log.warn("wallet of user {} put on AML hold by {}: {}", userId, by, request.reason());
        return Result.ok();
    }

    /** Releases only the AML hold; the wallet status then falls back to the strictest remaining lock, if any. */
    @PostMapping("/release")
    public Result<Void> release(@PathVariable("userId") long userId, @Valid @RequestBody WalletHoldRequest request,
                                @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        String by = Operators.require(operator);
        walletClient.updateStatus(new UpdateWalletStatusCommand(userId, null, WalletStatus.ACTIVE, LOCK_REASON));
        log.warn("AML hold of user {} released by {}: {}", userId, by, request.reason());
        return Result.ok();
    }
}
