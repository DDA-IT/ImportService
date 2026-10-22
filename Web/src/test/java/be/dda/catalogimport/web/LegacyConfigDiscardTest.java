package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.LegacyConfigReceiptService.PushResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * DISCARD van slice G1 ({@code docs/design/legacy-config-bundle-design.md} par. 2 en 4; beslissingslog 2026-10-22
 * "Bouwopdracht"): enkel een open ({@code RECEIVED}) ontvangst, reden verplicht, audit in {@code discarded_*}, de
 * verworpen ontvangst blijft de laatste van haar herkomst (idempotentie van S1).
 */
class LegacyConfigDiscardTest extends LegacyConfigS2TestBase {

    private ResultActions discard(long id, String body, Permission permission) throws Exception {
        var request = post(RECEIPTS + "/{id}/discard", id).with(as(USER, permission));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions discard(long id, String reason) throws Exception {
        return discard(id, MAPPER.createObjectNode().put("reason", reason).toString(), Permission.MANAGE);
    }

    @Test
    void aReceivedReceiptCanBeDiscardedWithAuditAndWritesNothingElse() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        var before = footprint(bundle);

        discard(id, "  Niet nodig  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(id))
                .andExpect(jsonPath("$.receiptStatus").value("DISCARDED"));

        Map<String, Object> row = receiptRow(id);
        assertThat(row.get("status")).isEqualTo("DISCARDED");
        assertThat(row.get("open_marker")).isNull();
        assertThat(row.get("discarded_at")).isNotNull();
        assertThat(row.get("discarded_by")).isEqualTo(USER);
        assertThat(row.get("discarded_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(row.get("discarded_reason")).isEqualTo("Niet nodig");
        // Alles behalve het aantal open ontvangsten (laatste element) blijft ongewijzigd; dat gaat van 1 naar 0.
        var after = footprint(bundle);
        assertThat(after.subList(0, after.size() - 1)).isEqualTo(before.subList(0, before.size() - 1));
        assertThat(before.get(before.size() - 1)).isEqualTo(1L);
        assertThat(after.get(after.size() - 1)).isEqualTo(0L);

        mockMvc.perform(get(RECEIPTS + "/{id}", id).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("DISCARDED"))
                .andExpect(jsonPath("$.discardedReason").value("Niet nodig"))
                .andExpect(jsonPath("$.discardedBy").value(USER));
    }

    @Test
    void aReasonIsRequiredAndBounded() throws Exception {
        long id = receive(applicable());

        discard(id, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_DISCARD_REASON_REQUIRED"));
        discard(id, null, Permission.MANAGE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_DISCARD_REASON_REQUIRED"));
        discard(id, "x".repeat(501)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_DISCARD_REASON_TOO_LONG"));

        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    void withoutManageIs403BeforeAnythingElseAndAnUnknownReceiptIs404() throws Exception {
        long id = receive(applicable());

        discard(id, "{\"reason\":\"x\"}", Permission.READ).andExpect(status().isForbidden());
        discard(id, "{}", Permission.READ).andExpect(status().isForbidden());
        discard(Long.MAX_VALUE, "{\"reason\":\"x\"}", Permission.READ).andExpect(status().isForbidden());
        discard(Long.MAX_VALUE, "Reden").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_FOUND"));

        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    void onlyAReceivedReceiptCanBeDiscarded() throws Exception {
        // Al verworpen: een tweede keer is 409, de eerste audit blijft.
        long discarded = receive(applicable());
        discard(discarded, "Eerste reden").andExpect(status().isOk());
        discard(discarded, "Tweede reden").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(receiptRow(discarded).get("discarded_reason")).isEqualTo("Eerste reden");

        // Toegepast: niet meer te verwerpen.
        long applied = receive(applicable());
        previewAndApply(applied, "libraryCode", "LIB-G1").andExpect(status().isOk());
        discard(applied, "Te laat").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(receiptRow(applied).get("status")).isEqualTo("APPLIED");

        // Vervangen: ook niet.
        ObjectNode a = applicable();
        long first = receive(a);
        ObjectNode changed = a.deepCopy();
        ((ObjectNode) changed.get("definition")).put("description", "Andere omschrijving G1");
        long second = receive(changed);
        assertThat(receiptRow(first).get("status")).isEqualTo("SUPERSEDED");
        discard(first, "Vervangen").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(receiptRow(second).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    void afterDiscardingTheSameHashIsUnchangedAndAnotherHashIsANewReceivedReceipt() throws Exception {
        ObjectNode a = applicable();
        long first = receive(a);
        discard(first, "Niet nodig").andExpect(status().isOk());

        PushResult again = receipts.receive(MAPPER.writeValueAsBytes(a), new be.dda.catalogimport.service.ActorIdentity(
                "svc:legacy-config-test", "svc-sub"));
        assertThat(again.outcome()).isEqualTo("UNCHANGED");
        assertThat(again.receiptId()).isEqualTo(first);
        assertThat(again.status()).isEqualTo("DISCARDED");

        ObjectNode changed = a.deepCopy();
        ((ObjectNode) changed.get("definition")).put("description", "Andere omschrijving G1b");
        long next = receive(changed);
        assertThat(next).isNotEqualTo(first);
        assertThat(receiptRow(next).get("status")).isEqualTo("RECEIVED");
        assertThat(receiptRow(first).get("status")).isEqualTo("DISCARDED");
        assertThat(receiptRow(first).get("superseded_by_receipt_id")).isNull();
    }

    @Test
    void applyOnADiscardedReceiptIsAConflict() throws Exception {
        long id = receive(applicable());
        String fingerprint = fingerprint(id, "libraryCode", "LIB-G1");
        discard(id, "Niet nodig").andExpect(status().isOk());

        apply(id, fingerprint, REASON, "libraryCode", "LIB-G1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(receiptRow(id).get("status")).isEqualTo("DISCARDED");
    }

    @Test
    void anAppliedReceiptExposesItsApplyReportForTheLinksToTheDraft() throws Exception {
        long id = receive(applicable());
        previewAndApply(id, "libraryCode", "LIB-G1B").andExpect(status().isOk());

        mockMvc.perform(get(RECEIPTS + "/{id}", id).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applyReport.planned.definition.id").isNumber())
                .andExpect(jsonPath("$.applyReport.planned.definition.revisionId").isNumber())
                .andExpect(jsonPath("$.applyReport.planned.template.id").isNumber())
                .andExpect(jsonPath("$.discardedAt").value(org.hamcrest.Matchers.nullValue()));
    }
}
