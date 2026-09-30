package com.bingo789.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.bingo789.common.core.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(BingoMybatisProperties.class)
public class BingoMybatisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(BingoMybatisProperties properties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // master routing must run before pagination rewrites the statement
        interceptor.addInnerInterceptor(new ForceMasterInnerInterceptor(properties.forceMaster(), properties.masterHint()));
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator snowflakeIdGenerator(BingoMybatisProperties properties) {
        long workerId = properties.workerId();
        if (workerId < 0) {
            workerId = deriveWorkerIdFromHost();
            log.warn("bingo.mybatis.worker-id not set, derived {} from hostname. Configure an explicit id in production.", workerId);
        }
        return new SnowflakeIdGenerator(workerId);
    }

    /** Makes {@code @TableId(type = IdType.ASSIGN_ID)} use our snowflake instead of MyBatis-Plus' MAC-based one. */
    @Bean
    @ConditionalOnMissingBean
    public IdentifierGenerator identifierGenerator(SnowflakeIdGenerator snowflake) {
        return entity -> snowflake.nextId();
    }

    private static long deriveWorkerIdFromHost() {
        try {
            return Math.floorMod(InetAddress.getLocalHost().getHostName().hashCode(), SnowflakeIdGenerator.MAX_WORKER_ID + 1);
        } catch (Exception e) {
            return 0L;
        }
    }
}
