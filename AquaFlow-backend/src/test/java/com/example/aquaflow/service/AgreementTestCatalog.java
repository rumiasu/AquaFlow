package com.example.aquaflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Synthetic test-only text/metadata. Never packaged as a real operator or activated policy. */
public final class AgreementTestCatalog {
    public static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"),ZoneOffset.UTC);
    public record Fixture(byte[] index,Map<String,byte[]> resources) {
        public AgreementCatalogService catalog(boolean enabled) {return new AgreementCatalogService(index,resources::get,enabled,CLOCK);}
    }
    public static Fixture fixture(boolean approved,boolean archived,Instant effectiveAt) {
        try {
            ObjectMapper json=new ObjectMapper();List<Map<String,Object>> index=new ArrayList<>();Map<String,byte[]> resources=new LinkedHashMap<>();
            for(String audience:List.of("CUSTOMER","STAFF"))for(String type:List.of("user","privacy"))for(int revision=archived?0:1;revision<=1;revision++) {
                Map<String,Object> body=new LinkedHashMap<>();body.put("audience",audience);body.put("type",type);
                body.put("navTitle",type.equals("user")?"用户协议":"隐私政策");body.put("docTitle","仅用于合成测试的正文");
                body.put("updatedAt","合成测试版本"+revision);body.put("notice",approved?"":"仅为占位草稿，未启用");
                body.put("operatorName",approved?"仅测试合成主体":"待补");body.put("contact",approved?"仅测试合成受理渠道":"待补");
                body.put("retentionRule",approved?"仅用于测试验证，非真实保留规则":"待补");
                body.put("sections",List.of(Map.of("heading","合成测试章节","body",approved?"仅用于测试验证正文版本"+revision:"【待补】仅用于草稿门禁验证")));
                String resource="agreements/"+audience.toLowerCase(Locale.ROOT)+"-"+type+"-test-v"+revision+".json";
                byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
                // Include real LF line endings so canonical checkout tests exercise a meaningful difference.
                bytes=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
                resources.put(resource,bytes);
                Map<String,Object> entry=new LinkedHashMap<>();entry.put("resource",resource);entry.put("sha256",AgreementCatalogService.sha256(bytes));
                entry.put("current",revision==1);entry.put("status",approved?"APPROVED":"DRAFT");entry.put("reviewApproved",approved);
                if(approved){entry.put("approvedBy","仅测试审核人");entry.put("approvedAt","2026-10-07T00:00:00Z");entry.put("effectiveAt",effectiveAt.toString());}
                index.add(entry);
            }
            return new Fixture(json.writeValueAsBytes(Map.of("documents",index)),resources);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static AgreementCatalogService published() {return fixture(true,true,Instant.parse("2026-10-08T00:00:00Z")).catalog(true);}
    public static String archivedVersion(Fixture fixture,String audience,String type) {
        byte[] body=fixture.resources.get("agreements/"+audience.toLowerCase(Locale.ROOT)+"-"+type+"-test-v0.json");
        return audience.toLowerCase(Locale.ROOT)+"-"+type+"-"+AgreementCatalogService.sha256(body);
    }
}
