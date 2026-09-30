package com.bingo789.kyc.mapper;

import com.bingo789.kyc.domain.ConfigEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConfigMapper {

    @Select("SELECT cfg_name, cfg_value FROM config")
    List<ConfigEntry> selectAll();
}
