package com.bingo789.lobby.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.lobby.entity.Game;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface GameMapper extends BaseMapper<Game> {

    /**
     * Catalogue sync upsert. New games are inserted OFFLINE so ops review them before they go live
     * (game certification is a licence requirement); status, sort and game_type of existing games are never touched.
     */
    @Insert("""
            INSERT INTO game (id, provider_code, game_code, name, category, game_type, theoretical_rtp, thumbnail_url,
                              status, mobile_supported, desktop_supported, sort, created_at, updated_at)
            VALUES (#{id}, #{providerCode}, #{gameCode}, #{name}, #{category}, #{gameType}, #{theoreticalRtp}, #{thumbnailUrl},
                    'OFFLINE', #{mobileSupported}, #{desktopSupported}, 0, #{updatedAt}, #{updatedAt}) AS new
            ON DUPLICATE KEY UPDATE name = new.name, category = new.category, theoretical_rtp = new.theoretical_rtp,
                thumbnail_url = new.thumbnail_url, mobile_supported = new.mobile_supported,
                desktop_supported = new.desktop_supported, updated_at = new.updated_at
            """)
    int upsertFromProvider(Game game);

    @Update("UPDATE game SET status = #{status}, updated_at = #{now} WHERE id = #{id}")
    int setStatus(@Param("id") long id, @Param("status") String status, @Param("now") LocalDateTime now);
}
