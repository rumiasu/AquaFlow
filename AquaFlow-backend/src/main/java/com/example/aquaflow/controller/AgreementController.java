package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.AgreementAcknowledgementDTO;
import com.example.aquaflow.service.AgreementAcknowledgementService;
import com.example.aquaflow.service.AgreementCatalogService;
import com.example.aquaflow.vo.AgreementDocumentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Current/version text is public. Evidence reads and writes are restricted to the authenticated person. */
@RestController
@RequestMapping("/api/agreements")
public class AgreementController {
    private final AgreementCatalogService catalog;
    private final AgreementAcknowledgementService evidence;
    public AgreementController(AgreementCatalogService catalog, AgreementAcknowledgementService evidence) {
        this.catalog=catalog;this.evidence=evidence;
    }
    @GetMapping("/current")
    public Result<AgreementCatalogService.Catalog> current(@RequestParam String audience) { return Result.success(catalog.catalog(audience)); }
    @GetMapping("/documents/{versionId}")
    public Result<AgreementDocumentVO> document(@PathVariable String versionId) { return Result.success(catalog.document(versionId)); }
    @PostMapping("/acknowledgements")
    public Result<Void> acknowledge(@RequestBody @Valid AgreementAcknowledgementDTO dto) { evidence.acknowledgeMy(dto);return Result.success(); }
    @GetMapping("/acknowledgements/my")
    public Result<List<Map<String,Object>>> mine(@RequestParam Map<String,String> inputs) {
        if(!inputs.isEmpty())return Result.error("仅查询当前账户协议记录，不接受身份或水站参数");
        return Result.success(evidence.mine());
    }
}
