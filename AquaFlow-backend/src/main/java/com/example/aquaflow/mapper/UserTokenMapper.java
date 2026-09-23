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

    @Select("select * from user_token where refresh_token = #{refreshToken} and expire_time > NOW()")
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
