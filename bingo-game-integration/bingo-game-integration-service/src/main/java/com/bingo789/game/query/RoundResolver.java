package com.bingo789.game.query;

import com.bingo789.game.adapter.model.RoundStatus;
import com.bingo789.game.api.dto.ResolveRoundCommand;
import com.bingo789.game.api.dto.RoundResolutionView;
import com.bingo789.game.provider.ProviderRegistry;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.game.wallet.PlayerIds;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Settles rounds that stayed open too long (triggered by bet-record's unsettled-round job): ask the provider,
 * then apply what it reports through the same idempotent wallet commands a callback would use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundResolver {

    private static final String RESOLVER_PREFIX = "resolver:";

    private final ProviderRegistry registry;
    private final WalletClient walletClient;

    public RoundResolutionView resolve(ResolveRoundCommand c) {
        ProviderRuntime provider = registry.require(c.providerCode());
        String playerId = PlayerIds.encode(c.userId());
        RoundStatus status;
        try {
            status = provider.client().execute(() ->
                    provider.adapter().queryRound(c.roundId(), playerId, c.currency(), provider.client()));
        } catch (Exception e) {
            log.warn("round query failed: provider={}, round={}", c.providerCode(), c.roundId(), e);
            return new RoundResolutionView(RoundResolutionView.UNKNOWN, e.getMessage());
        }
        return switch (status.state()) {
            case IN_PROGRESS -> new RoundResolutionView(RoundResolutionView.STILL_OPEN, null);
            case UNKNOWN -> new RoundResolutionView(RoundResolutionView.UNKNOWN, "provider does not know the round");
            case CANCELLED -> cancel(provider, c);
            case COMPLETED -> settle(provider, c, status.payouts());
        };
    }

    private RoundResolutionView cancel(ProviderRuntime provider, ResolveRoundCommand c) {
        WalletResult result = walletClient.rollback(new RollbackCommand(c.userId(), c.currency(), provider.code(),
                RESOLVER_PREFIX + c.roundId(), null, TxnType.BET, c.roundId(), null));
        boolean done = result.isSuccess() || result.code() == WalletResultCode.TXN_NOT_FOUND;
        return new RoundResolutionView(done ? RoundResolutionView.CANCELLED : RoundResolutionView.UNKNOWN, result.message());
    }

    private RoundResolutionView settle(ProviderRuntime provider, ResolveRoundCommand c, List<RoundStatus.Settlement> payouts) {
        if (payouts.isEmpty()) {
            // lost round: a zero payout closes it
            payouts = List.of(new RoundStatus.Settlement(RESOLVER_PREFIX + c.roundId() + ":close", BigDecimal.ZERO, TxnType.PAYOUT));
        }
        for (int i = 0; i < payouts.size(); i++) {
            RoundStatus.Settlement item = payouts.get(i);
            WalletResult result = walletClient.payout(new PayoutCommand(c.userId(), c.currency(), provider.code(),
                    item.txnId(), c.roundId(), null, item.amount(), item.payoutType(), null,
                    provider.config().requireBetForPayout(), i == payouts.size() - 1));
            if (!result.isSuccess()) {
                return new RoundResolutionView(RoundResolutionView.UNKNOWN, result.code() + ": " + result.message());
            }
        }
        return new RoundResolutionView(RoundResolutionView.SETTLED, null);
    }
}
