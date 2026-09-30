package com.bingo789.game.callback;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.config.GameIntegrationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inbound limits on two dimensions (Sentinel hot-parameter rules): per provider, and per player.
 * The per-player limit stops a single provider's retry storm on one account from saturating the wallet.
 * Limited requests are answered as retryable, never as failures.
 * TODO: load rules from Nacos via sentinel-datasource-nacos so they can be tuned at runtime.
 */
@Component
public class CallbackRateLimiter {

    static final String PROVIDER_RESOURCE = "callback:provider";
    static final String PLAYER_RESOURCE = "callback:player";

    public CallbackRateLimiter(GameIntegrationProperties properties) {
        GameIntegrationProperties.RateLimit limits = properties.callback().rateLimit();
        ParamFlowRuleManager.loadRules(List.of(
                new ParamFlowRule(PROVIDER_RESOURCE).setParamIdx(0).setCount(limits.perProviderQps()),
                new ParamFlowRule(PLAYER_RESOURCE).setParamIdx(0).setCount(limits.perPlayerQps())));
    }

    public Permit acquireProvider(String providerCode) {
        return acquire(PROVIDER_RESOURCE, providerCode);
    }

    /** @param playerKey provider code + player id; null skips the check (e.g. authenticate) */
    public Permit acquirePlayer(String playerKey) {
        return playerKey == null ? Permit.NONE : acquire(PLAYER_RESOURCE, playerKey);
    }

    private static Permit acquire(String resource, String key) {
        try {
            return new Permit(SphU.entry(resource, EntryType.IN, 1, key), key);
        } catch (BlockException e) {
            throw new CallbackException(CallbackError.RATE_LIMITED, resource + " limit reached for " + key);
        }
    }

    /** Must be closed in reverse acquisition order (try-with-resources does this). */
    public static final class Permit implements AutoCloseable {

        static final Permit NONE = new Permit(null, null);

        private final Entry entry;
        private final String key;

        private Permit(Entry entry, String key) {
            this.entry = entry;
            this.key = key;
        }

        @Override
        public void close() {
            if (entry != null) {
                entry.exit(1, key);
            }
        }
    }
}
