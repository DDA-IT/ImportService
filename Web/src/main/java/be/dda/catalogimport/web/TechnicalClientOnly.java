package be.dda.catalogimport.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deze handlermethode is <b>uitsluitend</b> voor een technische client met een JWT (beslissingslog 2026-10-20 M2;
 * {@code docs/design/legacy-config-bundle-design.md} par. 3) — nooit voor een gebruiker met een BFF-sessie, ook niet
 * met {@code MANAGE} of {@code APPROVE}.
 * <p>
 * Derde, exclusieve keuze naast {@link RequiresPermission} en {@link NoPermissionRequired}: {@code PermissionCoverageTest}
 * eist dat elke {@code @*Mapping}-methode precies een van de drie draagt. {@code PermissionInterceptor} laat de methode
 * enkel door als de aanmelding een JWT-aanmelding is met de bevoegdheid {@link #AUTHORITY_PREFIX}{@code + value()};
 * die bevoegdheid wordt uitsluitend door de JWT-keten ({@code LegacyConfigPushSecurityConfiguration}) toegekend, na de
 * controle op {@code aud}, {@code azp} en de client role.
 * <p>
 * Bewust geen {@code ConfiguredPermissionSource}, geen Prodis-relay en geen rechtenhiërarchie: een technische client
 * heeft geen catalogImport-rechten, enkel deze ene bevoegdheid.
 *
 * @see RequiresPermission
 * @see NoPermissionRequired
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TechnicalClientOnly {

    /** Prefix van de bevoegdheid die de JWT-keten toekent. */
    String AUTHORITY_PREFIX = "TECHNICAL_CLIENT:";

    /** De bevoegdheid van de datasync om legacy-config-bundels te pushen. */
    String LEGACY_CONFIG_PUSH = "legacy-config-push";

    /** De vereiste bevoegdheid, bv. {@value #LEGACY_CONFIG_PUSH}. */
    String value();
}
