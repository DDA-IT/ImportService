package be.dda.catalogimport.web;

import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.TrialReadService;
import be.dda.catalogimport.service.TrialReadService.TrialReadResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Proefinlezing van een bestand tegen een revisie, zonder iets te bewaren (NT-9; bindend contract
 * {@code docs/design/proefinlezing-design.md}; beslissingslog 2026-09-30 NT-spoor V1 = A en "NT-9a").
 * <p>
 * {@code MANAGE}, niet achter {@code catalogimport.setup-api.enabled}. Werkt op een DRAFT-, ACTIVE- of
 * SUPERSEDED-revisie. Een voltooide proef is altijd 200, ook wanneer de levering zou blokkeren: dat staat in
 * {@code verdict}. Foutcodes, in deze volgorde: 403 {@code PERMISSION_DENIED} (interceptor) → 400
 * {@code FILE_REQUIRED} → 404 {@code REVISION_NOT_FOUND} → 404 {@code LINK_NOT_FOUND} → 400
 * {@code LINK_NOT_OF_REVISION_DEFINITION}. 413 (multipartlimiet, zonder code) en 415 komen van Spring; een
 * technische leesfout is 500.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportTrialReadController {

    private final TrialReadService trialReads;

    public CatalogImportTrialReadController(TrialReadService trialReads) {
        this.trialReads = trialReads;
    }

    /**
     * @param file   verplicht; bewust {@code required=false} zodat een ontbrekend bestand een stabiele
     *               {@code FILE_REQUIRED} krijgt. Een leeg bestand (0 bytes) is geen fout maar een proef met
     *               verdict {@code SOURCE_FILE_EMPTY}
     * @param linkId optioneel; enkel voor de vaste valuta van de koppeling (anders EUR, systeemstandaard)
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping(path = "/revisions/{revisionId}/trial-reads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TrialReadResult trialRead(@PathVariable("revisionId") long revisionId,
                              @RequestParam(value = "file", required = false) MultipartFile file,
                              @RequestParam(value = "linkId", required = false) Long linkId) {
        if (file == null) {
            throw new BadRequestException(TrialReadService.CODE_FILE_REQUIRED,
                    "A file is required for a trial read (multipart part 'file')");
        }
        try (InputStream content = file.getInputStream()) {
            return trialReads.trialRead(revisionId, linkId, file.getOriginalFilename(), content);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the uploaded file", failure);
        }
    }
}
