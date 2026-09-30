package com.bingo789.reconcile.model;

import lombok.Getter;
import lombok.Setter;

/** kafka_offset row: next offset to apply for one partition of one consumer group. */
@Getter
@Setter
public class KafkaOffsetRow {

    private Integer partitionNo;
    private Long nextOffset;
}
