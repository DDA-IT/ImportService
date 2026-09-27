package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import java.util.Set;

/**
 * De poort naar de rechtenbron (Fase 5-PERM, bindend ontwerp {@code docs/design/fase5-perm-design.md}
 * par. 2).
 * <p>
 * <b>Ruwe codes, geen afleiding.</b> Een implementatie geeft terug wat de bron zegt; de hiërarchie
 * ({@code approve} impliceert {@code manage} en {@code read}) wordt op precies één plek toegepast:
 * {@link Permission#effective(java.util.Collection)}, aangeroepen door {@link CurrentActor}.
 * <p>
 * <b>Eén methode, geen tokenparameter.</b> De latere Prodis-adapter (5B-7) haalt het access token zelf
 * uit de sessie ({@code OAuth2AuthorizedClientRepository}); daardoor blijft deze poort bruikbaar voor
 * een lokale, configuratie-gedreven bron zonder token.
 * <p>
 * <b>Fail-closed.</b> Een lege set betekent "deze gebruiker heeft niets" en is een geldig antwoord. Een
 * bron die het antwoord <i>niet kent</i> geeft nooit een lege set maar gooit
 * {@link PermissionSourceUnavailableException} (503) — het verschil tussen "geen rechten" en "we weten
 * het niet" mag nooit verloren gaan.
 */
public interface PermissionSource {

    /**
     * De ruwe, toegekende rechten van deze gebruiker.
     *
     * @param actor de geverifieerde identiteit; nooit {@code null}
     * @return de toegekende rechten, mogelijk leeg; nooit {@code null}
     * @throws PermissionSourceUnavailableException als de bron niet bevraagd kan worden
     */
    Set<Permission> permissionsOf(ActorIdentity actor);
}
