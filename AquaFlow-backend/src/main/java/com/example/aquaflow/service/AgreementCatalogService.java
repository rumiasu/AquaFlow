package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.vo.AgreementDocumentVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Reviewed packaged text, pinned by SHA-256. There is deliberately no HTTP publishing/editing command. */
@Service
public class AgreementCatalogService {
    public record Catalog(boolean enabled, String notice, List<AgreementDocumentVO> documents) {}
    record Document(AgreementDocumentVO view, String bodyJson, boolean current, Instant effectiveAt) {}
    private final Map<String, Document> documents;
    private final Map<String, Document> current;
    private final boolean formalEnabled;
    private final Clock clock;

    @Autowired
    public AgreementCatalogService(@Value("${app.agreements.formal-enabled:false}") boolean enabled, Clock clock) {
        this(read("agreements/index.json"), AgreementCatalogService::read, enabled, clock);
    }

    AgreementCatalogService(byte[] index, Function<String, byte[]> loader, boolean enabled, Clock clock) {
        this.formalEnabled = enabled;
        this.clock = clock;
        Map<String, Document> all = new LinkedHashMap<>(), selected = new LinkedHashMap<>();
        try {
            ObjectMapper json = new ObjectMapper();
            JsonNode entries = json.readTree(index).path("documents");
            if (!entries.isArray() || entries.isEmpty()) throw new IllegalArgumentException("Missing agreement index");
            for (JsonNode entry : entries) {
                String resource = required(entry, "resource");
                if (!resource.matches("agreements/[a-z0-9-]+\\.json")) throw new IllegalArgumentException("Invalid document path");
                byte[] bytes = new String(loader.apply(resource), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
                        .getBytes(StandardCharsets.UTF_8);
                String hash = sha256(bytes);
                if (!hash.equals(required(entry, "sha256"))) throw new IllegalArgumentException("Agreement text hash mismatch: " + resource);
                JsonNode body = json.readTree(bytes);
                String audience = audience(required(body, "audience")), type = type(required(body, "type"));
                String status = required(entry, "status");
                if (!Set.of("DRAFT", "APPROVED").contains(status)) throw new IllegalArgumentException("Invalid agreement status");
                boolean isCurrent = entry.path("current").asBoolean(false);
                Instant effectiveAt = null;
                if ("APPROVED".equals(status)) {
                    // TODO(待拍板)：真实主体/联系人、正式审核和变更告知见 docs/design/16-范围决策与实施路线图.md §12 C-09。
                    // A switch alone cannot promote a placeholder into a formal document.
                    if (!entry.path("reviewApproved").asBoolean(false)) throw new IllegalArgumentException("Unapproved agreement");
                    reviewed(required(entry, "approvedBy"));
                    Instant.parse(required(entry, "approvedAt"));
                    effectiveAt = Instant.parse(required(entry, "effectiveAt"));
                    reviewed(required(body, "operatorName")); reviewed(required(body, "contact"));
                    if ("privacy".equals(type)) reviewed(required(body, "retentionRule"));
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    // 占位标记仍须拒绝；真实正文/资料规则正本见 docs/design/16-范围决策与实施路线图.md §12 C-09/C-10。
                    if (text.contains("【待补】") || text.contains("TODO(待拍板)") || text.contains("草稿") || text.contains("未核验") || text.contains("未审核"))
                        throw new IllegalArgumentException("Formal document contains placeholders");
                }
                List<AgreementDocumentVO.Section> sections = new ArrayList<>();
                if (!body.path("sections").isArray() || body.path("sections").isEmpty()) throw new IllegalArgumentException("Missing sections");
                for (JsonNode section : body.path("sections"))
                    sections.add(new AgreementDocumentVO.Section(required(section, "heading"), required(section, "body")));
                String version = audience.toLowerCase(Locale.ROOT) + "-" + type + "-" + hash;
                AgreementDocumentVO view = new AgreementDocumentVO(audience, type, version, hash, status, false,
                        required(body, "navTitle"), required(body, "docTitle"), required(body, "updatedAt"),
                        body.path("notice").asText(""), List.copyOf(sections));
                Document document = new Document(view, new String(bytes, StandardCharsets.UTF_8), isCurrent, effectiveAt);
                if (all.putIfAbsent(version, document) != null) throw new IllegalArgumentException("Duplicate agreement version");
                if (isCurrent && selected.putIfAbsent(audience + ":" + type, document) != null)
                    throw new IllegalArgumentException("Multiple current agreement versions");
            }
            for (String audience : List.of("CUSTOMER", "STAFF")) for (String type : List.of("user", "privacy"))
                if (!selected.containsKey(audience + ":" + type)) throw new IllegalArgumentException("Missing current agreement");
            if (enabled && selected.values().stream().anyMatch(d -> !"APPROVED".equals(d.view.status())))
                throw new IllegalArgumentException("Formal agreements cannot be enabled with drafts");
            this.documents = Collections.unmodifiableMap(all);
            this.current = Collections.unmodifiableMap(selected);
        } catch (Exception e) {
            throw new IllegalStateException("协议目录校验失败；未审核或摘要不一致的正文不能启用", e);
        }
    }

    public Catalog catalog(String audience) {
        String scope = audience(audience);
        List<AgreementDocumentVO> views = List.of(view(current.get(scope + ":user")), view(current.get(scope + ":privacy")));
        boolean enabled = views.stream().allMatch(AgreementDocumentVO::active);
        return new Catalog(enabled, enabled ? "点击登录表示接受用户协议并确认已获隐私告知；不代表所有信息处理均已获同意。"
                : "协议正文为未启用草稿或尚未生效，当前登录不记录正式协议接受或隐私告知确认。", views);
    }

    public AgreementDocumentVO document(String versionId) {
        Document document = documents.get(versionId);
        if (document == null) throw new BusinessException("协议版本不存在，请重新打开协议页面");
        return view(document);
    }

    Document requireActive(String audience, String type, String versionId) {
        Document document = documents.get(versionId);
        String scope = audience(audience), kind = type(type);
        if (document == null || !scope.equals(document.view.audience()) || !kind.equals(document.view.type()))
            throw new BusinessException("协议版本与当前身份不符，请重新阅读");
        if (!catalog(scope).enabled()) throw new BusinessException("正式协议尚未启用，不能记录正式接受或隐私告知确认");
        if (current.get(scope + ":" + kind) != document)
            throw new BusinessException("协议版本已变化，请重新打开并阅读当前正文");
        return document;
    }

    private AgreementDocumentVO view(Document document) {
        AgreementDocumentVO v = document.view;
        boolean active = formalEnabled && document.current && "APPROVED".equals(v.status())
                && !Instant.now(clock).isBefore(document.effectiveAt);
        return new AgreementDocumentVO(v.audience(), v.type(), v.versionId(), v.contentSha256(), v.status(), active,
                v.navTitle(), v.docTitle(), v.updatedAt(), v.notice(), v.sections());
    }
    static String audience(String value) {
        if (value == null || !Set.of("CUSTOMER", "STAFF").contains(value)) throw new BusinessException("请选择客户或员工协议");
        return value;
    }
    static String type(String value) {
        if (value == null || !Set.of("user", "privacy").contains(value)) throw new BusinessException("协议类型无效");
        return value;
    }
    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value.asText();
    }
    private static void reviewed(String value) {
        if (value.contains("待补") || value.contains("待拍板") || value.contains("your-") || value.contains("example.com"))
            throw new IllegalArgumentException("Placeholder publication metadata");
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] read(String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) { return input.readAllBytes(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Missing agreement resource " + resource, e); }
    }
}
