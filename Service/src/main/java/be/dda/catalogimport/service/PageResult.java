package be.dda.catalogimport.service;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Eén pagina van een lijst voor de Web-laag. Bewust geen Spring Data {@code Page}: dat zou het
 * persistentiemodel in het REST-contract lekken en de JSON-vorm van een framework laten afhangen.
 * {@code page} is 0-gebaseerd; {@code size} is de werkelijk gebruikte paginagrootte (na begrenzing).
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResult<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResult<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
