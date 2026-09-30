package com.bingo789.reconcile.report;

import com.bingo789.lobby.api.LobbyClient;
import com.bingo789.lobby.api.dto.GameView;
import com.bingo789.reconcile.config.ReconcileProperties;
import com.bingo789.reconcile.entity.RtpAlert;
import com.bingo789.reconcile.mapper.GgrDailyMapper;
import com.bingo789.reconcile.mapper.RtpAlertMapper;
import com.bingo789.reconcile.model.GgrRow;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares each game's actual RTP (net payout / net bet over the last {@code window-days} reporting days) with the
 * certified theoretical RTP from the lobby catalogue. A sustained deviation points to a provider-side problem
 * (wrong game version or maths, missing or duplicated settlements) or to arbitrage / bonus abuse.
 * <p>
 * Slot RTP converges slowly: a high-volatility game can sit several points away from its theoretical value over
 * hundreds of thousands of spins, so min-rounds / min-bet must be set per volatility class.
 * TODO: confidence-interval based alerting (deviation vs the game's payout variance and the number of rounds).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RtpMonitorJob {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final GgrDailyMapper ggrMapper;
    private final RtpAlertMapper alertMapper;
    private final LobbyClient lobbyClient;
    private final ReconcileProperties properties;

    @XxlJob("reconRtpMonitorJob")
    public void execute() {
        ReconcileProperties.Rtp cfg = properties.rtp();
        LocalDate to = LocalDate.now(properties.reportZoneId());
        LocalDate from = to.minusDays(cfg.windowDays());
        List<GgrRow> volumes = ggrMapper.gameVolumes(from, to, cfg.minBet(), cfg.minRounds());

        Map<String, Map<String, BigDecimal>> theoreticalByProvider = new HashMap<>();
        int checked = 0;
        int alerts = 0;
        for (GgrRow row : volumes) {
            Map<String, BigDecimal> theoretical = theoreticalByProvider.computeIfAbsent(row.getProviderCode(), this::theoreticalRtps);
            BigDecimal expected = theoretical.get(row.getGameCode());
            if (expected == null || row.getBet() == null || row.getBet().signum() <= 0) {
                continue;
            }
            checked++;
            BigDecimal payout = row.getPayout() == null ? BigDecimal.ZERO : row.getPayout();
            BigDecimal actual = payout.multiply(HUNDRED).divide(row.getBet(), 3, RoundingMode.HALF_UP);
            BigDecimal deviation = actual.subtract(expected);
            if (deviation.abs().compareTo(cfg.maxDeviation()) <= 0) {
                continue;
            }
            alerts++;
            RtpAlert alert = new RtpAlert();
            alert.setStatDate(to.minusDays(1));
            alert.setProviderCode(row.getProviderCode());
            alert.setGameCode(row.getGameCode());
            alert.setCurrency(row.getCurrency());
            alert.setWindowDays(cfg.windowDays());
            alert.setActualRtp(actual);
            alert.setTheoreticalRtp(expected);
            alert.setBet(row.getBet());
            alert.setPayout(payout);
            alert.setRounds(row.getBetCount());
            alertMapper.upsert(alert);
            log.warn("RTP deviation {}/{} ({}): actual {}% vs theoretical {}% over {} days, bet {}, rounds {}",
                    row.getProviderCode(), row.getGameCode(), row.getCurrency(), actual, expected, cfg.windowDays(),
                    row.getBet(), row.getBetCount());
        }
        XxlJobHelper.log("window={}..{}, games={}, checked={}, alerts={}", from, to.minusDays(1), volumes.size(), checked, alerts);
    }

    /** gameCode -> theoretical RTP; empty when the lobby is unreachable (the provider is skipped this run). */
    private Map<String, BigDecimal> theoreticalRtps(String providerCode) {
        try {
            return lobbyClient.providerGames(providerCode).stream()
                    .filter(g -> g.theoreticalRtp() != null)
                    .collect(Collectors.toMap(GameView::gameCode, GameView::theoreticalRtp, (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("could not load theoretical RTPs of provider {}: {}", providerCode, e.toString());
            return Map.of();
        }
    }
}
