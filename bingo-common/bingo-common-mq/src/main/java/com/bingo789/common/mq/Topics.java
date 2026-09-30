package com.bingo789.common.mq;

/**
 * Kafka topic catalog (DMS for Kafka, the only message broker).
 * <ul>
 *   <li>Data streams: high volume, key = userId. {@link #WALLET_TXN} is produced by Flink CDC from the wallet_txn
 *   binlog, so the wallet never double-writes messages.</li>
 *   <li>Business events: low volume, written through the transactional outbox, consumed with the
 *   {@code bizEventListenerFactory} (retries, then {@code <topic>.DLT}).</li>
 * </ul>
 */
public final class Topics {

    // ---------- Kafka: data streams (message key = userId, so per-user order is preserved) ----------
    /** wallet_txn change stream (Flink CDC). Payload: {@code WalletTxnEvent}. */
    public static final String WALLET_TXN = "bingo.wallet.txn";
    /** Round settled/cancelled by bet-record. Payload: {@code RoundSettledEvent}. */
    public static final String ROUND_SETTLED = "bingo.round.settled";
    /** Bet records pulled from providers, used for reconciliation. Payload: {@code ProviderBetEvent}. */
    public static final String PROVIDER_BET = "bingo.provider.bet";

    // ---------- Kafka: business events, one topic per event type (written through the transactional outbox,
    // message key = order no / biz no; consumers are idempotent on it). Failed records end up in <topic>.DLT. ----------
    /** Payload: {@code DepositSucceededEvent}. */
    public static final String DEPOSIT_SUCCEEDED = "bingo.payment.deposit-succeeded";
    /** Payload: {@code WithdrawRequestedEvent}. */
    public static final String WITHDRAW_REQUESTED = "bingo.payment.withdraw-requested";
    /** Payload: {@code WithdrawFinishedEvent} (status SUCCEEDED, FAILED or REJECTED). */
    public static final String WITHDRAW_FINISHED = "bingo.payment.withdraw-finished";
    /** Payload: {@code BonusGrantedEvent}. */
    public static final String BONUS_GRANTED = "bingo.promotion.bonus-granted";

    private Topics() {
    }
}
