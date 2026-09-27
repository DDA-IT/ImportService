package be.dda.catalogimport.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Het recht dat deze handlermethode vereist (Fase 5-PERM, ontwerp par. 1 en 3). Wordt gelezen door
 * {@code PermissionInterceptor}; de hiërarchie maakt dat {@code APPROVE} ook voldoet aan een
 * {@code MANAGE}- of {@code READ}-eis, niet omgekeerd.
 * <p>
 * Uitsluitend op de methode, niet op de klasse: de archtest van 5B-3 eist dat élke
 * {@code @*Mapping}-methode in {@code be.dda.catalogimport.web} zichtbaar zelf zegt wat ze vraagt —
 * een recht dat je pas ziet door naar de klasse te kijken, is een recht dat bij het toevoegen van een
 * endpoint ongemerkt overgeërfd wordt.
 *
 * @see NoPermissionRequired
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** Het vereiste recht. */
    Permission value();
}
