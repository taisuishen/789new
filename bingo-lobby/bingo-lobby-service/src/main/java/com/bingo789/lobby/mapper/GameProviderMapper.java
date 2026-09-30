package com.bingo789.lobby.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.lobby.entity.GameProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface GameProviderMapper extends BaseMapper<GameProvider> {

    /** Automatic transition: applies only when the provider is currently in {@code expected}. */
    @Update("""
            UPDATE game_provider SET status = #{target}, status_reason = #{reason}, updated_at = #{now}
             WHERE code = #{code} AND status = #{expected}
            """)
    int transitionStatus(@Param("code") String code, @Param("expected") String expected, @Param("target") String target,
                         @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** Operator decision: unconditional. */
    @Update("""
            UPDATE game_provider SET status = #{target}, status_reason = #{reason}, updated_at = #{now}
             WHERE code = #{code}
            """)
    int setStatus(@Param("code") String code, @Param("target") String target, @Param("reason") String reason,
                  @Param("now") LocalDateTime now);
}
