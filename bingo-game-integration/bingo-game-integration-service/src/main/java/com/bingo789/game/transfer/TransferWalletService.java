package com.bingo789.game.transfer;

import com.bingo789.common.core.Money;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import com.bingo789.game.adapter.TransferCapable;
import com.bingo789.game.adapter.model.Transfer;
import com.bingo789.game.provider.ProviderRegistry;
import com.bingo789.game.provider.ProviderRuntime;
import com.bingo789.game.wallet.PlayerIds;
import com.bingo789.wallet.api.WalletClient;
import com.bingo789.wallet.api.dto.PlatformTxnCommand;
import com.bingo789.wallet.api.dto.WalletResult;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Transfer-wallet mode. The main risk is money "stuck at the provider" (掉单) when a call times out, so
 * every step is idempotent on orderNo and every uncertain step leaves the order in a state the
 * {@link TransferRecoveryJob} knows how to finish. Rules:
 * <ul>
 *   <li>never refund a transfer-in whose provider outcome is unknown — query first;</li>
 *   <li>never credit a transfer-out before the provider confirmed the debit.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferWalletService {

    private final TransferOrderMapper mapper;
    private final WalletClient walletClient;
    private final ProviderRegistry registry;
    private final SnowflakeIdGenerator idGenerator;

    /** @param userLine the player's line (PlayerStatusView.userLine), stored on the order */
    public TransferOrder transferIn(ProviderRuntime provider, long userId, int userLine, String currency, BigDecimal amount) {
        TransferOrder order = create(provider, userId, userLine, currency, Money.normalizePositive(amount), TransferOrder.DIRECTION_IN, "TI");
        advance(provider, order);
        return mapper.findByOrderNo(order.getOrderNo());
    }

    public TransferOrder transferOut(ProviderRuntime provider, long userId, int userLine, String currency, BigDecimal amount) {
        TransferOrder order = create(provider, userId, userLine, currency, Money.normalizePositive(amount), TransferOrder.DIRECTION_OUT, "TO");
        advance(provider, order);
        return mapper.findByOrderNo(order.getOrderNo());
    }

    /** Called by the recovery job for orders stuck in a non-terminal state. */
    public void recover(TransferOrder order) {
        advance(registry.require(order.getProviderCode()), order);
    }

    private void advance(ProviderRuntime provider, TransferOrder order) {
        if (TransferOrder.DIRECTION_IN.equals(order.getDirection())) {
            advanceIn(provider, order);
        } else {
            advanceOut(provider, order);
        }
    }

    // ------------------------------------------------------------------ platform -> provider

    private void advanceIn(ProviderRuntime provider, TransferOrder order) {
        String status = order.getStatus();
        if (TransferOrder.INIT.equals(status)) {
            WalletResult debit;
            try {
                debit = walletClient.platformTxn(walletTxn(provider, order, TxnType.TRANSFER_OUT, order.getOrderNo()));
            } catch (Exception e) {
                mapper.recordAttempt(order.getOrderNo(), "wallet debit unknown: " + e.getMessage());
                return;
            }
            if (!debit.isSuccess()) {
                mapper.transition(order.getOrderNo(), TransferOrder.INIT, TransferOrder.FAILED, null, debit.code().name());
                return;
            }
            if (!moved(order, TransferOrder.INIT, TransferOrder.WALLET_DEBITED, null, null)) {
                return;
            }
            status = TransferOrder.WALLET_DEBITED;
        }
        if (!TransferOrder.WALLET_DEBITED.equals(status) && !TransferOrder.UNKNOWN.equals(status)) {
            return;
        }
        TransferCapable api = provider.transferApi();
        Transfer.Request request = request(order);
        String from = status;
        Transfer.Result result = callProvider(provider, () -> TransferOrder.UNKNOWN.equals(from)
                ? api.queryTransfer(order.getOrderNo(), provider.client())
                : api.transferIn(request, provider.client()));
        switch (result.state()) {
            case SUCCEEDED -> moved(order, from, TransferOrder.SUCCEEDED, result.providerRef(), null);
            // the provider definitely did not take the money: give it back
            case FAILED, NOT_FOUND -> refund(provider, order, from, result.message());
            case UNKNOWN -> {
                if (!from.equals(TransferOrder.UNKNOWN)) {
                    moved(order, from, TransferOrder.UNKNOWN, null, result.message());
                } else {
                    mapper.recordAttempt(order.getOrderNo(), result.message());
                }
            }
        }
    }

    private void refund(ProviderRuntime provider, TransferOrder order, String from, String reason) {
        try {
            WalletResult credit = walletClient.platformTxn(walletTxn(provider, order, TxnType.TRANSFER_IN, order.getOrderNo() + ":refund"));
            if (credit.isSuccess()) {
                moved(order, from, TransferOrder.REFUNDED, null, reason);
            } else {
                log.error("transfer refund refused by wallet, manual action needed: order={}, code={}", order.getOrderNo(), credit.code());
                mapper.recordAttempt(order.getOrderNo(), "refund refused: " + credit.code());
            }
        } catch (Exception e) {
            mapper.recordAttempt(order.getOrderNo(), "refund unknown: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ provider -> platform

    private void advanceOut(ProviderRuntime provider, TransferOrder order) {
        String status = order.getStatus();
        if (TransferOrder.INIT.equals(status) || TransferOrder.UNKNOWN.equals(status)) {
            TransferCapable api = provider.transferApi();
            Transfer.Request request = request(order);
            String from = status;
            // re-sending INIT is safe: the provider dedupes on orderNo
            Transfer.Result result = callProvider(provider, () -> TransferOrder.UNKNOWN.equals(from)
                    ? api.queryTransfer(order.getOrderNo(), provider.client())
                    : api.transferOut(request, provider.client()));
            switch (result.state()) {
                case SUCCEEDED -> {
                    if (!moved(order, from, TransferOrder.PROVIDER_DONE, result.providerRef(), null)) {
                        return;
                    }
                    status = TransferOrder.PROVIDER_DONE;
                }
                case FAILED, NOT_FOUND -> {
                    moved(order, from, TransferOrder.FAILED, null, result.message());
                    return;
                }
                case UNKNOWN -> {
                    if (!from.equals(TransferOrder.UNKNOWN)) {
                        moved(order, from, TransferOrder.UNKNOWN, null, result.message());
                    } else {
                        mapper.recordAttempt(order.getOrderNo(), result.message());
                    }
                    return;
                }
            }
        }
        if (TransferOrder.PROVIDER_DONE.equals(status)) {
            try {
                WalletResult credit = walletClient.platformTxn(walletTxn(provider, order, TxnType.TRANSFER_IN, order.getOrderNo()));
                if (credit.isSuccess()) {
                    moved(order, TransferOrder.PROVIDER_DONE, TransferOrder.SUCCEEDED, null, null);
                } else {
                    log.error("transfer-out credit refused by wallet, manual action needed: order={}, code={}", order.getOrderNo(), credit.code());
                    mapper.recordAttempt(order.getOrderNo(), "credit refused: " + credit.code());
                }
            } catch (Exception e) {
                mapper.recordAttempt(order.getOrderNo(), "wallet credit unknown: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private TransferOrder create(ProviderRuntime provider, long userId, int userLine, String currency, BigDecimal amount,
                                 String direction, String prefix) {
        TransferOrder order = new TransferOrder();
        order.setId(idGenerator.nextId());
        order.setOrderNo(prefix + order.getId());
        order.setUserId(userId);
        order.setUserLine(userLine);
        order.setProviderCode(provider.code());
        order.setCurrency(currency);
        order.setDirection(direction);
        order.setAmount(amount);
        order.setStatus(TransferOrder.INIT);
        order.setAttempts(0);
        mapper.insert(order);
        return order;
    }

    private boolean moved(TransferOrder order, String from, String to, String providerRef, String error) {
        boolean moved = mapper.transition(order.getOrderNo(), from, to, providerRef, error) == 1;
        if (!moved) {
            log.info("transfer order {} already moved on from {}, skipping", order.getOrderNo(), from);
        }
        return moved;
    }

    private static Transfer.Result callProvider(ProviderRuntime provider, java.util.function.Supplier<Transfer.Result> call) {
        try {
            return provider.client().execute(call);
        } catch (Exception e) {
            return Transfer.Result.unknown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Transfer.Request request(TransferOrder order) {
        return new Transfer.Request(order.getOrderNo(), PlayerIds.encode(order.getUserId()), order.getCurrency(), order.getAmount());
    }

    private static PlatformTxnCommand walletTxn(ProviderRuntime provider, TransferOrder order, TxnType type, String bizNo) {
        return new PlatformTxnCommand(order.getUserId(), order.getCurrency(), type, "TRANSFER:" + provider.code(),
                bizNo, order.getAmount(), order.getDirection() + " " + order.getOrderNo());
    }
}
