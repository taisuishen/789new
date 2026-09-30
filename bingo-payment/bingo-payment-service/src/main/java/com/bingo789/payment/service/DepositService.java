package com.bingo789.payment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bingo789.common.core.BizException;
import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.common.core.line.UserLine;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.DepositSucceededEvent;
import com.bingo789.common.mq.outbox.OutboxService;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.payment.channel.ChannelOutcome;
import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.channel.DepositInitiation;
import com.bingo789.payment.channel.DepositResult;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.common.PageResult;
import com.bingo789.payment.common.PaymentErrorCode;
import com.bingo789.payment.common.RemoteCalls;
import com.bingo789.payment.common.Texts;
import com.bingo789.payment.common.UserActionLock;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.ChannelDirection;
import com.bingo789.payment.domain.DepositOrder;
import com.bingo789.payment.domain.DepositStatus;
import com.bingo789.payment.mapper.DepositOrderMapper;
import com.bingo789.payment.mapper.PlayerFirstDepositMapper;
import com.bingo789.payment.web.dto.CreateDepositRequest;
import com.bingo789.payment.web.dto.DepositCreatedView;
import com.bingo789.payment.web.dto.DepositOrderView;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    /** Wallet idempotency source for every payment transaction; bizNo is the order number. */
    static final String WALLET_SOURCE = "PAYMENT";
    private static final Duration CREATE_LOCK_TTL = Duration.ofSeconds(10);

    private final DepositOrderMapper depositMapper;
    private final PlayerFirstDepositMapper firstDepositMapper;
    private final ChannelConfigService channelConfigService;
    private final ChannelRegistry channelRegistry;
    private final DepositLimitChecker limitChecker;
    private final UserActionLock userActionLock;
    private final UserClient userClient;
    private final WalletClient walletClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final SnowflakeIdGenerator idGenerator;

    // ------------------------------------------------------------------ player requests

    public DepositCreatedView create(long userId, CreateDepositRequest request, String clientIp, String deviceId) {
        PlayerStatusView player = RemoteCalls.call("user.playerStatus", () -> userClient.playerStatus(userId));
        if (!player.canDeposit()) {
            throw new BizException(PaymentErrorCode.DEPOSIT_NOT_ALLOWED,
                    Texts.hasText(player.reason()) ? player.reason() : PaymentErrorCode.DEPOSIT_NOT_ALLOWED.message());
        }
        String currency = accountCurrency(request.currency(), player.defaultCurrency());
        BigDecimal amount = Money.normalizePositive(request.amount());
        ChannelConfig config = channelConfigService.requireUsable(request.channelCode(), ChannelDirection.DEPOSIT, currency, amount);
        PaymentChannel channel = channelRegistry.require(config.getCode());
        // stored on the order and carried by its events; 0 (not sent by an older user-service) is the default line
        int userLine = UserLine.orDefault(player.userLine());

        // Limit check and insert under one per-player lock; otherwise two parallel requests could both pass the check.
        DepositOrder order = userActionLock.withLock("deposit", userId, CREATE_LOCK_TTL, () -> {
            limitChecker.check(userId, currency, amount);
            DepositOrder created = newOrder(userId, userLine, currency, amount, config.getCode(), clientIp, deviceId);
            depositMapper.insert(created);
            return created;
        });

        DepositInitiation initiation;
        try {
            initiation = channel.createDeposit(order);
        } catch (RuntimeException e) {
            // No payment instructions reached the player. EXPIRED (not FAILED) frees the limit headroom and still
            // lets a payment that arrives anyway be credited through the late-payment path.
            log.warn("channel {} failed to create deposit {}", config.getCode(), order.getOrderNo(), e);
            depositMapper.markExpired(order.getId(), "channel create failed");
            throw new BizException(PaymentErrorCode.CHANNEL_UNAVAILABLE);
        }
        // 0 rows only if a notify already completed the order; nothing to do then
        depositMapper.markPending(order.getId(), initiation.channelOrderNo());
        return new DepositCreatedView(order.getOrderNo(), amount, currency, DepositStatus.PENDING,
                initiation.payUrl(), initiation.qrContent());
    }

    public PageResult<DepositOrderView> listOwn(long userId, long page, long size) {
        Page<DepositOrder> request = PageResult.request(page, size);
        Page<DepositOrder> result = depositMapper.selectPage(request, Wrappers.<DepositOrder>lambdaQuery()
                .eq(DepositOrder::getUserId, userId)
                .orderByDesc(DepositOrder::getCreatedAt));
        return PageResult.of(result, DepositOrderView::of);
    }

    // ------------------------------------------------------------------ channel results

    /**
     * Handles a verified deposit notify.
     *
     * @return true when the notify may be acknowledged; false makes the channel re-notify later
     */
    public boolean onNotify(String channelCode, DepositResult result) {
        String orderNo = result.orderNo();
        DepositOrder order = orderNo == null ? null : MasterRoute.run(() -> depositMapper.selectByOrderNo(orderNo));
        if (order == null || !order.getChannelCode().equals(channelCode)) {
            log.error("ALERT deposit notify from {} for unknown order {} (or an order of another channel)", channelCode, orderNo);
            return false;
        }
        return apply(order, result);
    }

    /** Recovery for orders the channel never notified: query it, then process like a notify; expire when too old. */
    public void recover(DepositOrder order, LocalDateTime expireCreatedBefore) {
        PaymentChannel channel = channelRegistry.find(order.getChannelCode()).orElse(null);
        if (channel == null) {
            log.error("ALERT no channel adapter {} for unsettled deposit {}", order.getChannelCode(), order.getOrderNo());
            return;
        }
        DepositResult result = channel.queryDeposit(order);
        if (!order.getOrderNo().equals(result.orderNo())) {
            log.error("ALERT channel {} answered the query for deposit {} with order {}",
                    order.getChannelCode(), order.getOrderNo(), result.orderNo());
            return;
        }
        apply(order, result);
        if (result.outcome() == ChannelOutcome.PENDING && order.getCreatedAt().isBefore(expireCreatedBefore)) {
            depositMapper.markExpired(order.getId(), "not paid in time");
        }
    }

    private boolean apply(DepositOrder order, DepositResult result) {
        DepositStatus status = order.getStatus();
        if (status == DepositStatus.SUCCEEDED) {
            if (result.outcome() == ChannelOutcome.FAILED) {
                log.error("ALERT deposit {} was credited but channel {} now reports FAILED ({}); manual review required",
                        order.getOrderNo(), order.getChannelCode(), result.reason());
            }
            return true;
        }
        return switch (result.outcome()) {
            case PENDING -> true;
            case FAILED -> {
                depositMapper.markFailed(order.getId(), Texts.truncate(result.reason(), 255));
                yield true;
            }
            case SUCCEEDED -> {
                if (status == DepositStatus.FAILED) {
                    log.error("ALERT deposit {} reported paid after it was marked FAILED; manual review required", order.getOrderNo());
                    yield false;
                }
                yield credit(order, result);
            }
        };
    }

    /**
     * (1) Credit the wallet, idempotent on (PAYMENT, orderNo, DEPOSIT). (2) One local transaction: SUCCEEDED + outbox.
     * If (2) fails after (1), the channel's re-notify or the recovery job replays (1) harmlessly and retries (2).
     * A wallet exception is an unknown outcome and propagates, so the notify is not acknowledged.
     */
    private boolean credit(DepositOrder order, DepositResult result) {
        if (!amountMatches(order, result)) {
            // TODO: route to the ops alerting channel / manual resolution queue instead of relying on log alerts
            log.error("ALERT deposit {} amount mismatch: order {} {}, channel {} reported {} {}; NOT credited",
                    order.getOrderNo(), order.getAmount(), order.getCurrency(), order.getChannelCode(),
                    result.amount(), result.currency());
            return false;
        }
        WalletResult wallet = walletClient.platformTxn(new PlatformTxnCommand(order.getUserId(), order.getCurrency(),
                TxnType.DEPOSIT, WALLET_SOURCE, order.getOrderNo(), order.getAmount(), "deposit via " + order.getChannelCode()));
        if (!wallet.isSuccess()) {
            log.error("ALERT wallet refused deposit credit {}: {} {}", order.getOrderNo(), wallet.code(), wallet.message());
            return false;
        }
        if (order.getStatus() == DepositStatus.EXPIRED) {
            log.warn("late payment credited for expired deposit {}", order.getOrderNo());
        }
        transactionTemplate.executeWithoutResult(tx -> {
            if (depositMapper.markSucceeded(order.getId(), result.channelOrderNo()) == 0) {
                return; // a concurrent notify or recovery run completed it
            }
            boolean first = firstDepositMapper.insertIgnore(order.getUserId(), order.getOrderNo()) == 1;
            if (first) {
                depositMapper.markFirstDeposit(order.getId());
            }
            outboxService.save(Topics.DEPOSIT_SUCCEEDED, order.getOrderNo(),
                    new DepositSucceededEvent(order.getOrderNo(), order.getUserId(), order.getUserLine(), order.getCurrency(),
                            order.getAmount(), order.getChannelCode(), first, Instant.now()));
        });
        return true;
    }

    private static boolean amountMatches(DepositOrder order, DepositResult result) {
        if (result.amount() == null || result.amount().compareTo(order.getAmount()) != 0) {
            return false;
        }
        return result.currency() == null || result.currency().equalsIgnoreCase(order.getCurrency());
    }

    /** RG limits carry no currency, so deposits are accepted in the player's account currency only. */
    private static String accountCurrency(String requested, String accountCurrency) {
        if (!Texts.hasText(accountCurrency)) {
            throw new BizException(PaymentErrorCode.CURRENCY_NOT_SUPPORTED, "player has no account currency");
        }
        if (Texts.hasText(requested) && !requested.equalsIgnoreCase(accountCurrency)) {
            throw new BizException(PaymentErrorCode.CURRENCY_NOT_SUPPORTED,
                    "deposits must be made in the account currency " + accountCurrency);
        }
        return accountCurrency;
    }

    private DepositOrder newOrder(long userId, int userLine, String currency, BigDecimal amount, String channelCode,
                                  String clientIp, String deviceId) {
        long id = idGenerator.nextId();
        LocalDateTime now = BingoTime.now();
        DepositOrder order = new DepositOrder();
        order.setId(id);
        order.setOrderNo("D" + id);
        order.setUserId(userId);
        order.setUserLine(userLine);
        order.setCurrency(currency);
        order.setAmount(amount);
        order.setChannelCode(channelCode);
        order.setStatus(DepositStatus.CREATED);
        order.setFirstDeposit(false);
        order.setClientIp(Texts.truncate(clientIp, 64));
        order.setDeviceId(Texts.truncate(deviceId, 128));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }
}
