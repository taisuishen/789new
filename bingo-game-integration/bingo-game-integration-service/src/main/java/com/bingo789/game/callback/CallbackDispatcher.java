package com.bingo789.game.callback;

import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.provider.ProviderRegistry;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.game.wallet.WalletGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The synchronous callback path, kept as short as possible: guard -> parse -> one wallet call -> render.
 * Everything else (round state, turnover, reporting) is derived asynchronously from the ledger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackDispatcher {

    private final ProviderRegistry registry;
    private final CallbackGuard guard;
    private final CallbackRateLimiter rateLimiter;
    private final WalletGateway walletGateway;
    private final MeterRegistry meterRegistry;

    public CallbackResponse dispatch(CallbackRequest request) {
        ProviderRuntime provider = registry.find(request.providerCode()).orElse(null);
        if (provider == null) {
            return CallbackResponse.text(404, "unknown provider");
        }
        ProviderAdapter adapter = provider.adapter();
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = CallbackError.SYSTEM_RETRYABLE.name();
        try (var _ = rateLimiter.acquireProvider(provider.code())) {
            guard.check(provider, request);
            WalletCommand command = adapter.parse(request);
            CommandOutcome outcome;
            try (var _ = rateLimiter.acquirePlayer(playerKey(provider, command))) {
                outcome = walletGateway.execute(provider, command);
            }
            result = outcome.code().name();
            if (!outcome.isSuccess()) {
                log.info("callback {} {} answered {}: {}", provider.code(), request.action(), outcome.code(), outcome.message());
            }
            return adapter.render(request, command, outcome, provider.client());
        } catch (CallbackException e) {
            result = e.error().name();
            log.warn("callback {} {} rejected ({}): {} from {}", provider.code(), request.action(), e.error(), e.getMessage(), request.sourceIp());
            return adapter.renderError(request, e.error());
        } catch (Exception e) {
            // unknown outcome: answer "system error, retry" - never success
            log.error("callback {} {} failed, answering retryable", provider.code(), request.action(), e);
            return adapter.renderError(request, CallbackError.SYSTEM_RETRYABLE);
        } finally {
            sample.stop(Timer.builder("bingo.callback")
                    .tag("provider", provider.code())
                    .tag("action", request.action())
                    .tag("result", result)
                    .register(meterRegistry));
        }
    }

    private static String playerKey(ProviderRuntime provider, WalletCommand command) {
        String playerId = WalletGateway.playerIdOf(command);
        return playerId == null ? null : provider.code() + ":" + playerId;
    }
}
