package com.bingo789.wallet.api;

import com.bingo789.wallet.api.dto.AdjustCommand;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.api.dto.BetAndPayoutCommand;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.OpenWalletCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.UpdateUserLineCommand;
import com.bingo789.wallet.api.dto.UpdateWalletStatusCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Internal wallet contract. Implemented by the wallet controller and consumed through {@link WalletClient}.
 * <p>
 * All mutating commands are idempotent. Business outcomes (insufficient funds, locked wallet ...)
 * are returned as {@link WalletResult} with HTTP 200. Any non-2xx response or timeout means
 * "unknown outcome": the caller must NOT assume failure or success, it must retry with the same
 * idempotency key (or let the provider retry / roll back).
 */
public interface WalletApi {

    String PREFIX = "/internal/wallet";

    @PostMapping(PREFIX + "/bet")
    WalletResult bet(@RequestBody BetCommand command);

    @PostMapping(PREFIX + "/payout")
    WalletResult payout(@RequestBody PayoutCommand command);

    @PostMapping(PREFIX + "/bet-payout")
    WalletResult betAndPayout(@RequestBody BetAndPayoutCommand command);

    @PostMapping(PREFIX + "/rollback")
    WalletResult rollback(@RequestBody RollbackCommand command);

    @PostMapping(PREFIX + "/adjust")
    WalletResult adjust(@RequestBody AdjustCommand command);

    @PostMapping(PREFIX + "/platform-txn")
    WalletResult platformTxn(@RequestBody PlatformTxnCommand command);

    @PostMapping(PREFIX + "/open")
    BalanceView open(@RequestBody OpenWalletCommand command);

    @PostMapping(PREFIX + "/status")
    void updateStatus(@RequestBody UpdateWalletStatusCommand command);

    @PostMapping(PREFIX + "/user-line")
    void updateUserLine(@RequestBody UpdateUserLineCommand command);

    @GetMapping(PREFIX + "/balance")
    BalanceView balance(@RequestParam("userId") long userId, @RequestParam("currency") String currency);
}
