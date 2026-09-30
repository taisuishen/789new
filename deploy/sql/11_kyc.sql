USE bingo_kyc;

-- KYC submissions (identity document + selfie) verified by the RunPod serverless worker (bbwave_face).
-- All DATETIME columns hold UTC+8 wall-clock time.
-- PII: the images live in a PRIVATE OBS bucket (only the object keys are stored here); result_json holds the OCR
-- fields (name, ID number, birth date). Field-level encryption with DEW is required before go-live (TODO), and
-- retention follows the licence / Data Privacy Act (RA 10173).

-- status:
--   0 待提交       submission to RunPod failed; kycSubmitRetryJob resubmits (backoff) until the timeout
--   1 处理中       accepted by RunPod (/run), waiting for the webhook (kycStatusPollJob asks /status as a fallback)
--   2 成功         worker decision "approved"
--   3 拒绝         worker decision "rejected"
--   4 RunPod 响应失败  no result within KycTimeoutSeconds (default 5 minutes), or the worker reported an error
--                  (e.g. image download failed); rejected, the player may submit again
-- 2 / 3 / 4 are final. Every final status is pushed to user-service (kyc_status VERIFIED / REJECTED).
CREATE TABLE IF NOT EXISTS kyc_record (
    id              BIGINT        NOT NULL COMMENT 'snowflake; also the requestId sent to RunPod',
    user_id         BIGINT        NOT NULL,
    user_line       INT           NOT NULL DEFAULT 1 COMMENT 'player''s line at submission',
    identity_type   INT           NOT NULL COMMENT 'declared document: 1 driver''s licence, 23 PhilID, ... (worker IDENTITY_TYPE_MAP); others: face match only',
    id_front_key    VARCHAR(255)  NOT NULL COMMENT 'OBS object key',
    id_back_key     VARCHAR(255)  NULL COMMENT 'OBS object key (optional)',
    selfie_key      VARCHAR(255)  NOT NULL COMMENT 'OBS object key',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待提交, 1 处理中, 2 成功, 3 拒绝, 4 RunPod 响应失败',
    runpod_job_id   VARCHAR(64)   NULL COMMENT 'job id of the latest /run',
    submit_attempts INT           NOT NULL DEFAULT 0,
    next_submit_at  DATETIME(3)   NULL COMMENT 'status 0: earliest resubmission',
    last_error      VARCHAR(512)  NULL,
    decision        VARCHAR(16)   NULL COMMENT 'worker decision: approved / rejected / error',
    reasons         JSON          NULL COMMENT 'worker reasons (internal)',
    reasons_en      JSON          NULL COMMENT 'reasons shown to the player',
    gender          CHAR(1)       NULL COMMENT 'm / f / u (selfie)',
    face_distance   DECIMAL(8,4)  NULL,
    face_threshold  DECIMAL(8,4)  NULL,
    result_json     JSON          NULL COMMENT 'full worker report (PII: OCR fields)',
    user_synced     TINYINT       NOT NULL DEFAULT 0 COMMENT '1 once user-service holds the kyc_status of the current status',
    submitted_at    DATETIME(3)   NULL COMMENT 'last successful /run',
    completed_at    DATETIME(3)   NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'the 5-minute timeout counts from here',
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    -- at most one open (0 / 1) submission per player; never written by the application
    open_user_id    BIGINT        GENERATED ALWAYS AS (IF(status IN (0, 1), user_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_user (open_user_id),
    UNIQUE KEY uk_runpod_job (runpod_job_id),
    KEY idx_user (user_id, id),
    KEY idx_status_next (status, next_submit_at),
    KEY idx_status_created (status, created_at),
    KEY idx_user_synced (user_synced, id),
    KEY idx_line_created (user_line, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'KYC submissions';

-- Runtime settings, cached by the service and re-read every 30 s (edit a row, no restart needed).
-- Secrets are references (dew:csms/<name>, resolved from the DEW/CSMS mount or an environment variable), never the
-- secret itself.
CREATE TABLE IF NOT EXISTS config (
    cfg_name   VARCHAR(64)   NOT NULL,
    cfg_value  VARCHAR(1024) NOT NULL,
    cfg_desc   VARCHAR(255)  NULL,
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (cfg_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'KYC runtime settings';

-- Replace the __PLACEHOLDERS__ with the values of your RunPod deployment. INSERT IGNORE: re-running the script
-- never overwrites values already set.
INSERT IGNORE INTO config (cfg_name, cfg_value, cfg_desc) VALUES
    ('RunPodApiBaseUrl',        'https://api.runpod.ai/v2', 'RunPod serverless API base'),
    ('RunPodEndpointId',        '__RUNPOD_ENDPOINT_ID__', 'serverless endpoint of the bbwave_face KYC worker (Dockerfile.runpod)'),
    ('RunPodApiKey',            'dew:csms/bingo-runpod-api-key', 'RunPod API key (secret reference)'),
    ('RunPodWebhookUrl',        'https://__CALLBACK_HOST__:8443/callback/runpod/kyc', 'public URL RunPod posts job results to (ingress bingo-runpod-webhook); empty = results from kycResultPollJob only'),
    ('RunPodWebhookToken',      'dew:csms/bingo-runpod-webhook-token', 'shared token appended to the webhook URL (RunPod does not sign webhooks)'),
    ('RunPodHttpTimeoutMs',     '5000', 'timeout of /run, /status and /cancel calls'),
    ('KycTimeoutSeconds',       '300', 'no result this long after submission -> status 4 (RunPod 响应失败), rejected'),
    ('KycMaxSubmitAttempts',    '10', 'maximum /run attempts per submission'),
    ('KycRetryBaseDelaySeconds','5', 'resubmission backoff: base x 2^(attempts-1), capped at 60 s'),
    ('KycPollAfterSeconds',     '30', 'status 1 older than this without webhook -> ask /status'),
    ('KycImageUrlTtlSeconds',   '900', 'lifetime of the signed OBS URLs handed to the worker'),
    ('KycEnableOcr',            'true', 'OCR for identityType 1 / 23 (worker enableOcr)'),
    ('KycMaxSubmissionsPerDay', '5', 'per player and UTC+8 day (every submission costs GPU time)'),
    ('FaceCompareUrl',          'https://__RUNPOD_POD_ID__-8000.proxy.runpod.net/api/v1/face/compare', 'facecmp resident pod (synchronous face compare)'),
    ('FaceCompareTimeoutMs',    '5000', 'facecmp pod timeout; then fall back to the serverless worker (action=face_compare)'),
    ('FaceCompareFallbackTimeoutMs', '30000', 'serverless /runsync timeout of the fallback');
