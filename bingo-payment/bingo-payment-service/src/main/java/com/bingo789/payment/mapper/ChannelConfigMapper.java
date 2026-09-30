package com.bingo789.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bingo789.payment.domain.ChannelConfig;
import com.bingo789.payment.domain.ChannelStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ChannelConfigMapper extends BaseMapper<ChannelConfig> {

    default List<ChannelConfig> selectEnabled() {
        return selectList(Wrappers.<ChannelConfig>lambdaQuery()
                .eq(ChannelConfig::getStatus, ChannelStatus.ENABLED)
                .orderByAsc(ChannelConfig::getSort));
    }
}
