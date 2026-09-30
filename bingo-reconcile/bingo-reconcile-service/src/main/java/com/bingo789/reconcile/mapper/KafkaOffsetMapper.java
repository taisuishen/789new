package com.bingo789.reconcile.mapper;

import com.bingo789.reconcile.model.KafkaOffsetRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Consumed Kafka offsets, stored in the same database (and transaction) as the aggregates they produced.
 * Composite primary key, so no BaseMapper.
 */
@Mapper
public interface KafkaOffsetMapper {

    @Select("""
            <script>
            SELECT partition_no, next_offset FROM kafka_offset
             WHERE consumer_group = #{group} AND topic = #{topic}
               AND partition_no IN
               <foreach collection="partitions" item="p" open="(" separator="," close=")">#{p}</foreach>
            </script>
            """)
    List<KafkaOffsetRow> find(@Param("group") String group, @Param("topic") String topic,
                              @Param("partitions") Collection<Integer> partitions);

    /**
     * Locks the offset rows of the batch's partitions. A consumer that lost a partition in a rebalance but is
     * still finishing its batch blocks here and then sees the advanced offsets, so no record is applied twice.
     */
    @Select("""
            <script>
            SELECT partition_no, next_offset FROM kafka_offset
             WHERE consumer_group = #{group} AND topic = #{topic}
               AND partition_no IN
               <foreach collection="partitions" item="p" open="(" separator="," close=")">#{p}</foreach>
             ORDER BY partition_no
               FOR UPDATE
            </script>
            """)
    List<KafkaOffsetRow> lock(@Param("group") String group, @Param("topic") String topic,
                              @Param("partitions") Collection<Integer> partitions);

    @Insert("""
            <script>
            INSERT INTO kafka_offset (consumer_group, topic, partition_no, next_offset) VALUES
            <foreach collection="offsets" index="partition" item="offset" separator=",">
              (#{group}, #{topic}, #{partition}, #{offset})
            </foreach>
            AS new
            ON DUPLICATE KEY UPDATE next_offset = GREATEST(kafka_offset.next_offset, new.next_offset)
            </script>
            """)
    int save(@Param("group") String group, @Param("topic") String topic, @Param("offsets") Map<Integer, Long> offsets);
}
