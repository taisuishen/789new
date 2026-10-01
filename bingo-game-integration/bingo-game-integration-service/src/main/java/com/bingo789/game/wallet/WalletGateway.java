package com.bingo789.game.wallet;

import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.CommandOutcome.Code;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Ciphers;
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
import com.bingo789.wallet.api.dto.TakeAllBetCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Executes a unified {@link WalletCommand} against the wallet: one RPC per wallet operation (a {@link WalletCommand.Batch}
 * runs its steps one after the other), plus one user-service call for "authenticate" and for the first call of a
 * token-bound {@link WalletCommand.Session} (then cached, see GameSessions).
 * <p>
 * Any exception here (timeout, 5xx) propagates to the dispatcher and is answered as "retry": the outcome is
 * unknown, and the wallet's idempotency makes the provider's retry or rollback safe.
 * <p>
 * Providers that track open bets (ProviderAdapter#tracksOpenBets) have their token-bound commands recorded in the
 * {@link OpenBetLedger}, which also answers {@link WalletCommand.OpenBets} and identifies the player of an expired token
 * for the payout or refund of a stake placed with it.
 */
@Component
@RequiredArgsConstructor
public class WalletGateway {

    /** wallet_txn.provider_txn_id / ref_txn_id / ext_txn_id / round_id are VARCHAR(128). */
    static final int MAX_ID_LENGTH = 128;

    private final WalletClient wallet;
    private final UserClient users;
    private final GameSessions sessions;
    private final OpenBetLedger openBets;

    public CommandOutcome execute(ProviderRuntime provider, WalletCommand command) {
        if (command instanceof WalletCommand.Authenticate auth) {
            return authenticate(provider, auth);
        }
        if (command instanceof WalletCommand.Ack ack && ack.playerId() == null) {
            return CommandOutcome.of(Code.SUCCESS, null, ack.currency(), null);
        }
        if (command instanceof WalletCommand.OpenBets query) {
            if (!tracksOpenBets(provider)) {
                return new CommandOutcome(Code.INVALID_REQUEST, null, null, null, null, false, "provider does not track open bets");
            }
            return new CommandOutcome(Code.SUCCESS, null, null, null, null, false, null, null,
                    openBets.open(provider.code(), query.from(), query.to()));
        }
        if (command instanceof WalletCommand.Session session) {
            return session(provider, session);
        }
        String playerId = playerIdOf(command);
        Long userId = PlayerIds.tryDecode(playerId);
        if (userId == null) {
            return CommandOutcome.of(Code.PLAYER_NOT_FOUND, playerId, command.currency(), null);
        }
        String currency = command.currency() != null ? command.currency() : provider.defaultCurrency();
        return run(provider, command, userId, playerId, currency);
    }

    private CommandOutcome session(ProviderRuntime provider, WalletCommand.Session session) {
        WalletCommand inner = session.command();
        String innerPlayer = playerIdOf(inner);
        boolean tracked = tracksOpenBets(provider);
        GameTokenView token = sessions.verify(provider.code(), session.token());
        if (!token.valid()) {
            // the payout or refund of a tracked stake: the token it was placed with still names its player
            OpenBetLedger.Holder holder = tracked && settlesStake(inner) ? openBets.holder(provider.code(), session.token()) : null;
            if (holder == null || innerPlayer != null && !innerPlayer.equals(PlayerIds.encode(holder.userId()))) {
                return CommandOutcome.of(Code.INVALID_TOKEN, innerPlayer, session.currency(), null);
            }
            String currency = session.currency() != null ? session.currency() : holder.currency();
            return tracked(provider, session.token(), inner, holder.userId(), currency);
        }
        if (innerPlayer != null && !innerPlayer.equals(PlayerIds.encode(token.userId()))) {
            return CommandOutcome.of(Code.INVALID_TOKEN, innerPlayer, session.currency(), null);
        }
        // stakes need the player to be allowed to play now; wins / refunds of rounds in play are always credited
        if (!token.playAllowed() && placesStake(inner)) {
            return CommandOutcome.of(Code.PLAYER_LOCKED, PlayerIds.encode(token.userId()), session.currency(), null);
        }
        String currency = session.currency() != null ? session.currency() : token.currency();
        return tracked
                ? tracked(provider, session.token(), inner, token.userId(), currency)
                : run(provider, inner, token.userId(), PlayerIds.encode(token.userId()), currency);
    }

    /** A stake is recorded before its wallet call, every outcome after it (see OpenBetLedger). */
    private CommandOutcome tracked(ProviderRuntime provider, String token, WalletCommand command, long userId, String currency) {
        String playerId = PlayerIds.encode(userId);
        if (OpenBetLedger.isStake(command) && currency != null && provider.supportsCurrency(currency)) {
            openBets.pending(provider.code(), token, command, userId, currency);
        }
        CommandOutcome outcome = run(provider, command, userId, playerId, currency);
        openBets.record(provider.code(), command, outcome);
        return outcome;
    }

    private CommandOutcome run(ProviderRuntime provider, WalletCommand command, long userId, String playerId, String currency) {
        if (currency == null || !provider.supportsCurrency(currency)) {
            return new CommandOutcome(Code.INVALID_REQUEST, playerId, currency, null, null, false,
                    "currency not enabled for provider");
        }
        String code = provider.code();
        return switch (command) {
            case WalletCommand.Authenticate auth -> authenticate(provider, auth);
            case WalletCommand.Session s -> throw new IllegalArgumentException("nested session");
            case WalletCommand.OpenBets q -> throw new IllegalArgumentException("not a player command");
            case WalletCommand.Ack a -> CommandOutcome.of(Code.SUCCESS, playerId, currency, null);
            case WalletCommand.Batch batch -> batch(provider, batch, userId, playerId, currency);
            case WalletCommand.GetBalance b -> balance(userId, playerId, currency);
            case WalletCommand.Bet b -> outcome(playerId, wallet.bet(new BetCommand(
                    userId, currency, code, fit(b.txnId()), fit(b.roundId()), b.gameCode(), b.amount(), b.roundClosed())));
            case WalletCommand.TakeAll t -> outcome(playerId, wallet.betAll(new TakeAllBetCommand(
                    userId, currency, code, fit(t.txnId()), fit(t.roundId()), t.gameCode(), provider.config().balanceScale())));
            case WalletCommand.Payout p -> outcome(playerId, wallet.payout(new PayoutCommand(
                    userId, currency, code, fit(p.txnId()), fit(p.roundId()), p.gameCode(), p.amount(), p.payoutType(),
                    fit(p.betTxnId()), provider.config().requireBetForPayout(), p.roundClosed())));
            case WalletCommand.BetAndPayout bp -> outcome(playerId, wallet.betAndPayout(new BetAndPayoutCommand(
                    userId, currency, code, fit(bp.betTxnId()), fit(bp.payoutTxnId()), fit(bp.roundId()), bp.gameCode(),
                    bp.betAmount(), bp.payoutAmount(), bp.roundClosed())));
            case WalletCommand.Rollback r -> outcome(playerId, wallet.rollback(new RollbackCommand(
                    userId, currency, code, fit(r.rollbackTxnId()), fit(r.targetTxnId()), r.targetType(), fit(r.roundId()),
                    r.gameCode())));
            case WalletCommand.Adjust a -> outcome(playerId, wallet.adjust(new AdjustCommand(
                    userId, currency, code, fit(a.txnId()), fit(a.refTxnId()), fit(a.roundId()), a.signedAmount(), a.reason())));
        };
    }

    /**
     * First failure wins; otherwise the last step's outcome (its balance is the final one), a replay only if all were.
     * Reversing a payout that never happened is nothing to do: a batch that voids a round ("reverse the payout, then
     * the bet") continues with the bet when the round was not paid yet.
     */
    private CommandOutcome batch(ProviderRuntime provider, WalletCommand.Batch batch, long userId, String playerId, String currency) {
        CommandOutcome last = null;
        boolean allReplays = true;
        for (WalletCommand step : batch.steps()) {
            CommandOutcome outcome = run(provider, step, userId, playerId, currency);
            if (outcome.code() == Code.TXN_NOT_FOUND && step instanceof WalletCommand.Rollback r
                    && r.targetType() != null && r.targetType().isPayout()) {
                continue;
            }
            if (!outcome.isSuccess()) {
                return outcome;
            }
            last = outcome;
            allReplays &= outcome.replay();
        }
        if (last == null) {
            // only reversals of payouts that never happened
            return CommandOutcome.of(Code.TXN_NOT_FOUND, playerId, currency, null);
        }
        return new CommandOutcome(last.code(), last.playerId(), last.currency(), last.balance(), last.platformTxnId(),
                allReplays, last.message(), last.amount(), List.of());
    }

    private static boolean placesStake(WalletCommand command) {
        return switch (command) {
            case WalletCommand.Bet b -> true;
            case WalletCommand.TakeAll t -> true;
            case WalletCommand.BetAndPayout bp -> true;
            case WalletCommand.Batch batch -> batch.steps().stream().anyMatch(WalletGateway::placesStake);
            default -> false;
        };
    }

    private static boolean tracksOpenBets(ProviderRuntime provider) {
        return provider.adapter() != null && provider.adapter().tracksOpenBets();
    }

    /** The payout or refund of a stake, which a provider may send with the stake's token after it expired. */
    private static boolean settlesStake(WalletCommand command) {
        return command instanceof WalletCommand.Payout || command instanceof WalletCommand.Rollback;
    }

    /** Opening a game session: the player must be allowed to play. */
    private CommandOutcome authenticate(ProviderRuntime provider, WalletCommand.Authenticate auth) {
        GameTokenView token = users.verifyGameToken(auth.token());
        if (!token.valid() || !token.playAllowed() || !provider.code().equals(token.providerCode())) {
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
            case BET_SETTLED -> Code.BET_SETTLED;
            case INVALID_REQUEST -> Code.INVALID_REQUEST;
        };
        return new CommandOutcome(code, playerId, result.currency(), displayable(result.balance()),
                result.txnId(), result.replay(), result.message(), result.txnAmount(), List.of());
    }

    /**
     * Provider ids longer than the wallet's columns (128) become {@code sha256:<hex>}: deterministic, so a retry maps to
     * the same key and idempotency holds; shorter ids are kept exactly as sent.
     */
    static String fit(String id) {
        return id == null || id.length() <= MAX_ID_LENGTH ? id : "sha256:" + Ciphers.sha256Hex(id);
    }

    /** Providers are never shown a negative balance (possible after reversals when policy = ALLOW). */
    private static BigDecimal displayable(BigDecimal balance) {
        return balance == null || balance.signum() >= 0 ? balance : BigDecimal.ZERO;
    }

    /**
     * The provider-facing player id of a command (a session without one: its token, as rate-limiting key; use
     * {@link #playerIdOf} only for keys, never to decode a user id from a session).
     */
    public static String playerIdOf(WalletCommand command) {
        return switch (command) {
            case WalletCommand.Authenticate a -> null;
            case WalletCommand.Session s -> playerIdOf(s.command()) != null ? playerIdOf(s.command()) : "token:" + s.token();
            case WalletCommand.OpenBets q -> null;
            case WalletCommand.Batch b -> b.playerId();
            case WalletCommand.Ack a -> a.playerId();
            case WalletCommand.GetBalance b -> b.playerId();
            case WalletCommand.Bet b -> b.playerId();
            case WalletCommand.TakeAll t -> t.playerId();
            case WalletCommand.Payout p -> p.playerId();
            case WalletCommand.BetAndPayout bp -> bp.playerId();
            case WalletCommand.Rollback r -> r.playerId();
            case WalletCommand.Adjust a -> a.playerId();
        };
    }
}
