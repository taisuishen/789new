package com.bingo789.game.wallet;

import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.CommandOutcome.Code;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.GameTokenView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.AdjustCommand;
import com.bingo789.wallet.api.dto.BalanceView;
import com.bingo789.wallet.api.dto.BetAndPayoutCommand;
import com.bingo789.wallet.api.dto.BetCommand;
import com.bingo789.wallet.api.dto.PayoutCommand;
import com.bingo789.wallet.api.dto.RollbackCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Executes a unified {@link WalletCommand} against the wallet: exactly one RPC per callback
 * (plus one user-service call for "authenticate", which happens once per game session).
 * <p>
 * Any exception here (timeout, 5xx) propagates to the dispatcher and is answered as "retry": the outcome is
 * unknown, and the wallet's idempotency makes the provider's retry or rollback safe.
 */
@Component
@RequiredArgsConstructor
public class WalletGateway {

    private final WalletClient wallet;
    private final UserClient users;

    public CommandOutcome execute(ProviderRuntime provider, WalletCommand command) {
        if (command instanceof WalletCommand.Authenticate auth) {
            return authenticate(provider, auth);
        }
        String playerId = playerIdOf(command);
        Long userId = PlayerIds.tryDecode(playerId);
        if (userId == null) {
            return CommandOutcome.of(Code.PLAYER_NOT_FOUND, playerId, command.currency(), null);
        }
        if (!provider.supportsCurrency(command.currency())) {
            return new CommandOutcome(Code.INVALID_REQUEST, playerId, command.currency(), null, null, false,
                    "currency not enabled for provider");
        }
        String code = provider.code();
        return switch (command) {
            case WalletCommand.Authenticate auth -> authenticate(provider, auth);
            case WalletCommand.GetBalance b -> balance(userId, b.playerId(), b.currency());
            case WalletCommand.Bet b -> outcome(b.playerId(), wallet.bet(new BetCommand(
                    userId, b.currency(), code, b.txnId(), b.roundId(), b.gameCode(), b.amount(), b.roundClosed())));
            case WalletCommand.Payout p -> outcome(p.playerId(), wallet.payout(new PayoutCommand(
                    userId, p.currency(), code, p.txnId(), p.roundId(), p.gameCode(), p.amount(), p.payoutType(),
                    p.betTxnId(), provider.config().requireBetForPayout(), p.roundClosed())));
            case WalletCommand.BetAndPayout bp -> outcome(bp.playerId(), wallet.betAndPayout(new BetAndPayoutCommand(
                    userId, bp.currency(), code, bp.betTxnId(), bp.payoutTxnId(), bp.roundId(), bp.gameCode(),
                    bp.betAmount(), bp.payoutAmount(), bp.roundClosed())));
            case WalletCommand.Rollback r -> outcome(r.playerId(), wallet.rollback(new RollbackCommand(
                    userId, r.currency(), code, r.rollbackTxnId(), r.targetTxnId(), r.targetType(), r.roundId(), r.gameCode())));
            case WalletCommand.Adjust a -> outcome(a.playerId(), wallet.adjust(new AdjustCommand(
                    userId, a.currency(), code, a.txnId(), a.refTxnId(), a.roundId(), a.signedAmount(), a.reason())));
        };
    }

    private CommandOutcome authenticate(ProviderRuntime provider, WalletCommand.Authenticate auth) {
        GameTokenView token = users.verifyGameToken(auth.token());
        if (!token.valid() || !provider.code().equals(token.providerCode())) {
            return CommandOutcome.of(Code.INVALID_TOKEN, null, auth.currency(), null);
        }
        return balance(token.userId(), PlayerIds.encode(token.userId()), token.currency());
    }

    private CommandOutcome balance(long userId, String playerId, String currency) {
        try {
            BalanceView view = wallet.balance(userId, currency);
            return CommandOutcome.of(Code.SUCCESS, playerId, currency, displayable(view.balance()));
        } catch (FeignException.NotFound e) {
            return CommandOutcome.of(Code.PLAYER_NOT_FOUND, playerId, currency, null);
        }
    }

    private static CommandOutcome outcome(String playerId, WalletResult result) {
        Code code = switch (result.code()) {
            case SUCCESS -> Code.SUCCESS;
            case INSUFFICIENT_FUNDS -> Code.INSUFFICIENT_FUNDS;
            case WALLET_NOT_FOUND -> Code.PLAYER_NOT_FOUND;
            case WALLET_LOCKED -> Code.PLAYER_LOCKED;
            case BET_NOT_FOUND -> Code.BET_NOT_FOUND;
            case BET_CANCELLED -> Code.TXN_CANCELLED;
            case TXN_NOT_FOUND -> Code.TXN_NOT_FOUND;
            case INVALID_REQUEST -> Code.INVALID_REQUEST;
        };
        return new CommandOutcome(code, playerId, result.currency(), displayable(result.balance()),
                result.txnId(), result.replay(), result.message());
    }

    /** Providers are never shown a negative balance (possible after reversals when policy = ALLOW). */
    private static BigDecimal displayable(BigDecimal balance) {
        return balance == null || balance.signum() >= 0 ? balance : BigDecimal.ZERO;
    }

    public static String playerIdOf(WalletCommand command) {
        return switch (command) {
            case WalletCommand.Authenticate a -> null;
            case WalletCommand.GetBalance b -> b.playerId();
            case WalletCommand.Bet b -> b.playerId();
            case WalletCommand.Payout p -> p.playerId();
            case WalletCommand.BetAndPayout bp -> bp.playerId();
            case WalletCommand.Rollback r -> r.playerId();
            case WalletCommand.Adjust a -> a.playerId();
        };
    }
}
