package com.bingo789.payment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.WithdrawRequestedEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.payment.api.dto.WithdrawAuditCommand;
import com.bingo789.payment.channel.ChannelOutcome;
import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.channel.PayoutResult;
import com.bingo789.payment.common.PageResult;
import com.bingo789.payment.common.PaymentErrorCode;
import com.bingo789.payment.common.RemoteCalls;
import com.bingo789.payment.common.Texts;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.ChannelDirection;
import com.bingo789.payment.domain.WithdrawOrder;
import com.bingo789.payment.domain.WithdrawStatus;
import com.bingo789.common.mq.event.WithdrawFinishedEvent;
import com.bingo789.payment.mapper.WithdrawOrderMapper;
import com.bingo789.payment.web.dto.CreateWithdrawRequest;
import com.bingo789.payment.web.dto.WithdrawOrderView;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import com.bingo789.wallet.api.enums.WalletResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Withdrawal lifecycle. Wallet calls use bizNo = orderNo with WITHDRAW_FREEZE / WITHDRAW_CONFIRM / WITHDRAW_UNFREEZE;
 * each is idempotent, so every step can be retried. The wallet does not stop CONFIRM and UNFREEZE both being applied
 * to the same order, so this class guarantees it: CONFIRM is only sent for SUCCEEDED payouts and UNFREEZE only
 * before submission (rejection) or for definite channel failures.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private static final String WALLET_SOURCE = DepositService.WALLET_SOURCE;

    private final WithdrawOrderMapper withdrawMapper;
    private final ChannelConfigService channelConfigService;
    private final ChannelRegistry channelRegistry;
    private final UserClient userClient;
    private final WalletClient walletClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * @param status    PENDING_AUDIT (frozen), CREATED (outcome unknown, retried by the job) or FAILED (refused)
     * @param rejection wallet code when the freeze was refused, otherwise null
     */
    public record FreezeResult(WithdrawStatus status, WalletResultCode rejection) {
    }

    // ------------------------------------------------------------------ player requests

    public WithdrawOrderView create(long userId, CreateWithdrawRequest request, String clientIp, String deviceId) {
        PlayerStatusView player = RemoteCalls.call("user.playerStatus", () -> userClient.playerStatus(userId));
        // canWithdraw requires KYC VERIFIED; self-excluded players can still withdraw their balance.
        if (!player.canWithdraw()) {
            throw new BizException(PaymentErrorCode.WITHDRAW_NOT_ALLOWED,
                    Texts.hasText(player.reason()) ? player.reason() : PaymentErrorCode.WITHDRAW_NOT_ALLOWED.message());
        }
        String currency = Texts.hasText(request.currency()) ? request.currency() : player.defaultCurrency();
        BizException.check(Texts.hasText(currency), PaymentErrorCode.CURRENCY_NOT_SUPPORTED);
        BigDecimal amount = Money.normalizePositive(request.amount());
        ChannelConfig config = channelConfigService.requireUsable(request.channelCode(), ChannelDirection.PAYOUT, currency, amount);
        // TODO: resolve payeeRef in the payee vault and verify it belongs to this player and fits the channel type.

        // stored on the order and carried by its events; 0 (not sent by an older user-service) is the default line
        int userLine = UserLine.orDefault(player.userLine());
        WithdrawOrder order = newOrder(userId, userLine, currency, amount, config.getCode(), request.payeeRef(), clientIp, deviceId);
        withdrawMapper.insert(order);

        FreezeResult freeze = freezeAndRequestAudit(order);
        if (freeze.status() == WithdrawStatus.FAILED) {
            throw new BizException(rejectionError(freeze.rejection()));
        }
        order.setStatus(freeze.status());
        return WithdrawOrderView.of(order);
    }

    public PageResult<WithdrawOrderView> listOwn(long userId, long page, long size) {
        Page<WithdrawOrder> request = PageResult.request(page, size);
        Page<WithdrawOrder> result = withdrawMapper.selectPage(request, Wrappers.<WithdrawOrder>lambdaQuery()
                .eq(WithdrawOrder::getUserId, userId)
                .orderByDesc(WithdrawOrder::getCreatedAt));
        return PageResult.of(result, WithdrawOrderView::of);
    }

    // ------------------------------------------------------------------ freeze

    /**
     * Freezes the amount and hands the order to risk. Safe to repeat for a CREATED order: the wallet replays
     * the original result for (PAYMENT, orderNo, WITHDRAW_FREEZE).
     */
    public FreezeResult freezeAndRequestAudit(WithdrawOrder order) {
        WalletResult wallet;
        try {
            wallet = walletClient.platformTxn(walletCommand(order, TxnType.WITHDRAW_FREEZE, "withdraw freeze"));
        } catch (RuntimeException e) {
            // Unknown outcome: stay CREATED; WithdrawRecoveryJob retries the freeze with the same bizNo.
            log.warn("WITHDRAW_FREEZE outcome unknown for {}, will be retried", order.getOrderNo(), e);
            return new FreezeResult(WithdrawStatus.CREATED, null);
        }
        if (wallet.isSuccess()) {
            transactionTemplate.executeWithoutResult(tx -> {
                if (withdrawMapper.transition(order.getId(), WithdrawStatus.CREATED, WithdrawStatus.PENDING_AUDIT) == 1) {
                    outboxService.save(Topics.WITHDRAW_REQUESTED, order.getOrderNo(),
                            new WithdrawRequestedEvent(order.getOrderNo(), order.getUserId(), order.getUserLine(),
                                    order.getCurrency(), order.getAmount(), order.getChannelCode(), order.getClientIp(),
                                    order.getDeviceId(), BingoTime.toInstant(order.getCreatedAt())));
                }
            });
            return new FreezeResult(WithdrawStatus.PENDING_AUDIT, null);
        }
        // Business rejection (INSUFFICIENT_FUNDS, WALLET_LOCKED ...): nothing was frozen, the order fails right away.
        withdrawMapper.fail(order.getId(), WithdrawStatus.CREATED, "freeze refused: " + wallet.code());
        return new FreezeResult(WithdrawStatus.FAILED, wallet.code());
    }

    // ------------------------------------------------------------------ audit

    /**
     * Risk decision. The decision is written once (audit_decision); a retry of the same decision resumes the flow,
     * a different decision is refused with AUDIT_CONFLICT.
     */
    public void audit(WithdrawAuditCommand command) {
        WithdrawOrder order = load(command.orderNo());
        if (order.getStatus() == WithdrawStatus.PENDING_AUDIT && order.getAuditDecision() == null) {
            String reason = Texts.truncate(command.reason(), 512);
            if (command.decision() == WithdrawAuditCommand.Decision.APPROVED) {
                withdrawMapper.approve(order.getId(), reason, command.auditor());
            } else {
                withdrawMapper.recordRejection(order.getId(), reason, command.auditor());
            }
            order = reload(order.getId());
        }
        if (order.getAuditDecision() == null) {
            throw new BizException(PaymentErrorCode.ORDER_STATE_INVALID,
                    "withdrawal " + order.getOrderNo() + " is " + order.getStatus() + " and cannot be audited");
        }
        if (order.getAuditDecision() != command.decision()) {
            throw new BizException(PaymentErrorCode.AUDIT_CONFLICT,
                    "withdrawal " + order.getOrderNo() + " was already " + order.getAuditDecision());
        }
        log.info("withdrawal {} {} by {}", order.getOrderNo(), command.decision(), command.auditor());
        continueAfterDecision(order);
    }

    /** Resumes an audited order; used by {@link #audit} and by the recovery job. */
    public void continueAfterDecision(WithdrawOrder order) {
        if (order.getStatus() == WithdrawStatus.APPROVED) {
            startPayout(order);
        } else if (order.getStatus() == WithdrawStatus.PENDING_AUDIT
                && order.getAuditDecision() == WithdrawAuditCommand.Decision.REJECTED) {
            completeRejection(order);
        }
    }

    /**
     * Unfreeze first (the payout was never submitted, so returning the funds is safe), then REJECTED + outbox.
     * On failure the order stays PENDING_AUDIT with decision REJECTED and is resumed by the caller's retry or the job.
     */
    private void completeRejection(WithdrawOrder order) {
        WalletResult wallet = walletClient.platformTxn(walletCommand(order, TxnType.WITHDRAW_UNFREEZE, "withdraw rejected"));
        if (!wallet.isSuccess()) {
            log.error("ALERT wallet refused WITHDRAW_UNFREEZE for rejected withdrawal {}: {} {}",
                    order.getOrderNo(), wallet.code(), wallet.message());
            throw new IllegalStateException("WITHDRAW_UNFREEZE refused for " + order.getOrderNo() + ": " + wallet.code());
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (withdrawMapper.transition(order.getId(), WithdrawStatus.PENDING_AUDIT, WithdrawStatus.REJECTED) == 1) {
                outboxService.save(Topics.WITHDRAW_FINISHED, order.getOrderNo(),
                        finishedEvent(order, WithdrawStatus.REJECTED, order.getAuditReason()));
            }
        });
    }

    // ------------------------------------------------------------------ payout

    /**
     * APPROVED -> PAYING is claimed BEFORE the channel is called: whatever happens afterwards (timeout, crash), the
     * order can never be submitted twice, and its fate is decided only by what the channel reports.
     */
    public void startPayout(WithdrawOrder order) {
        PaymentChannel channel = channelRegistry.find(order.getChannelCode()).orElse(null);
        if (channel == null) {
            log.error("ALERT no channel adapter {} for approved withdrawal {}", order.getChannelCode(), order.getOrderNo());
            return;
        }
        if (withdrawMapper.transition(order.getId(), WithdrawStatus.APPROVED, WithdrawStatus.PAYING) == 0) {
            return; // someone else is submitting it
        }
        order.setStatus(WithdrawStatus.PAYING);
        PayoutResult result;
        try {
            result = channel.submitPayout(order);
        } catch (RuntimeException e) {
            // Unknown outcome: the channel may have accepted the payout. Stay PAYING; the recovery job queries it.
            log.warn("payout submission outcome unknown for {}, stays PAYING", order.getOrderNo(), e);
            return;
        }
        try {
            applyPayoutResult(order, result);
        } catch (RuntimeException e) {
            log.warn("could not apply the submission result of payout {}; the recovery job will re-query", order.getOrderNo(), e);
        }
    }

    /** @return true when the notify may be acknowledged */
    public boolean onPayoutNotify(String channelCode, PayoutResult result) {
        String orderNo = result.orderNo();
        WithdrawOrder order = orderNo == null ? null : MasterRoute.run(() -> withdrawMapper.selectByOrderNo(orderNo));
        if (order == null || !order.getChannelCode().equals(channelCode)) {
            log.error("ALERT payout notify from {} for unknown order {} (or an order of another channel)", channelCode, orderNo);
            return false;
        }
        return switch (order.getStatus()) {
            case PAYING -> applyPayoutResult(order, result);
            case SUCCEEDED, FAILED -> {
                boolean contradicts = (order.getStatus() == WithdrawStatus.SUCCEEDED && result.outcome() == ChannelOutcome.FAILED)
                        || (order.getStatus() == WithdrawStatus.FAILED && result.outcome() == ChannelOutcome.SUCCEEDED);
                if (contradicts) {
                    log.error("ALERT payout {} is {} but channel {} now reports {}; manual reconciliation required",
                            order.getOrderNo(), order.getStatus(), channelCode, result.outcome());
                }
                yield true;
            }
            default -> {
                log.warn("payout notify for {} in status {} not processed", order.getOrderNo(), order.getStatus());
                yield false;
            }
        };
    }

    /** Recovery for PAYING orders: query the channel and apply what it reports. */
    public void recoverPayout(WithdrawOrder order, LocalDateTime alertIfIdleBefore) {
        PaymentChannel channel = channelRegistry.find(order.getChannelCode()).orElse(null);
        if (channel == null) {
            log.error("ALERT no channel adapter {} for paying withdrawal {}", order.getChannelCode(), order.getOrderNo());
            return;
        }
        PayoutResult result = channel.queryPayout(order);
        if (!order.getOrderNo().equals(result.orderNo())) {
            log.error("ALERT channel {} answered the query for payout {} with order {}",
                    order.getChannelCode(), order.getOrderNo(), result.orderNo());
            return;
        }
        applyPayoutResult(order, result);
        if (result.outcome() == ChannelOutcome.PENDING && order.getUpdatedAt().isBefore(alertIfIdleBefore)) {
            log.error("ALERT payout {} unresolved since {}; reconcile with channel {} manually. Do NOT unfreeze without the "
                    + "channel's confirmation that it was not paid.", order.getOrderNo(), order.getUpdatedAt(), order.getChannelCode());
        }
        // TODO: if the channel reports the order as unknown (the submission never arrived), resubmit with the same
        //  merchant order no, but only for channels that guarantee de-duplication on it.
    }

    /**
     * Applies a payout outcome to an order in PAYING.
     * <p>
     * !!! NEVER UNFREEZE ON AN UNKNOWN OUTCOME !!!
     * Timeouts, exceptions, "processing" and unrecognised provider codes are PENDING and keep the order in PAYING
     * until the channel gives a definite answer. Unfreezing a payout that the channel executes later pays the
     * player twice. Only a FAILED result, which adapters may report solely for provider-guaranteed final failures,
     * returns the funds to the player's balance.
     *
     * @return true when the outcome was fully processed
     */
    public boolean applyPayoutResult(WithdrawOrder order, PayoutResult result) {
        return switch (result.outcome()) {
            case PENDING -> {
                if (result.channelOrderNo() != null && order.getChannelOrderNo() == null) {
                    withdrawMapper.setChannelOrderNo(order.getId(), result.channelOrderNo());
                }
                yield true;
            }
            case SUCCEEDED -> completePayout(order, result);
            case FAILED -> failPayout(order, result);
        };
    }

    /** WITHDRAW_CONFIRM first (idempotent), then SUCCEEDED + outbox. A wallet exception propagates (unknown outcome). */
    private boolean completePayout(WithdrawOrder order, PayoutResult result) {
        WalletResult wallet = walletClient.platformTxn(walletCommand(order, TxnType.WITHDRAW_CONFIRM, "withdraw paid"));
        if (!wallet.isSuccess()) {
            log.error("ALERT wallet refused WITHDRAW_CONFIRM for paid withdrawal {}: {} {}",
                    order.getOrderNo(), wallet.code(), wallet.message());
            return false;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (withdrawMapper.markPaid(order.getId(), result.channelOrderNo()) == 1) {
                outboxService.save(Topics.WITHDRAW_FINISHED, order.getOrderNo(),
                        finishedEvent(order, WithdrawStatus.SUCCEEDED, null));
            }
        });
        return true;
    }

    /** Definite channel failure only: WITHDRAW_UNFREEZE first (idempotent), then FAILED + outbox. */
    private boolean failPayout(WithdrawOrder order, PayoutResult result) {
        WalletResult wallet = walletClient.platformTxn(walletCommand(order, TxnType.WITHDRAW_UNFREEZE, "withdraw payout failed"));
        if (!wallet.isSuccess()) {
            log.error("ALERT wallet refused WITHDRAW_UNFREEZE for failed payout {}: {} {}",
                    order.getOrderNo(), wallet.code(), wallet.message());
            return false;
        }
        String reason = Texts.truncate("payout failed: " + result.reason(), 255);
        transactionTemplate.executeWithoutResult(tx -> {
            if (withdrawMapper.fail(order.getId(), WithdrawStatus.PAYING, reason) == 1) {
                outboxService.save(Topics.WITHDRAW_FINISHED, order.getOrderNo(),
                        finishedEvent(order, WithdrawStatus.FAILED, reason));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private WithdrawOrder load(String orderNo) {
        WithdrawOrder order = MasterRoute.run(() -> withdrawMapper.selectByOrderNo(orderNo));
        if (order == null) {
            throw new BizException(PaymentErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private WithdrawOrder reload(long id) {
        return MasterRoute.run(() -> withdrawMapper.selectById(id));
    }

    private static PlatformTxnCommand walletCommand(WithdrawOrder order, TxnType type, String remark) {
        return new PlatformTxnCommand(order.getUserId(), order.getCurrency(), type, WALLET_SOURCE, order.getOrderNo(),
                order.getAmount(), remark);
    }

    private static WithdrawFinishedEvent finishedEvent(WithdrawOrder order, WithdrawStatus status, String reason) {
        return new WithdrawFinishedEvent(order.getOrderNo(), order.getUserId(), order.getUserLine(), order.getCurrency(),
                order.getAmount(), order.getFee(), status.name(), reason, Instant.now());
    }

    private static PaymentErrorCode rejectionError(WalletResultCode code) {
        if (code == WalletResultCode.INSUFFICIENT_FUNDS) {
            return PaymentErrorCode.INSUFFICIENT_FUNDS;
        }
        if (code == WalletResultCode.WALLET_LOCKED) {
            return PaymentErrorCode.WALLET_LOCKED;
        }
        return PaymentErrorCode.WITHDRAW_FAILED;
    }

    private WithdrawOrder newOrder(long userId, int userLine, String currency, BigDecimal amount, String channelCode,
                                   String payeeRef, String clientIp, String deviceId) {
        long id = idGenerator.nextId();
        LocalDateTime now = BingoTime.now();
        WithdrawOrder order = new WithdrawOrder();
        order.setId(id);
        order.setOrderNo("W" + id);
        order.setUserId(userId);
        order.setUserLine(userLine);
        order.setCurrency(currency);
        order.setAmount(amount);
        // TODO: fee schedule per channel; the fee would be deducted from the payout, not frozen on top of the amount.
        order.setFee(Money.ZERO);
        order.setChannelCode(channelCode);
        order.setPayeeRef(payeeRef);
        order.setStatus(WithdrawStatus.CREATED);
        order.setClientIp(Texts.truncate(clientIp, 64));
        order.setDeviceId(Texts.truncate(deviceId, 128));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }
}
