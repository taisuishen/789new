package com.bingo789.game.adapter;

import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.RoundStatus;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.game.provider.ProviderClient;

import java.time.Instant;
import java.util.List;

/**
 * Provider protocol SPI (strategy pattern): one implementation per provider protocol, registered as a
 * Spring bean. It only translates; it holds no money logic.
 * <p>
 * Inbound methods run on the callback path and must be fast and allocation-light: verify, parse,
 * render — nothing else. Outbound methods are always invoked by the framework inside the provider's
 * bulkhead and circuit breaker (see {@link ProviderClient#execute}).
 * <p>
 * Implement {@link TransferCapable} as well for providers that run in transfer-wallet mode.
 */
public interface ProviderAdapter {

    /** Protocol name; providers select it with {@code bingo.providers.<code>.adapter} (default: the provider code). */
    String name();

    // ---------------------------------------------------------------- inbound (seamless wallet callbacks)

    /**
     * Verifies the signature over the raw request; throws {@code CallbackException.auth(...)} on mismatch.
     * Protocols whose body is ENCRYPTED with a shared key authenticate by decrypting it: they do that (and any
     * timestamp check inside the ciphertext) in {@link #parse}, and leave this method empty.
     */
    void verifySignature(CallbackRequest request, ProviderClient client);

    /** Timestamp covered by the signature, for replay protection; null when the protocol has none. */
    default Instant requestTimestamp(CallbackRequest request) {
        return null;
    }

    /**
     * Translates the provider message into the unified command; throws {@code CallbackException} for bad input
     * ({@code CallbackException.auth} when an encrypted body does not decrypt). Providers that identify the player by
     * the launch token on every call return a {@link WalletCommand.Session}; several wallet operations in one call are
     * a {@link WalletCommand.Batch}; calls that move no money are a {@link WalletCommand.Ack}.
     */
    WalletCommand parse(CallbackRequest request, ProviderClient client);

    /**
     * True for protocols that query their open stakes ({@link WalletCommand.OpenBets}) and settle them with the token
     * they were placed with, possibly after it expired (YGR fishing). The token-bound stakes of such a provider are then
     * recorded with their token until paid out or refunded, and that record identifies the player for the payout or
     * refund of the stake when the token no longer verifies.
     */
    default boolean tracksOpenBets() {
        return false;
    }

    /** Renders a business outcome exactly as this provider's spec requires (including duplicates = success). */
    CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client);

    /** Renders a non-business failure. {@link CallbackError#SYSTEM_RETRYABLE} must map to the spec's retryable error. */
    CallbackResponse renderError(CallbackRequest request, CallbackError error);

    // ---------------------------------------------------------------- outbound

    LaunchView launch(LaunchCommand command, String playerId, ProviderClient client);

    List<ProviderGameView> listGames(ProviderClient client);

    /** One page of the provider's bet history for [from, to); used for reconciliation, not for money. */
    BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client);

    RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client);
}
