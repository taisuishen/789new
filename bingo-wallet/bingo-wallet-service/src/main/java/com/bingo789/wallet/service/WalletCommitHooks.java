package com.bingo789.wallet.service;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.wallet.config.WalletProperties;
import com.bingo789.wallet.domain.Wallet;
import com.bingo789.wallet.domain.WalletTxn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Local development only (bingo.wallet.events.after-commit-publish): publishes the ledger rows to Kafka after the
 * transaction committed. Production has no after-commit work: the events come from the binlog (Flink CDC).
 */
@Slf4j
@Component
public class WalletCommitHooks {

    private final WalletProperties properties;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate;

    public WalletCommitHooks(WalletProperties properties, ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void register(Wallet wallet, List<WalletTxn> txns) {
        if (!properties.events().afterCommitPublish() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // synchronous on purpose: keeps per-user ordering, and this path is for local development only
                publish(txns);
            }
        });
    }

    private void publish(List<WalletTxn> txns) {
        KafkaTemplate<String, String> template = kafkaTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        for (WalletTxn txn : txns) {
            try {
                template.send(Topics.WALLET_TXN, String.valueOf(txn.getUserId()), JsonUtils.toJson(toEvent(txn)));
            } catch (Exception e) {
                log.warn("after-commit publish failed for txn {}", txn.getId(), e);
            }
        }
    }

    static WalletTxnEvent toEvent(WalletTxn txn) {
        return new WalletTxnEvent(txn.getId(), txn.getUserId(), txn.getUserLine(), txn.getCurrency(), txn.getTxnType(), txn.getDirection(),
                txn.getAmount(), txn.getBalanceAfter(), txn.getProviderCode(), txn.getProviderTxnId(), txn.getRoundId(),
                txn.getGameCode(), txn.getRefTxnId(), Boolean.TRUE.equals(txn.getRoundClosed()), txn.getStatus(),
                txn.getCreatedAt().toInstant(BingoTime.ZONE));
    }
}
