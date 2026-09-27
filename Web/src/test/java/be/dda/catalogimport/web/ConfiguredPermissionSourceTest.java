package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.ActorIdentity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Bouwstap 5B-1 (docs/design/fase5-perm-design.md par. 2): de lokale rechtenbron uit
 * {@code application*.yml}. Databasevrij en zonder Spring-context.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> fail-closed — lege configuratie betekent dat niemand iets heeft.
 *       <b>Implementatie:</b> geen wildcard, geen default; een onbekende gebruiker krijgt een lege set.</li>
 *   <li><b>Regel:</b> matching is getrimd en hoofdletterongevoelig. <b>Implementatie:</b> de sleutel is
 *       de getrimde naam in kleine letters, aan beide kanten.</li>
 *   <li><b>Regel:</b> {@code system} mag lezen maar nooit beheren of goedkeuren. <b>Implementatie:</b>
 *       de applicatie <b>start niet</b> — een draaiende applicatie die de toekenning stil negeert, zou
 *       de yml en de werkelijkheid uit elkaar laten lopen.</li>
 *   <li><b>Data:</b> de bron levert de <b>ruwe</b> codes; de hiërarchie hoort niet hier maar in
 *       {@code CurrentActor}.</li>
 * </ul>
 */
class ConfiguredPermissionSourceTest {

    // --- Normaal scenario -------------------------------------------------------------------------------

