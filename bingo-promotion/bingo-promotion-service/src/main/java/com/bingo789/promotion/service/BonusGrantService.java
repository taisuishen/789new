package com.bingo789.promotion.service;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.BonusGrantedEvent;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.common.mybatis.DuplicateKeys;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.promotion.common.Texts;
import com.bingo789.promotion.config.PromotionProperties;
import com.bingo789.promotion.domain.BonusGrant;
import com.bingo789.promotion.domain.BonusStatus;
import com.bingo789.promotion.domain.Promotion;
import com.bingo789.promotion.domain.PromotionEntry;
import com.bingo789.promotion.domain.PromotionType;
import com.bingo789.promotion.mapper.BonusGrantMapper;
import com.bingo789.promotion.mapper.PromotionMapper;
import com.bingo789.promotion.terms.FirstDepositTerms;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BonusGrantService {

    static final String FIRST_DEPOSIT = "FIRST_DEPOSIT";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BonusGrantMapper grantMapper;
    private final PromotionMapper promotionMapper;
    private final UserClient userClient;
    private final WalletClient walletClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final PromotionProperties properties;

    /**
     * First-deposit bonus = min(deposit x percent, maxAmount) with the terms of the FIRST_DEPOSIT promotion that
     * applies to the deposit ({@link #selectOffer}; none = no bonus), idempotent per deposit (bizNo "FDB-" + orderNo).
     * An unknown wallet outcome throws, so the message is redelivered and the same bizNo retried.
     * <p>
     * TODO(compliance): bonuses must never be forced on a player. Require an explicit opt-in (e.g. a "claim bonus"
     *  choice captured with the deposit request) and acceptance of the bonus terms version before granting; until
     *  that exists keep bingo.promotion.first-deposit.enabled=false in production.
     */
    public void onFirstDeposit(DepositSucceededEvent event) {
        if (!event.firstDeposit() || !properties.firstDeposit().enabled()) {
            return;
        }
        String bizNo = "FDB-" + event.orderNo();
        BonusGrant grant = MasterRoute.run(() -> grantMapper.selectByBizNo(bizNo));
        if (grant == null) {
            Offer offer = selectOffer(event);
            if (offer == null) {
                log.info("first-deposit bonus for {} skipped, no FIRST_DEPOSIT promotion for line {} / {}",
                        event.orderNo(), event.userLine(), event.currency());
                return;
            }
            // no marketing incentives for players who are self-excluded, cooling off or otherwise barred from play;
            // a user-service failure throws and the message is redelivered
            PlayerStatusView player = userClient.playerStatus(event.userId());
            if (!player.canPlay()) {
                log.info("first-deposit bonus for {} skipped, player {} not eligible: {}", event.orderNo(), event.userId(), player.reason());
                return;
            }
            FirstDepositTerms terms = offer.terms();
            BigDecimal amount = event.amount().multiply(terms.percent())
                    .divide(HUNDRED, Money.SCALE, RoundingMode.DOWN)
                    .min(terms.maxAmount())
                    .setScale(Money.SCALE, RoundingMode.DOWN);
            if (amount.signum() <= 0) {
                return;
            }
            grant = insertOrLoad(newGrant(bizNo, event, amount, offer));
        }
        if (grant.getStatus() == BonusStatus.PENDING && pay(grant) == PayOutcome.UNKNOWN) {
            throw new IllegalStateException("bonus " + bizNo + " wallet outcome unknown, redelivering");
        }
    }

    /**
     * The ONLINE FIRST_DEPOSIT promotion active when the deposit succeeded, for the deposit's line (the line in the
     * event, i.e. at deposit time) and currency; several: highest sort, then newest. Read from the table rather than
     * the catalog, so a late or redelivered event still finds a promotion that has ended since.
     * A promotion whose stored terms are invalid throws (alert, the message is redelivered) instead of being skipped.
     */
    Offer selectOffer(DepositSucceededEvent event) {
        Instant at = event.succeededAt() != null ? event.succeededAt() : Instant.now();
        int line = event.userLine();
        for (Promotion row : promotionMapper.selectOnlineActiveAt(PromotionType.FIRST_DEPOSIT.name(), BingoTime.toLocal(at))) {
            PromotionEntry entry = PromotionEntry.of(row);
            if (!entry.visibleOn(line)) {
                continue;
            }
            FirstDepositTerms terms;
            try {
                terms = FirstDepositTerms.parse(entry.config());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("ALERT promotion " + entry.id() + " has invalid terms: " + e.getMessage(), e);
            }
            if (terms.appliesTo(event.currency())) {
                return new Offer(entry.id(), terms);
            }
        }
        return null;
    }

    /** Wallet credit (idempotent on PROMOTION / bizNo / BONUS), then PAID + outbox in one transaction. */
    public PayOutcome pay(BonusGrant grant) {
        WalletResult wallet;
        try {
            wallet = walletClient.platformTxn(new PlatformTxnCommand(grant.getUserId(), grant.getCurrency(), TxnType.BONUS,
                    RebateService.WALLET_SOURCE, grant.getBizNo(), grant.getAmount(), grant.getBonusType() + " bonus"));
        } catch (RuntimeException e) {
            log.warn("bonus {} outcome unknown, stays PENDING", grant.getBizNo(), e);
            return PayOutcome.UNKNOWN;
        }
        if (!wallet.isSuccess()) {
            log.warn("bonus {} refused by the wallet: {} {}", grant.getBizNo(), wallet.code(), wallet.message());
            grantMapper.markFailed(grant.getId(), Texts.truncate(wallet.code() + ": " + wallet.message(), 255));
            return PayOutcome.FAILED;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (grantMapper.markPaid(grant.getId()) == 1) {
                // line and wagering terms as stored with the grant, also when re-published by the retry job
                outboxService.save(Topics.BONUS_GRANTED, grant.getBizNo(), new BonusGrantedEvent(
                        grant.getBizNo(), grant.getUserId(), grant.getUserLine(), grant.getCurrency(), grant.getAmount(),
                        grant.getBonusType(), grant.getTurnoverMultiplier(), grant.getTurnoverScope(),
                        grant.getTurnoverScopeValue(), Instant.now()));
            }
        });
        return PayOutcome.PAID;
    }

    private BonusGrant insertOrLoad(BonusGrant grant) {
        try {
            grantMapper.insert(grant);
            return grant;
        } catch (RuntimeException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            return MasterRoute.run(() -> grantMapper.selectByBizNo(grant.getBizNo()));
        }
    }

    private static BonusGrant newGrant(String bizNo, DepositSucceededEvent event, BigDecimal amount, Offer offer) {
        LocalDateTime now = BingoTime.now();
        BonusGrant grant = new BonusGrant();
        grant.setBizNo(bizNo);
        grant.setUserId(event.userId());
        grant.setUserLine(event.userLine());
        grant.setCurrency(event.currency());
        grant.setAmount(amount);
        grant.setBonusType(FIRST_DEPOSIT);
        grant.setPromotionId(offer.promotionId());
        grant.setStatus(BonusStatus.PENDING);
        grant.setTurnoverMultiplier(offer.terms().turnover().multiplier());
        grant.setTurnoverScope(offer.terms().turnover().scope());
        grant.setTurnoverScopeValue(offer.terms().turnover().scopeValue());
        grant.setCreatedAt(now);
        grant.setUpdatedAt(now);
        return grant;
    }

    /** The promotion chosen for a deposit and its parsed terms. */
    record Offer(long promotionId, FirstDepositTerms terms) {
    }
}
