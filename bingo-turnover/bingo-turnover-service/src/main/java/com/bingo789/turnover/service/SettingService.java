package com.bingo789.turnover.service;

import com.bingo789.common.core.line.LineScope;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import com.bingo789.turnover.api.dto.TurnoverSettingView;
import com.bingo789.turnover.api.dto.UpdateTurnoverSettingCommand;
import com.bingo789.turnover.config.TurnoverProperties;
import com.bingo789.turnover.domain.TurnoverSetting;
import com.bingo789.turnover.mapper.TurnoverSettingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-line rules (turnover_setting on the global datasource), held in memory and refreshed every 30 s because the
 * round stream needs them for every player. {@link #rules()} may load from the global datasource, so call it
 * OUTSIDE a shard scope / transaction and pass the snapshot down.
 */
@Slf4j
@Service
public class SettingService {

    private final TurnoverSettingMapper mapper;
    private final ShardTemplate shards;
    private final TurnoverProperties properties;
    private volatile Rules rules;

    public SettingService(TurnoverSettingMapper mapper, ShardTemplate shards, TurnoverProperties properties) {
        this.mapper = mapper;
        this.shards = shards;
        this.properties = properties;
    }

    /** Effective rule of one line and currency; null thresholds are switched off. */
    public record Rule(BigDecimal clearBelowBalance, BigDecimal completeBelowRemaining, BigDecimal depositMultiplier) {
    }

    /** Immutable snapshot; a line / currency without a row gets the configured defaults. */
    public static final class Rules {

        private final Map<String, Rule> byKey;
        private final Rule fallback;

        Rules(Map<String, Rule> byKey, Rule fallback) {
            this.byKey = byKey;
            this.fallback = fallback;
        }

        public Rule of(int userLine, String currency) {
            return byKey.getOrDefault(key(userLine, currency), fallback);
        }
    }

    public Rules rules() {
        Rules current = rules;
        if (current != null) {
            return current;
        }
        try {
            return reload();
        } catch (RuntimeException e) {
            // global database unreachable before the first load: defaults, i.e. no automatic clearing
            log.warn("turnover settings could not be loaded, using defaults: {}", e.toString());
            return new Rules(Map.of(), defaultRule());
        }
    }

    @Scheduled(fixedDelayString = "${bingo.turnover.settings-refresh:30s}")
    public void refresh() {
        try {
            reload();
        } catch (RuntimeException e) {
            log.warn("turnover settings refresh failed, keeping the previous rules: {}", e.toString());
        }
    }

    public List<TurnoverSettingView> list(LineScope scope) {
        return shards.onGlobal(mapper::selectAll).stream()
                .filter(s -> scope.contains(s.getUserLine()))
                .sorted(Comparator.comparing(TurnoverSetting::getUserLine).thenComparing(TurnoverSetting::getCurrency))
                .map(SettingService::view)
                .toList();
    }

    public TurnoverSettingView update(UpdateTurnoverSettingCommand command) {
        TurnoverSetting setting = new TurnoverSetting();
        setting.setUserLine(command.userLine());
        setting.setCurrency(command.currency().toUpperCase(Locale.ROOT));
        setting.setClearBelowBalance(command.clearBelowBalance());
        setting.setCompleteBelowRemaining(command.completeBelowRemaining());
        setting.setDepositMultiplier(command.depositMultiplier());
        setting.setUpdatedBy(command.operatorId());
        TurnoverSetting saved = shards.onGlobal(() -> {
            mapper.upsert(setting);
            return mapper.select(setting.getUserLine(), setting.getCurrency());
        });
        log.info("turnover setting line {} {} updated by {}: clearBelowBalance={}, completeBelowRemaining={}, depositMultiplier={}",
                saved.getUserLine(), saved.getCurrency(), command.operatorId(), saved.getClearBelowBalance(),
                saved.getCompleteBelowRemaining(), saved.getDepositMultiplier());
        refresh();
        return view(saved);
    }

    private Rules reload() {
        Map<String, Rule> byKey = new HashMap<>();
        for (TurnoverSetting s : shards.onGlobal(mapper::selectAll)) {
            byKey.put(key(s.getUserLine(), s.getCurrency()),
                    new Rule(s.getClearBelowBalance(), s.getCompleteBelowRemaining(), s.getDepositMultiplier()));
        }
        Rules loaded = new Rules(Map.copyOf(byKey), defaultRule());
        rules = loaded;
        return loaded;
    }

    private Rule defaultRule() {
        return new Rule(null, null, properties.defaultDepositMultiplier());
    }

    private static String key(int userLine, String currency) {
        return userLine + ":" + currency;
    }

    private static TurnoverSettingView view(TurnoverSetting s) {
        return new TurnoverSettingView(s.getUserLine(), s.getCurrency(), s.getClearBelowBalance(),
                s.getCompleteBelowRemaining(), s.getDepositMultiplier(), s.getUpdatedBy(),
                s.getUpdatedAt() == null ? null : s.getUpdatedAt().atZone(BingoTime.ZONE).toInstant());
    }
}
