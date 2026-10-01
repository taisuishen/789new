package com.bingo789.common.job;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the XXL-Job executor when {@code xxl.job.admin.addresses} is configured and non-empty.
 * Job handlers are plain beans with {@code @XxlJob("name")} methods.
 * <p>
 * The executor port runs any registered job for whoever knows the access token, so an executor without a real token
 * refuses to start instead of accepting unauthenticated triggers.
 */
@AutoConfiguration
@ConditionalOnExpression("'${xxl.job.admin.addresses:}' != ''")
public class BingoXxlJobAutoConfiguration {

    static final int MIN_TOKEN_LENGTH = 16;

    @Bean
    @ConditionalOnMissingBean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.access-token:}") String accessToken,
            @Value("${xxl.job.executor.appname:${spring.application.name}}") String appName,
            @Value("${xxl.job.executor.address:}") String address,
            @Value("${xxl.job.executor.ip:}") String ip,
            @Value("${xxl.job.executor.port:9999}") int port,
            @Value("${xxl.job.executor.log-path:/data/applogs/xxl-job}") String logPath,
            @Value("${xxl.job.executor.log-retention-days:7}") int logRetentionDays) {
        if (accessToken == null || accessToken.strip().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("xxl.job.access-token (XXL_JOB_TOKEN) must be set to at least "
                    + MIN_TOKEN_LENGTH + " characters when xxl.job.admin.addresses is configured");
        }
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
