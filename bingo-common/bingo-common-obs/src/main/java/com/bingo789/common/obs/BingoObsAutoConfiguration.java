package com.bingo789.common.obs;

import com.bingo789.common.core.secret.SecretRefs;
import com.obs.services.EcsObsCredentialsProvider;
import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Enabled with {@code bingo.obs.enabled=true}. Static AK/SK are resolved through {@link SecretRefs}
 * ({@code dew:csms/<name>}); without keys the client uses the node's IAM agency (recommended on CCE: no long-lived
 * keys in the cluster). With {@code bingo.obs.local-dir} set (local development only) objects are files on disk
 * ({@link LocalDiskStorage}) and no OBS client is created.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "bingo.obs", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ObsProperties.class)
public class BingoObsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${bingo.obs.local-dir:}'.isBlank()")
    public ObsClient obsClient(ObsProperties properties) {
        if (!StringUtils.hasText(properties.endpoint()) || !StringUtils.hasText(properties.bucket())) {
            throw new IllegalStateException("bingo.obs.endpoint and bingo.obs.bucket are required when bingo.obs.enabled=true");
        }
        ObsConfiguration config = new ObsConfiguration();
        config.setEndPoint(properties.endpoint());
        config.setConnectionTimeout(3_000);
        config.setSocketTimeout(30_000);
        config.setMaxErrorRetry(2);
        if (!StringUtils.hasText(properties.accessKey())) {
            return new ObsClient(new EcsObsCredentialsProvider(), config);
        }
        SecretRefs secrets = new SecretRefs(properties.secretsPath());
        return new ObsClient(secrets.resolve(properties.accessKey()), secrets.resolve(properties.secretKey()), config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsStorage obsStorage(ObjectProvider<ObsClient> obsClient, ObsProperties properties) {
        return properties.localDisk() ? new LocalDiskStorage(properties) : new HuaweiObsStorage(obsClient.getObject(), properties);
    }
}
