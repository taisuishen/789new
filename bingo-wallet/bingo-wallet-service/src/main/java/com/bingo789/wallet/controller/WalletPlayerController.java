package com.bingo789.wallet.controller;

import com.bingo789.common.core.Result;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.ReplicaRoute;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.common.web.CurrentUser;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.config.WalletProperties;
import com.bingo789.wallet.controller.dto.TxnHistoryView;
import com.bingo789.wallet.mapper.WalletTxnMapper;
import com.bingo789.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Player-facing reads through the gateway, served by the read-only nodes of the player's shard. */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletPlayerController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService walletService;
    private final WalletTxnMapper txnMapper;
    private final ShardTemplate shards;
    private final WalletProperties properties;

    /** For display: read from a read-only node (TaurusDB shares storage with the primary, lag is milliseconds). */
    @GetMapping("/balance")
    public Result<BigDecimal> balance(@RequestParam("currency") String currency) {
        long userId = CurrentUser.requireUserId();
        return Result.ok(ReplicaRoute.run(() -> walletService.balance(userId, currency)).balance());
    }

    @GetMapping("/balances")
    public Result<List<BalanceView>> balances() {
        long userId = CurrentUser.requireUserId();
        return Result.ok(ReplicaRoute.run(() -> walletService.balances(userId)));
    }

    /**
     * The player's transactions of the last {@code days} days (at most the retention window,
     * bingo.wallet.retention.keep), newest first, from a read-only node of the player's shard (may lag the primary by
     * a moment). Older history is served by the back office from StarRocks.
     */
    @GetMapping("/transactions")
    public Result<List<TxnHistoryView>> transactions(@RequestParam(value = "currency", required = false) String currency,
                                                     @RequestParam(value = "days", defaultValue = "1") int days,
                                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                                     @RequestParam(value = "size", defaultValue = "20") int size) {
        long userId = CurrentUser.requireUserId();
        int maxDays = (int) Math.max(1, properties.retention().keep().toDays());
        LocalDateTime to = BingoTime.now();
        LocalDateTime from = to.minusDays(Math.clamp(days, 1, maxDays));
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = (Math.max(page, 1) - 1) * limit;
        String cur = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
        return Result.ok(shards.forUser(userId, () -> ReplicaRoute.run(
                        () -> txnMapper.findHistory(userId, cur, from, to, offset, limit)))
                .stream().map(TxnHistoryView::of).toList());
    }
}
