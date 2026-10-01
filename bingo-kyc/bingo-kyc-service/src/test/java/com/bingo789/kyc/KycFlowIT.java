package com.bingo789.kyc;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
import com.bingo789.common.obs.ObsStorage;
import com.bingo789.kyc.config.KycConfigService;
import com.bingo789.kyc.domain.KycRecord;
import com.bingo789.kyc.job.KycJobs;
import com.bingo789.kyc.mapper.KycRecordMapper;
import com.bingo789.kyc.runpod.RunPodClient;
import com.bingo789.kyc.runpod.RunPodException;
import com.bingo789.kyc.runpod.RunPodJob;
import com.bingo789.kyc.service.KycResultService;
import com.bingo789.kyc.service.KycSubmissionService;
import com.bingo789.kyc.web.dto.SubmitKycRequest;
import com.bingo789.user.api.UserClient;
import com.bingo789.user.api.dto.PlayerStatusView;
import com.bingo789.user.api.dto.UpdateKycStatusCommand;
import com.bingo789.user.api.enums.AccountStatus;
import com.bingo789.user.api.enums.KycStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Statuses 0 待提交 -> 1 处理中 -> 2 / 3 / 4 against MySQL, with RunPod, OBS and user-service mocked. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "bingo.obs.enabled=false",
        "bingo.mybatis.force-master=false",
        "bingo.mybatis.worker-id=4",
        // test-only PII keys (plain values instead of dew:csms references)
        "bingo.pii.keys.k1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "bingo.pii.index-key=kyc-flow-it-index-key-0123456789abcdef"
})
class KycFlowIT {

