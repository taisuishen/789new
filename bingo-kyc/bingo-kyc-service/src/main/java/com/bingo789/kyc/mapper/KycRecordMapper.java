package com.bingo789.kyc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.kyc.domain.KycRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Every state change is a conditional UPDATE on the expected current status, so the webhook, the status poll, the
 * retry job and the timeout job can race freely: exactly one of them wins each transition.
 */
@Mapper
public interface KycRecordMapper extends BaseMapper<KycRecord> {

    /** 0 -> 1: RunPod accepted the job. */
    @Update("""
            UPDATE kyc_record
               SET status = 1, runpod_job_id = #{jobId}, submit_attempts = submit_attempts + 1, submitted_at = #{now},
                   next_submit_at = NULL, last_error = NULL
             WHERE id = #{id} AND status = 0
            """)
    int markSubmitted(@Param("id") long id, @Param("jobId") String jobId, @Param("now") LocalDateTime now);

    /** Stays 0: the attempt failed, retry at {@code nextAt}. */
    @Update("""
            UPDATE kyc_record
               SET submit_attempts = submit_attempts + 1, last_error = #{error}, next_submit_at = #{nextAt}
             WHERE id = #{id} AND status = 0
            """)
    int markSubmitFailed(@Param("id") long id, @Param("error") String error, @Param("nextAt") LocalDateTime nextAt);

    /** 1 -> 0: RunPod gave up on the job (FAILED / CANCELLED / TIMED_OUT) before the deadline; resubmit it. */
    @Update("""
            UPDATE kyc_record
               SET status = 0, last_error = #{error}, next_submit_at = #{nextAt}
             WHERE id = #{id} AND status = 1 AND runpod_job_id = #{jobId}
            """)
    int backToSubmit(@Param("id") long id, @Param("jobId") String jobId, @Param("error") String error,
                     @Param("nextAt") LocalDateTime nextAt);

    /**
     * 0 / 1 -> 2, 3 or 4 with the worker's result. Accepted from 0 as well: a job may have been accepted by RunPod
     * even though the call that submitted it failed or timed out on our side.
     */
    @Update("""
            UPDATE kyc_record
               SET status = #{status}, decision = #{decision}, reasons = #{reasons}, reasons_en = #{reasonsEn},
                   gender = #{gender}, face_distance = #{faceDistance}, face_threshold = #{faceThreshold},
                   result_json = #{resultJson}, completed_at = #{now}, next_submit_at = NULL, user_synced = 0
             WHERE id = #{id} AND status IN (0, 1)
            """)
    int complete(@Param("id") long id, @Param("status") int status, @Param("decision") String decision,
                 @Param("reasons") String reasons, @Param("reasonsEn") String reasonsEn, @Param("gender") String gender,
                 @Param("faceDistance") BigDecimal faceDistance, @Param("faceThreshold") BigDecimal faceThreshold,
                 @Param("resultJson") String resultJson, @Param("now") LocalDateTime now);

    /** 0 / 1 -> 4 (RunPod 响应失败) without a worker result. */
    @Update("""
            UPDATE kyc_record
               SET status = 4, last_error = #{error}, completed_at = #{now}, next_submit_at = NULL, user_synced = 0
             WHERE id = #{id} AND status IN (0, 1)
            """)
    int fail(@Param("id") long id, @Param("error") String error, @Param("now") LocalDateTime now);

    /** user-service confirmed the kyc_status of {@code status}; a newer status keeps user_synced = 0. */
    @Update("UPDATE kyc_record SET user_synced = 1 WHERE id = #{id} AND status = #{status}")
    int markUserSynced(@Param("id") long id, @Param("status") int status);

    @Select("SELECT * FROM kyc_record WHERE runpod_job_id = #{jobId}")
    KycRecord selectByJobId(@Param("jobId") String jobId);

    @Select("SELECT * FROM kyc_record WHERE open_user_id = #{userId}")
    KycRecord selectOpen(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM kyc_record WHERE user_id = #{userId} AND created_at >= #{since}")
    int countSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    @Select("SELECT * FROM kyc_record WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    KycRecord selectLatest(@Param("userId") long userId);

    @Select("SELECT * FROM kyc_record WHERE user_id = #{userId} AND status = 2 ORDER BY id DESC LIMIT 1")
    KycRecord selectLatestApproved(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM kyc_record WHERE user_id = #{userId} AND status = 2")
    int countApproved(@Param("userId") long userId);

    /** Back office; {@code lines} null = every line. */
    @Select("""
            <script>
            SELECT * FROM kyc_record WHERE user_id = #{userId}
            <if test="lines != null">
               AND user_line IN
               <foreach collection="lines" item="line" open="(" separator="," close=")">#{line}</foreach>
            </if>
             ORDER BY id DESC
             LIMIT 50
            </script>
            """)
    List<KycRecord> selectByUser(@Param("userId") long userId, @Param("lines") Collection<Integer> lines);

    @Select("""
            SELECT * FROM kyc_record
             WHERE status = 0 AND next_submit_at <= #{now} AND created_at > #{createdAfter}
             ORDER BY next_submit_at
             LIMIT #{limit}
            """)
    List<KycRecord> selectDueForSubmit(@Param("now") LocalDateTime now, @Param("createdAfter") LocalDateTime createdAfter,
                                       @Param("limit") int limit);

    @Select("""
            SELECT * FROM kyc_record
             WHERE status = 1 AND submitted_at < #{before}
             ORDER BY submitted_at
             LIMIT #{limit}
            """)
    List<KycRecord> selectProcessingSince(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Select("""
            SELECT * FROM kyc_record
             WHERE status IN (0, 1) AND created_at < #{before}
             ORDER BY created_at
             LIMIT #{limit}
            """)
    List<KycRecord> selectExpired(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Select("SELECT * FROM kyc_record WHERE user_synced = 0 AND updated_at < #{before} ORDER BY id LIMIT #{limit}")
    List<KycRecord> selectUserUnsynced(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
