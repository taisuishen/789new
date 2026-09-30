package com.bingo789.reconcile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.reconcile.entity.ProviderSettlement;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface ProviderSettlementMapper extends BaseMapper<ProviderSettlement> {

    /** Recomputes a DRAFT statement; a CONFIRMED one is never changed by the job. */
    @Insert("""
            INSERT INTO provider_settlement (period, provider_code, currency, ggr, revenue_share_rate, amount_due, status)
            VALUES (#{period}, #{providerCode}, #{currency}, #{ggr}, #{rate}, #{amountDue}, 'DRAFT') AS new
            ON DUPLICATE KEY UPDATE
                ggr = IF(provider_settlement.status = 'DRAFT', new.ggr, provider_settlement.ggr),
                revenue_share_rate = IF(provider_settlement.status = 'DRAFT', new.revenue_share_rate,
                                         provider_settlement.revenue_share_rate),
                amount_due = IF(provider_settlement.status = 'DRAFT', new.amount_due, provider_settlement.amount_due)
            """)
    int upsertDraft(@Param("period") String period, @Param("providerCode") String providerCode,
                    @Param("currency") String currency, @Param("ggr") BigDecimal ggr, @Param("rate") BigDecimal rate,
                    @Param("amountDue") BigDecimal amountDue);
}
