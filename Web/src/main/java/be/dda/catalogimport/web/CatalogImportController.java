package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.*;
import be.dda.catalogimport.service.CatalogImportService;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController @RequestMapping("/api/catalog-import")
public class CatalogImportController {
    private final CatalogImportService service;
    public CatalogImportController(CatalogImportService service) { this.service = service; }
    @PostMapping("/sources") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> source(@RequestBody SourceRequest request) { CatalogSource s = service.createSource(request.code(), request.name()); return Map.of("id",s.id,"code",s.code); }
    @PostMapping("/definitions") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> definition(@RequestBody DefinitionRequest request) { ImportDefinition d = service.createDefinition(request.sourceId(),request.version(),request.libraryCode(),request.supplierColumn(),request.referenceColumn(),request.groupColumn(),request.priceColumn()); return Map.of("id",d.id,"version",d.version,"libraryCode",d.libraryCode); }
    @PostMapping("/definitions/{definitionId}/batches") @ResponseStatus(HttpStatus.CREATED) public BatchResponse upload(@PathVariable Long definitionId, @RequestParam("file") MultipartFile file) throws IOException { return response(service.uploadAndScreen(definitionId, file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename(), file.getBytes())); }
    @PostMapping("/batches/{batchId}/plan") public BatchResponse plan(@PathVariable Long batchId) { return response(service.plan(batchId)); }
    @PostMapping("/batches/{batchId}/approve") public BatchResponse approve(@PathVariable Long batchId, @RequestBody ApprovalRequest request) { return response(service.approveAndPublish(batchId, request.approver())); }
    private BatchResponse response(ImportBatch b) { return new BatchResponse(b.id,b.status,b.recordCount,b.issueCount); }
    public record SourceRequest(@NotBlank String code, @NotBlank String name) { }
    public record DefinitionRequest(@NotNull Long sourceId, @Positive int version, @NotBlank String libraryCode, @NotBlank String supplierColumn, @NotBlank String referenceColumn, @NotBlank String groupColumn, @NotBlank String priceColumn) { }
    public record ApprovalRequest(@NotBlank String approver) { }
    public record BatchResponse(Long id, ImportStatus status, long recordCount, long issueCount) { }
}
