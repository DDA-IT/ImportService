package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 5B-1 (docs/design/fase5-perm-design.md par. 1): de rechtenhiërarchie en de codes.
 * Databasevrij — dit is pure afleiding en mag nooit van een context afhangen.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel (keuze mens 2026-09-26, V2):</b> {@code approve} impliceert {@code manage} en
 *       {@code read}; {@code manage} impliceert {@code read}. <b>Implementatie:</b>
 *       {@link Permission#effective}, op één plek, zodat de rechtencheck en {@code /me.permissions}
 *       (5B-4) nooit uit elkaar kunnen lopen.</li>
 *   <li><b>Regel:</b> de codes zijn het contract met de bron en de SPA. <b>Implementatie:</b> vaste
 *       {@code catalogImport.read/.manage/.approve}; hier vastgepind zodat een hernoeming opvalt.</li>
 * </ul>
 */
class PermissionHierarchyTest {

    // --- Normaal scenario: de hiërarchie ---------------------------------------------------------------

    @Test
    void approveImpliesManageAndRead() {
        assertThat(Permission.effective(Set.of(Permission.APPROVE)))
                .containsExactlyInAnyOrder(Permission.APPROVE, Permission.MANAGE, Permission.READ);
    }

    @Test
    void manageImpliesReadButNotApprove() {
        assertThat(Permission.effective(Set.of(Permission.MANAGE)))
                .containsExactlyInAnyOrder(Permission.MANAGE, Permission.READ);
    }

    @Test
    void readImpliesNothingElse() {
        assertThat(Permission.effective(Set.of(Permission.READ))).containsExactly(Permission.READ);
    }

    // --- Ontbrekende en dubbele invoer -----------------------------------------------------------------

    @Test
    void anEmptyOrNullGrantGivesNothing() {
        assertThat(Permission.effective(Set.of())).isEmpty();
        assertThat(Permission.effective(null)).isEmpty();
        assertThat(Permission.effective(Arrays.asList((Permission) null, null))).isEmpty();
    }

    @Test
    void duplicatesAndOverlappingRightsCollapseIntoOneSet() {
        assertThat(Permission.effective(List.of(Permission.APPROVE, Permission.APPROVE, Permission.READ)))
                .containsExactlyInAnyOrder(Permission.APPROVE, Permission.MANAGE, Permission.READ);
    }

    // --- Grensgeval: het resultaat is niet aanpasbaar ---------------------------------------------------

    /** De effectieve set is een momentopname, geen verzameling waar een aanroeper iets aan toevoegt. */
    @Test
    void theEffectiveSetIsUnmodifiable() {
        Set<Permission> effective = Permission.effective(EnumSet.of(Permission.READ));

        assertThatThrownBy(() -> effective.add(Permission.APPROVE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Codes en het herkennen van ruwe waarden --------------------------------------------------------

    @Test
    void theCodesAreTheContractWithTheSourceAndTheSpa() {
        assertThat(Permission.READ.code()).isEqualTo("catalogImport.read");
        assertThat(Permission.MANAGE.code()).isEqualTo("catalogImport.manage");
        assertThat(Permission.APPROVE.code()).isEqualTo("catalogImport.approve");
    }

    @Test
    void parseAcceptsTheFullCodeAndTheShortNameTrimmedAndCaseInsensitive() {
        assertThat(Permission.parse("catalogImport.approve")).contains(Permission.APPROVE);
        assertThat(Permission.parse("  CATALOGIMPORT.APPROVE ")).contains(Permission.APPROVE);
        assertThat(Permission.parse("approve")).contains(Permission.APPROVE);
        assertThat(Permission.parse(" Manage ")).contains(Permission.MANAGE);
    }

    /** Onbekend, blanco of {@code null} is nooit stilzwijgend "read": de aanroeper beslist wat er gebeurt. */
    @Test
    void parseRejectsUnknownBlankAndNullValues() {
        assertThat(Permission.parse("catalogImport.publish")).isEmpty();
        assertThat(Permission.parse("readonly")).isEmpty();
        assertThat(Permission.parse("   ")).isEmpty();
        assertThat(Permission.parse(null)).isEmpty();
    }
}
