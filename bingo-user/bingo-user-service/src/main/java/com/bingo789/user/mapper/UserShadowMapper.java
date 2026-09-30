package com.bingo789.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.user.entity.UserShadow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserShadowMapper extends BaseMapper<UserShadow> {

    @Select("SELECT * FROM user_shadow WHERE user_id = #{userId} AND user_line = #{userLine}")
    UserShadow selectByUserAndLine(@Param("userId") long userId, @Param("userLine") int userLine);

    @Select("SELECT * FROM user_shadow WHERE user_id = #{userId} ORDER BY user_line")
    List<UserShadow> selectByUser(@Param("userId") long userId);
}
