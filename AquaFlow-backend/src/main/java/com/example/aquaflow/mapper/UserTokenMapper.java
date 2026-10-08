package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.UserToken;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserTokenMapper {

    @Insert("insert into user_token(user_id, user_type, refresh_token, expire_time, device_info, create_time) " +
            "values(#{userId}, #{userType}, #{refreshToken}, #{expireTime}, #{deviceInfo}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(UserToken userToken);

    /**
     * 按 refresh token 取那一行（旋转的入口）。
     *
     * <p>⚠️ <b>`order by id desc limit 1` 不是装饰</b>（2026-09-26 真机实测事故）：
     * {@code idx_refresh_token} 是<b>非唯一</b>索引，而历史上（加 {@code jti} 之前）
     * 同一秒内两次签发会得到<b>完全相同的 token 字符串</b> —— 真实库里因此存在重复行
     * （{@code user_token} id 113/114、129/130）。没有这个 {@code limit 1}，
     * 这条单行查询会抛 {@code TooManyResultsException}，表现为刷新登录态时
     * HTTP 200 + {@code code=500}「系统错误」，客户端只能重新登录。</p>
     *
     * <p>取最新一行：轮换走 {@link #deleteByRefreshToken} 清掉历史同值行。
     * [2026-10-05 F-78] FOR UPDATE 必须与轮换同一事务，不能在查完后释放锁再删/插；
     * 否则登出可在其间完成，晚到刷新再创建有效会话。</p>
     */
    @Select("select * from user_token where refresh_token = #{refreshToken} and expire_time > NOW() "
            + "order by id desc limit 1 for update")
    UserToken findByRefreshToken(@Param("refreshToken") String refreshToken);

    @Delete("delete from user_token where id = #{id}")
    void deleteById(@Param("id") Long id);

    @Delete("delete from user_token where user_id = #{userId} and user_type = #{userType}")
    void deleteByUser(@Param("userId") Long userId, @Param("userType") String userType);

    @Delete("delete from user_token where refresh_token = #{refreshToken}")
    void deleteByRefreshToken(@Param("refreshToken") String refreshToken);

    @Select("select * from user_token where user_id = #{userId} and user_type = #{userType} and expire_time > NOW()")
    List<UserToken> findActiveByUser(@Param("userId") Long userId, @Param("userType") String userType);
}