    private static final AtomicLong USER_SEQ = new AtomicLong(7_000_000);

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("bingo_kyc")
            .withCommand("--default-time-zone=+08:00")
            .withUrlParam("connectionTimeZone", "%2B08:00")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../../deploy/sql/11_kyc.sql"),
                    "/docker-entrypoint-initdb.d/11_kyc.sql");

    @MockitoBean
    RunPodClient runPod;

    @MockitoBean
    ObsStorage storage;

    @MockitoBean
    UserClient userClient;

    @Autowired
    KycSubmissionService submissions;

    @Autowired
    KycResultService results;

    @Autowired
    KycJobs jobs;

    @Autowired
    KycRecordMapper mapper;

    @Autowired
    KycConfigService config;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void platformTimeZone() {
        BingoTime.applyJvmDefault();
    }

    @BeforeEach
    void settings() {
        reset(runPod, storage, userClient);
        // the seed rows hold placeholders; give the test real-looking values
        jdbc.update("UPDATE config SET cfg_value = 'ep-test' WHERE cfg_name = 'RunPodEndpointId'");
        jdbc.update("UPDATE config SET cfg_value = 'https://cb.example.com/callback/runpod/kyc' WHERE cfg_name = 'RunPodWebhookUrl'");
        jdbc.update("UPDATE config SET cfg_value = 'plain-test-token' WHERE cfg_name = 'RunPodWebhookToken'");
        config.refresh();
        when(storage.keyOf(anyString())).thenAnswer(i -> i.getArgument(0));
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.signedUrl(anyString(), any())).thenAnswer(i -> "https://signed/" + i.getArgument(0));
    }

    @Test
    void acceptedSubmissionIsProcessingThenApprovedAndTheUserIsVerified() {
        long user = player();
        when(runPod.run(any(), anyString(), any())).thenReturn("job-ok-" + user);

        KycRecord record = submissions.submit(user, request(user));
        assertThat(record.getStatus()).isEqualTo(1);
        assertThat(record.getRunpodJobId()).isEqualTo("job-ok-" + user);

        results.onJob(completed(record.getId(), "job-ok-" + user, "approved"));
        KycRecord done = mapper.selectById(record.getId());
        assertThat(done.getStatus()).isEqualTo(2);
        assertThat(done.getGender()).isEqualTo("m");
        assertThat(done.getUserSynced()).isEqualTo(1);
        assertThat(lastKycStatus(user)).isEqualTo(KycStatus.VERIFIED);

        // a late duplicate delivery changes nothing
        results.onJob(completed(record.getId(), "job-ok-" + user, "rejected"));
        assertThat(mapper.selectById(record.getId()).getStatus()).isEqualTo(2);
    }

    @Test
    void failedSubmissionWaitsAsPendingAndTimesOutAsRunPodFailure() {
        long user = player();
        when(runPod.run(any(), anyString(), any())).thenThrow(new RunPodException("HTTP 503"));

        KycRecord record = submissions.submit(user, request(user));
        assertThat(record.getStatus()).isZero();
        assertThat(record.getSubmitAttempts()).isEqualTo(1);
        assertThat(record.getNextSubmitAt()).isAfter(record.getCreatedAt());
        assertThat(lastKycStatus(user)).isEqualTo(KycStatus.PENDING);
        // one open submission per player
        assertThatThrownBy(() -> submissions.submit(user, request(user)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(KycErrorCode.SUBMISSION_IN_PROGRESS);

        jdbc.update("UPDATE kyc_record SET created_at = created_at - INTERVAL 6 MINUTE WHERE id = ?", record.getId());
        jobs.kycTimeoutJob();

        KycRecord expired = mapper.selectById(record.getId());
        assertThat(expired.getStatus()).isEqualTo(4);
        assertThat(expired.getLastError()).contains("no RunPod result");
        assertThat(lastKycStatus(user)).isEqualTo(KycStatus.REJECTED);
    }

    @Test
    void retryJobResubmitsDueRecordsAndWorkerErrorsAreRunPodFailures() {
        long user = player();
        when(runPod.run(any(), anyString(), any())).thenThrow(new RunPodException("timeout")).thenReturn("job-r-" + user);

        KycRecord record = submissions.submit(user, request(user));
        assertThat(record.getStatus()).isZero();
        jdbc.update("UPDATE kyc_record SET next_submit_at = NOW(3) - INTERVAL 1 SECOND WHERE id = ?", record.getId());
        jobs.kycSubmitRetryJob();
        assertThat(mapper.selectById(record.getId()).getStatus()).isEqualTo(1);

        results.onJob(completed(record.getId(), "job-r-" + user, "error"));
        assertThat(mapper.selectById(record.getId()).getStatus()).isEqualTo(4);
        assertThat(lastKycStatus(user)).isEqualTo(KycStatus.REJECTED);
    }

    private long player() {
        long user = USER_SEQ.incrementAndGet();
        when(userClient.playerStatus(user)).thenReturn(new PlayerStatusView(user, 1, "PHP", AccountStatus.ACTIVE,
                KycStatus.NONE, false, false, false, null, "KYC_NONE"));
        when(userClient.updateKycStatus(eq(user), any())).thenReturn(true);
        return user;
    }

    private KycStatus lastKycStatus(long user) {
        ArgumentCaptor<UpdateKycStatusCommand> captor = ArgumentCaptor.forClass(UpdateKycStatusCommand.class);
        verify(userClient, atLeastOnce()).updateKycStatus(eq(user), captor.capture());
        List<UpdateKycStatusCommand> all = captor.getAllValues();
        return all.getLast().status();
    }

    private static SubmitKycRequest request(long user) {
        String folder = "kyc/" + user + "/20261001/";
        return new SubmitKycRequest(23, folder + "front.jpg", null, folder + "selfie.jpg");
    }

    private static RunPodJob completed(long recordId, String jobId, String decision) {
        String output = """
                {"requestId":"%d","success":%s,"decision":"%s","reasons":[],"reasonsEn":[],"gender":"m",
                 "result":{"face_match":{"verified":true,"distance":0.21,"threshold":0.5}}}
                """.formatted(recordId, "approved".equals(decision), decision);
        return new RunPodJob(jobId, "COMPLETED", JsonUtils.mapper().readTree(output), null);
    }
}
