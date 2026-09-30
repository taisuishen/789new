package com.bingo789.betrecord.round;

import com.bingo789.betrecord.catalog.GameCatalog;
import com.bingo789.betrecord.catalog.GameInfo;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.mq.Topics;
import com.bingo789.common.mq.event.WalletTxnEvent;
import com.bingo789.common.mybatis.MasterRoute;
import com.bingo789.common.mybatis.shard.ShardTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Projects the wallet ledger stream into game rounds. The topic is keyed by userId, so all events of a round
 * arrive in ledger order on one partition and are applied sequentially here. Each event is applied on the shard
 * that owns its user (bingo.shard.*), so the per-event transaction touches exactly one database.
 * <p>
 * A record that fails to apply is reported with its batch index: offsets before it are committed and the
 * error handler retries it with back-off (see KafkaConfig). Records are never skipped on transient errors,
 * because a missing ledger row would leave a round wrong forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletTxnConsumer {

    private final RoundService roundService;
    private final GameCatalog gameCatalog;
    private final ShardTemplate shards;

    @KafkaListener(id = "bet-record-wallet-txn", groupId = "bingo-bet-record", topics = Topics.WALLET_TXN,
            batch = "true", concurrency = "${bingo.bet-record.consumer-concurrency:3}")
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> record = records.get(i);
            WalletTxnEvent event = parse(record);
            if (event == null) {
                continue;
            }
            RoundEffect effect = RoundEffect.of(event);
            if (effect == null) {
                continue;
            }
            try {
                // outside the transaction: a (rare, throttled) catalogue load must not hold a database connection
                GameInfo game = gameCatalog.lookup(event.providerCode(), event.gameCode());
                // a shard being moved rejects the write; the error handler retries it until the move is done
                shards.forUserWrite(event.userId(), () -> MasterRoute.run(() -> {
                    roundService.apply(event, effect, game);
                    return null;
                }));
            } catch (RuntimeException e) {
                throw new BatchListenerFailedException("failed to apply wallet txn " + event.id(), e, i);
            }
        }
    }

    /** A malformed event is a producer bug; it is logged with its coordinates for replay and skipped. */
    private static WalletTxnEvent parse(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            WalletTxnEvent event = JsonUtils.fromJson(record.value(), WalletTxnEvent.class);
            if (event.createdAt() == null) {
                throw new IllegalArgumentException("createdAt is missing");
            }
            return event;
        } catch (RuntimeException e) {
            log.error("malformed wallet txn event skipped at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.toString());
            return null;
        }
    }
}
