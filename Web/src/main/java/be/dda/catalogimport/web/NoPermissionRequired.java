package be.dda.catalogimport.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deze handlermethode is bewust van de rechtencheck uitgezonderd (Fase 5-PERM, ontwerp par. 1 en 3).
 * <p>
 * In 5-PERM is er precies één zo'n endpoint: {@code GET /api/catalog-import/me}. Een rechtenloze
 * gebruiker moet 200 met {@code permissions: []} kunnen krijgen, anders krijgt hij een 403 zonder enige
 * uitleg en kan de SPA niet eens tonen waarom ze leeg is.
 * <p>
 * Bewust een <b>expliciete</b> annotatie en geen witte lijst met paden: zodra 5B-3 de strikte modus
 * aanzet is een vergeten annotatie dicht (403), en een uitzondering staat dan zichtbaar bij het
 * endpoint zelf in plaats van in een lijst elders.
 *
 * @see RequiresPermission
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NoPermissionRequired {
}
