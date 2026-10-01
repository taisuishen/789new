package com.bingo789.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bingo789.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    /** Login lookup: real players only (shadow accounts have no player_username). */
    @Select("SELECT * FROM user_account WHERE player_username = #{username}")
    UserAccount selectByUsername(@Param("username") String username);

    /** Players already holding any of the unique identities (at most one row per unique key; shadows excluded). */
    @Select("""
            SELECT id, username, email_hash, phone_hash FROM user_account
             WHERE player_username = #{username} OR player_email_hash = #{emailHash} OR player_phone_hash = #{phoneHash}
             LIMIT 3
            """)
    List<UserAccount> selectConflicts(@Param("username") String username,
                                      @Param("emailHash") String emailHash,
                                      @Param("phoneHash") String phoneHash);

    @Select("SELECT user_line FROM user_account WHERE id = #{id}")
    Integer selectUserLine(@Param("id") long id);

    /** Current lines of real players; unknown ids and shadows are left out. */
    @Select("""
            <script>
            SELECT id, user_line FROM user_account
             WHERE account_type = 'PLAYER' AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<UserAccount> selectLines(@Param("ids") Collection<Long> ids);

    /** Conditional move: fails (0 rows) when the line or version changed since the caller read them. */
    @Update("""
            UPDATE user_account
               SET user_line = #{toLine}, line_version = line_version + 1
             WHERE id = #{id} AND account_type = 'PLAYER' AND user_line = #{fromLine} AND line_version = #{version}
            """)
    int moveLine(@Param("id") long id, @Param("fromLine") int fromLine, @Param("toLine") int toLine,
                 @Param("version") int version);

    /** Accounts (players and shadows) with this username on the given lines; {@code lines} null = every line. */
    @Select("""
            <script>
            SELECT * FROM user_account WHERE username = #{username}
            <if test="lines != null">
               AND user_line IN
               <foreach collection="lines" item="line" open="(" separator="," close=")">#{line}</foreach>
            </if>
             ORDER BY id
             LIMIT 20
            </script>
            """)
    List<UserAccount> selectByUsernameInLines(@Param("username") String username, @Param("lines") Collection<Integer> lines);

    /**
     * Guards: a VERIFIED player is only changed by the submission that verified it, and a submission that already
     * finished (VERIFIED / REJECTED) never goes back to PENDING.
     */
    @Update("""
            UPDATE user_account SET kyc_status = #{status}, kyc_vendor_ref = #{reference}
             WHERE id = #{id} AND account_type = 'PLAYER'
               AND NOT (kyc_status = 'VERIFIED' AND (kyc_vendor_ref IS NULL OR kyc_vendor_ref <> #{reference}))
               AND NOT (kyc_vendor_ref = #{reference} AND kyc_status IN ('VERIFIED', 'REJECTED') AND #{status} = 'PENDING')
            """)
    int updateKyc(@Param("id") long id, @Param("status") String status, @Param("reference") String reference);
}
