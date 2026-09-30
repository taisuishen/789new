package com.bingo789.betrecord.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.betrecord.entity.BetPullCheckpoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface BetPullCheckpointMapper extends BaseMapper<BetPullCheckpoint> {

    /** Never moves the checkpoint backwards (e.g. a delayed run finishing after a newer one). */
    @Insert("""
            INSERT INTO bet_pull_checkpoint (provider_code, window_end, updated_at)
            VALUES (#{providerCode}, #{windowEnd}, #{now}) AS new
            ON DUPLICATE KEY UPDATE window_end = GREATEST(bet_pull_checkpoint.window_end, new.window_end), updated_at = new.updated_at
            """)
    int advance(@Param("providerCode") String providerCode, @Param("windowEnd") LocalDateTime windowEnd,
                @Param("now") LocalDateTime now);
}
