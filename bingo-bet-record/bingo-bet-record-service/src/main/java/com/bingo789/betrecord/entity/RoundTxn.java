package com.bingo789.betrecord.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Wallet txn ids already applied to game_round: the exactly-once guard for at-least-once Kafka delivery. */
@Getter
@Setter
@TableName("round_txn")
public class RoundTxn {

    /** wallet_txn.id (WalletTxnEvent.id). */
    @TableId(value = "txn_id", type = IdType.INPUT)
    private Long txnId;
    private String roundId;
    private LocalDateTime createdAt;
}
