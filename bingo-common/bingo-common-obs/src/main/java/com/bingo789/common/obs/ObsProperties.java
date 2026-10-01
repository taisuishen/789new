package com.bingo789.common.obs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Huawei OBS (bingo.obs.*).
 *
 * @param endpoint      regional endpoint, e.g. https://obs.ap-southeast-3.myhuaweicloud.com
 * @param bucket        bucket name; keep KYC buckets PRIVATE (identity documents are sensitive personal data under
 *                      the Philippine Data Privacy Act): objects are then only reachable through signed URLs
 * @param accessKey     AK; a {@code dew:csms/<name>} reference in production. Leave both keys empty on CCE to use the
 *                      node's IAM agency (temporary credentials from the ECS metadata service) instead of static keys
 * @param secretKey     SK, same rules as the access key
 * @param publicBaseUrl custom domain / CDN in front of a PUBLIC bucket (e.g. https://img.789bingo.com); empty = the
 *                      bucket is private and every URL handed out is a signed, expiring URL
 * @param signedUrlTtl  lifetime of signed GET URLs handed to clients and workers
 * @param maxImageSize  upper bound of an uploaded image
 * @param secretsPath   where the DEW/CSMS add-on mounts secrets
 * @param localDir      LOCAL DEVELOPMENT ONLY: store objects as files in this directory instead of OBS
 *                      ({@link LocalDiskStorage}); endpoint, bucket and keys are then not used
 * @param localBaseUrl  public base URL the local files are served under; empty = read {@code <local-dir>/.base-url}
 * @param localSigningKey HMAC key of the local URLs (>= 32 characters), shared with the server of the files
 */
@ConfigurationProperties("bingo.obs")
public record ObsProperties(
        @DefaultValue("false") boolean enabled,
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String publicBaseUrl,
        @DefaultValue("15m") Duration signedUrlTtl,
        @DefaultValue("10MB") DataSize maxImageSize,
        @DefaultValue("/mnt/csms") String secretsPath,
        String localDir,
        String localBaseUrl,
        String localSigningKey) {

    public boolean localDisk() {
        return localDir != null && !localDir.isBlank();
    }

    public boolean privateBucket() {
        return publicBaseUrl == null || publicBaseUrl.isBlank();
    }
}
