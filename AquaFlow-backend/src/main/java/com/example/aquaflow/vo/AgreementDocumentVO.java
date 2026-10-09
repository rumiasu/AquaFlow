package com.example.aquaflow.vo;

import java.util.List;

/** Public document projection. Reading it never records acceptance or processing consent. */
public record AgreementDocumentVO(String audience, String type, String versionId, String contentSha256,
        String status, boolean active, String navTitle, String docTitle, String updatedAt,
        String notice, List<Section> sections) {
    public record Section(String heading, String body) {}
}
