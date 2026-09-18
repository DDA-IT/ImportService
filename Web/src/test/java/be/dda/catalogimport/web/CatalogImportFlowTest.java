package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.*;
import be.dda.catalogimport.service.CatalogImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("local")
class CatalogImportFlowTest {
    @Autowired CatalogImportService service;
    private ImportDefinition definition(String code, String library) { CatalogSource source = service.createSource(code, "Test source"); return service.createDefinition(source.id, 1, library, "supplier", "reference", "group", "price"); }
    @Test void publishesInitialOffersAndMakesAnIdenticalDeliveryIdempotent() {
        ImportDefinition definition = definition("SUP-A", "CONTROL-A"); byte[] csv = "supplier;reference;group;price\n02006;A-1;TOOLS;12,50\n".getBytes(StandardCharsets.UTF_8);
        ImportBatch first = service.uploadAndScreen(definition.id, "catalog.csv", csv); assertThat(first.status).isEqualTo(ImportStatus.PLANNED); assertThat(service.approveAndPublish(first.id, "reviewer@example.test").status).isEqualTo(ImportStatus.PUBLISHED);
        ImportBatch retry = service.uploadAndScreen(definition.id, "catalog-copy.csv", csv); assertThat(retry.id).isEqualTo(first.id); assertThat(retry.status).isEqualTo(ImportStatus.PUBLISHED);
    }
    @Test void blocksInvalidPricesWithoutTreatingThemAsZero() { ImportDefinition definition = definition("SUP-B", "CONTROL-B"); ImportBatch batch = service.uploadAndScreen(definition.id, "bad.csv", "supplier,reference,group,price\n02006,A-1,TOOLS,12x\n".getBytes(StandardCharsets.UTF_8)); assertThat(batch.status).isEqualTo(ImportStatus.BLOCKED); assertThat(batch.issueCount).isEqualTo(1); }
    @Test void blocksBulkCreationAgainstAnExistingScope() { ImportDefinition definition = definition("SUP-C", "CONTROL-C"); ImportBatch initial = service.uploadAndScreen(definition.id, "initial.csv", "supplier,reference,group,price\n1,A,G,1.00\n".getBytes(StandardCharsets.UTF_8)); service.approveAndPublish(initial.id, "reviewer@example.test"); ImportBatch batch = service.uploadAndScreen(definition.id, "many.csv", "supplier,reference,group,price\n1,B,G,1.00\n1,C,G,1.00\n".getBytes(StandardCharsets.UTF_8)); assertThat(batch.status).isEqualTo(ImportStatus.BLOCKED); }
}
