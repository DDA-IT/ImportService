package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De DEFINITION-scope bookmarksnapshot van een afgeleide revisie (sjabloon-materialisatie-design.md
 * §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkValueRepository
        extends JpaRepository<ImportDefinitionBookmarkValue, Long> {

    /** De waarde op (revisie, bookmarknaam); afwezigheid betekent "niet ingevuld" (R-BMK-03). */
    Optional<ImportDefinitionBookmarkValue> findByDefinitionRevisionIdAndBookmarkName(Long definitionRevisionId,
                                                                                       String bookmarkName);

    /**
     * Alle waarden van één revisie (revision-successor-design.md &sect;2, bouwstap S1-X-2): een
     * opvolgrevisie kopieert ze letterlijk mee, inclusief {@code source_template_revision_id}, zodat ook
     * na een opvolgversie afleesbaar blijft dát een vaste waarde uit een sjabloon kwam en uit welke
     * sjabloonversie.
     * <p>
     * Zonder expliciete sortering: {@code uk_import_definition_bookmark_value} maakt elke rij uniek op
     * (revisie, bookmarknaam) en de kopie behandelt elke rij los van de andere, dus de leesvolgorde
     * heeft hier geen betekenis. De bestaande variant hierboven leest één naam; deze de hele revisie.
     */
    List<ImportDefinitionBookmarkValue> findByDefinitionRevisionId(Long definitionRevisionId);
}