    @Test
    void aGrantedUserGetsExactlyTheConfiguredRawRights() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "read", "manage"));

        assertThat(source.permissionsOf(actor("jan.peeters")))
                .containsExactlyInAnyOrder(Permission.READ, Permission.MANAGE);
    }

    /** De hiërarchie hoort in {@code CurrentActor}: deze bron breidt {@code approve} niet uit. */
    @Test
    void theSourceReturnsRawCodesAndNeverExpandsTheHierarchy() {
        ConfiguredPermissionSource source = source(grant("an.janssens", "approve"));

        assertThat(source.permissionsOf(actor("an.janssens"))).containsExactly(Permission.APPROVE);
    }

    @Test
    void theFullCodesAreAlsoAcceptedInTheConfiguration() {
        ConfiguredPermissionSource source = source(grant("piet", "catalogImport.read", "CATALOGIMPORT.APPROVE"));

        assertThat(source.permissionsOf(actor("piet")))
                .containsExactlyInAnyOrder(Permission.READ, Permission.APPROVE);
    }

    // --- Matching: getrimd en hoofdletterongevoelig -------------------------------------------------------

    @Test
    void matchingIsTrimmedAndCaseInsensitiveOnBothSides() {
        ConfiguredPermissionSource source = source(grant("  Jan.Peeters  ", "read"));

        assertThat(source.permissionsOf(actor("jan.peeters"))).containsExactly(Permission.READ);
        assertThat(source.permissionsOf(actor("JAN.PEETERS"))).containsExactly(Permission.READ);
        assertThat(source.permissionsOf(actor(" jan.peeters "))).containsExactly(Permission.READ);
    }

    // --- Ontbrekende data: fail-closed ---------------------------------------------------------------------

    @Test
    void anEmptyConfigurationMeansNobodyHasAnything() {
        ConfiguredPermissionSource source = source();

        assertThat(source.grantedUserCount()).isZero();
        assertThat(source.permissionsOf(actor("jan.peeters"))).isEmpty();
        assertThat(source.permissionsOf(actor("system"))).isEmpty();
    }

    @Test
    void anUnknownUserGetsNothingAndNeverADefault() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "approve"));

        assertThat(source.permissionsOf(actor("iemand.anders"))).isEmpty();
        // Geen wildcard: '*' is gewoon een gebruikersnaam die niemand heeft.
        assertThat(source.permissionsOf(actor("*"))).isEmpty();
    }

    @Test
    void aGrantWithoutAnyRightGivesAnEmptySetAndIsNotAnError() {
        ConfiguredPermissionSource source = source(grant("jan.peeters"));

        assertThat(source.permissionsOf(actor("jan.peeters"))).isEmpty();
    }

    @Test
    void aNullActorOrUsernameGivesNothingInsteadOfFailing() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "read"));

        assertThat(source.permissionsOf(null)).isEmpty();
        assertThat(source.permissionsOf(new ActorIdentity(null, "sub-1"))).isEmpty();
    }

    // --- Dubbele invoer -------------------------------------------------------------------------------------

    /** Twee regels voor dezelfde gebruiker: de unie, zoals een lezer van de yml verwacht. */
    @Test
    void twoGrantsForTheSameUserAreMerged() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "read"), grant("JAN.PEETERS", "approve"));

        assertThat(source.grantedUserCount()).isEqualTo(1);
        assertThat(source.permissionsOf(actor("jan.peeters")))
                .containsExactlyInAnyOrder(Permission.READ, Permission.APPROVE);
    }

    @Test
    void aRepeatedRightIsHarmless() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "read", "READ", "catalogImport.read"));

        assertThat(source.permissionsOf(actor("jan.peeters"))).containsExactly(Permission.READ);
    }

    // --- Ongeldige invoer: opstarten faalt ---------------------------------------------------------------------

    @Test
    void systemWithManageOrApproveRefusesToStart() {
        assertThatThrownBy(() -> source(grant("system", "manage")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalogimport.permissions.grants[0]")
                .hasMessageContaining("system");
        assertThatThrownBy(() -> source(grant("system", "approve")))
                .isInstanceOf(IllegalStateException.class);
        // Ook in een andere schrijfwijze of met spaties: de controle loopt over dezelfde genormaliseerde sleutel.
        assertThatThrownBy(() -> source(grant("  SySTeM  ", "read", "approve")))
                .isInstanceOf(IllegalStateException.class);
        // En de index wijst de juiste regel aan.
        assertThatThrownBy(() -> source(grant("jan.peeters", "read"), grant("system", "manage")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grants[1]");
    }

    /** {@code system} mag wél lezen — het verschil zit in beheren en goedkeuren. */
    @Test
    void systemWithOnlyReadIsAllowed() {
        ConfiguredPermissionSource source = source(grant("system", "read"));

        assertThat(source.permissionsOf(actor("system"))).containsExactly(Permission.READ);
    }

    @Test
    void anUnknownRightRefusesToStartInsteadOfDisappearing() {
        assertThatThrownBy(() -> source(grant("jan.peeters", "read", "publish")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publish")
                .hasMessageContaining("catalogimport.permissions.grants[0].rights");
    }

    @Test
    void aMissingOrBlankUsernameRefusesToStart() {
        assertThatThrownBy(() -> source(grant(null, "read")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
        assertThatThrownBy(() -> source(grant("   ", "read")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }

    /** Een leeg element ("read, , manage") is slordig maar onschadelijk; het mag niet als fout gelden. */
    @Test
    void blankRightElementsAreIgnored() {
        ConfiguredPermissionSource source = source(grant("jan.peeters", "read", "  ", ""));

        assertThat(source.permissionsOf(actor("jan.peeters"))).containsExactly(Permission.READ);
    }

    // --- De configuratievorm zelf ---------------------------------------------------------------------------------

    /**
     * Het punt van het "Important technical constraint discovered"-blok in het ontwerp: een naam met een
     * punt ({@code jan.peeters}) mag nooit stilzwijgend verdwijnen. Daarom een <b>lijst</b> met een
     * {@code username}-veld, en geen map. Dit bindt de yml-vorm uit het ontwerp letterlijk.
     */
    @Test
    void theYamlListFormBindsIncludingADottedUsernameAndACommaSeparatedRightsValue() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("catalogimport.permissions.grants[0].username", "jan.peeters");
        yaml.put("catalogimport.permissions.grants[0].rights", "read, manage, approve");

        PermissionProperties bound = bind(yaml);

        assertThat(bound.getGrants()).hasSize(1);
        assertThat(bound.getGrants().get(0).getUsername()).isEqualTo("jan.peeters");
        assertThat(new ConfiguredPermissionSource(bound).permissionsOf(actor("jan.peeters")))
                .containsExactlyInAnyOrder(Permission.READ, Permission.MANAGE, Permission.APPROVE);
    }

    /** De andere schrijfwijze van dezelfde lijst moet ook werken; anders is de vorm een valkuil. */
    @Test
    void theYamlSequenceFormForRightsAlsoBinds() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("catalogimport.permissions.grants[0].username", "an.janssens");
        yaml.put("catalogimport.permissions.grants[0].rights[0]", "read");
        yaml.put("catalogimport.permissions.grants[0].rights[1]", "approve");

        assertThat(new ConfiguredPermissionSource(bind(yaml)).permissionsOf(actor("an.janssens")))
                .containsExactlyInAnyOrder(Permission.READ, Permission.APPROVE);
    }

    @Test
    void anAbsentGrantsKeyBindsToAnEmptyList() {
        assertThat(bind(new LinkedHashMap<>()).getGrants()).isEmpty();
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private static PermissionProperties bind(Map<String, Object> yaml) {
        return new Binder(new MapConfigurationPropertySource(yaml))
                .bind("catalogimport.permissions", PermissionProperties.class)
                .orElseGet(PermissionProperties::new);
    }

    private static ConfiguredPermissionSource source(PermissionProperties.Grant... grants) {
        PermissionProperties properties = new PermissionProperties();
        properties.setGrants(new ArrayList<>(Arrays.asList(grants)));
        return new ConfiguredPermissionSource(properties);
    }

    private static PermissionProperties.Grant grant(String username, String... rights) {
        PermissionProperties.Grant grant = new PermissionProperties.Grant();
        grant.setUsername(username);
        grant.setRights(List.of(rights));
        return grant;
    }

    private static ActorIdentity actor(String username) {
        return new ActorIdentity(username, "test-sub-" + username);
    }
}
