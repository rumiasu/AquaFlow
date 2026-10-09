package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

/** Append-only evidence. Duplicate retries preserve the original body and timestamp. */
@Mapper
public interface AgreementEvidenceMapper {
    @Insert("insert into agreement_document(version_id,audience,document_type,content_sha256,body_json,create_time) "
            + "values(#{version},#{audience},#{type},#{sha},#{body},#{now}) on duplicate key update version_id=version_id")
    int insertDocument(@Param("version") String version,@Param("audience") String audience,@Param("type") String type,
                       @Param("sha") String sha,@Param("body") String body,@Param("now") LocalDateTime now);
    @Select("select audience,document_type as documentType,content_sha256 as contentSha256,body_json as bodyJson "
            + "from agreement_document where version_id=#{version} for update")
    Map<String,Object> documentForUpdate(@Param("version") String version);
    @Insert("insert into agreement_acknowledgement(actor_type,actor_id,version_id,event_type,action_source,create_time) "
            + "values(#{actorType},#{actorId},#{version},#{event},#{source},#{now}) on duplicate key update id=id")
    int insertAcknowledgement(@Param("actorType") String actorType,@Param("actorId") Long actorId,@Param("version") String version,
                              @Param("event") String event,@Param("source") String source,@Param("now") LocalDateTime now);
    @Select("select version_id as versionId,event_type as eventType,action_source as actionSource,create_time as recordedAt "
            + "from agreement_acknowledgement where actor_type=#{actorType} and actor_id=#{actorId} order by id desc limit 100")
    List<Map<String,Object>> mine(@Param("actorType") String actorType,@Param("actorId") Long actorId);
}
