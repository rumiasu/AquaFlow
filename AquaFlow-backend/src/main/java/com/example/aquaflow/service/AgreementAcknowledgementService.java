package com.example.aquaflow.service;

import com.example.aquaflow.dto.AgreementLoginDTO;
import com.example.aquaflow.dto.AgreementAcknowledgementDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AgreementEvidenceMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Terms acceptance and notice acknowledgement are separate events; neither grants blanket processing consent. */
@Service
public class AgreementAcknowledgementService {
    private final AgreementCatalogService catalog;
    private final AgreementEvidenceMapper mapper;
    private final Clock clock;
    public AgreementAcknowledgementService(AgreementCatalogService catalog, AgreementEvidenceMapper mapper, Clock clock) {
        this.catalog=catalog;this.mapper=mapper;this.clock=clock;
    }
    public void validateLogin(String audience, AgreementLoginDTO dto) {
        if (dto == null) return; // Compatibility does not fabricate agreement evidence.
        catalog.requireActive(audience,"user",dto.getTermsVersionId());
        catalog.requireActive(audience,"privacy",dto.getPrivacyVersionId());
    }
    @Transactional
    public boolean recordLogin(String actorType, Long actorId, AgreementLoginDTO dto) {
        String audience = scope(actorType,actorId);
        if (dto == null) return false;
        validateLogin(audience,dto); // Recheck after identity resolution; deployment/version races fail before any evidence write.
        record(actorType,actorId,catalog.requireActive(audience,"user",dto.getTermsVersionId()),"LOGIN_CLICK");
        record(actorType,actorId,catalog.requireActive(audience,"privacy",dto.getPrivacyVersionId()),"LOGIN_CLICK");
        return true;
    }
    @Transactional
    public void acknowledgeMy(AgreementAcknowledgementDTO dto) {
        String actor=AuthContext.getUserType();Long id=AuthContext.getUserId();
        record(actor,id,catalog.requireActive(scope(actor,id),dto.getType(),dto.getVersionId()),"EXPLICIT_ACTION");
    }
    public List<Map<String,Object>> mine() {
        scope(AuthContext.getUserType(),AuthContext.getUserId());
        return mapper.mine(AuthContext.getUserType(),AuthContext.getUserId());
    }
    private String scope(String actor,Long id) {
        if (id==null || id<=0 || (!"customer".equals(actor) && !"staff".equals(actor))) throw new BusinessException("当前身份尚未建立，不能记录或查询协议确认");
        return "customer".equals(actor)?"CUSTOMER":"STAFF";
    }
    private void record(String actor,Long id,AgreementCatalogService.Document document,String source) {
        var view=document.view();LocalDateTime now=LocalDateTime.now(clock);
        mapper.insertDocument(view.versionId(),view.audience(),view.type(),view.contentSha256(),document.bodyJson(),now);
        Map<String,Object> existing=mapper.documentForUpdate(view.versionId());
        // Raw snapshots are immutable even if application storage has a conflicting row.
        if (existing==null || !view.contentSha256().equals(existing.get("contentSha256"))
                || !document.bodyJson().equals(existing.get("bodyJson")) || !view.audience().equals(existing.get("audience"))
                || !view.type().equals(existing.get("documentType"))) throw new BusinessException("协议历史正文不一致，暂不能记录确认，请联系维护人员");
        String event="user".equals(view.type())?"TERMS_ACCEPTANCE":"PRIVACY_NOTICE_ACKNOWLEDGEMENT";
        mapper.insertAcknowledgement(actor,id,view.versionId(),event,source,now);
    }
}
