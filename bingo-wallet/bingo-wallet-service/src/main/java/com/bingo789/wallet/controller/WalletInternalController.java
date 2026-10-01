package com.bingo789.wallet.controller;

import com.bingo789.wallet.api.WalletApi;
import com.bingo789.wallet.api.dto.AdjustCommand;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.api.dto.BetAndPayoutCommand;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.TakeAllBetCommand;
import com.bingo789.wallet.api.dto.UpdateUserLineCommand;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cluster-internal endpoints; never routed by the player gateway or the callback ingress. */
@RestController
@RequiredArgsConstructor
public class WalletInternalController implements WalletApi {

    private final WalletService walletService;

    @Override
    public WalletResult bet(@Valid @RequestBody BetCommand command) {
        return walletService.bet(command);
    }

    @Override
    public WalletResult betAll(@Valid @RequestBody TakeAllBetCommand command) {
        return walletService.betAll(command);
    }

    @Override
    public WalletResult payout(@Valid @RequestBody PayoutCommand command) {
        return walletService.payout(command);
    }

    @Override
    public WalletResult betAndPayout(@Valid @RequestBody BetAndPayoutCommand command) {
        return walletService.betAndPayout(command);
    }

    @Override
    public WalletResult rollback(@Valid @RequestBody RollbackCommand command) {
        return walletService.rollback(command);
    }

    @Override
    public WalletResult adjust(@Valid @RequestBody AdjustCommand command) {
        return walletService.adjust(command);
    }

    @Override
    public WalletResult platformTxn(@Valid @RequestBody PlatformTxnCommand command) {
        return walletService.platformTxn(command);
    }

    @Override
    public BalanceView open(@Valid @RequestBody OpenWalletCommand command) {
        return walletService.open(command);
    }

    @Override
    public void updateStatus(@Valid @RequestBody UpdateWalletStatusCommand command) {
        walletService.updateStatus(command);
    }

    @Override
    public void updateUserLine(@Valid @RequestBody UpdateUserLineCommand command) {
        walletService.updateUserLine(command);
    }

    @Override
    public BalanceView balance(@RequestParam("userId") long userId, @RequestParam("currency") String currency) {
        return walletService.balance(userId, currency);
    }
}
