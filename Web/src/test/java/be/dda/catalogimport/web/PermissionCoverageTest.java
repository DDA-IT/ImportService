package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architectuurtest van bouwstap 5B-3 (bindend ontwerp {@code docs/design/fase5-perm-design.md} par. 1 en 3).
 *
 * <ul>
 *   <li><b>Regel:</b> een ontbrekende annotatie is dicht; elke handler draagt dus een expliciete keuze.
 *       <b>Implementatie:</b> reflectie over alle controllers in {@code be.dda.catalogimport.web}: elke
 *       {@code @*Mapping}-methode draagt precies één van {@code @RequiresPermission} of
 *       {@code @NoPermissionRequired}.</li>
 *   <li><b>Regel:</b> de mapping uit ontwerp par. 1 klopt. <b>Implementatie:</b> de volledige tabel als
 *       verwachte map (pad-variabelenamen genormaliseerd); een nieuw of gewijzigd endpoint laat deze test
 *       falen tot het ontwerp en de test samen bijgewerkt zijn.</li>
 * </ul>
 * Databasevrij en zonder Spring-context.
 */
class PermissionCoverageTest {

    private static final String NONE = "NONE";

    @Test
    void everyMappingMethodCarriesExactlyOneOfTheTwoAnnotations() throws Exception {
        List<String> violations = new ArrayList<>();
        int handlers = 0;
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                boolean requires = method.isAnnotationPresent(RequiresPermission.class);
                boolean exempt = method.isAnnotationPresent(NoPermissionRequired.class);
                if (requires == exempt) {
                    violations.add(controller.getSimpleName() + "#" + method.getName()
                            + (requires ? " has both annotations" : " has no permission annotation"));
                }
            }
        }
        assertThat(handlers).as("controllers were found").isPositive();
        assertThat(violations).isEmpty();
    }

    @Test
    void theMappingMatchesDesignSection1() throws Exception {
        Map<String, String> actual = new TreeMap<>();
        for (Class<?> controller : controllers()) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String basePath = (base == null || base.path().length == 0) ? "" : base.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = basePath + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                String key = mapping.method()[0].name() + " " + path.replaceAll("\\{[^}]+}", "{}");
                RequiresPermission required = method.getAnnotation(RequiresPermission.class);
                actual.put(key, required == null ? NONE : required.value().name());
            }
        }
        assertThat(actual).isEqualTo(new TreeMap<>(expected()));
    }

    private static Map<String, String> expected() {
        String b = "/api/catalog-import/batches";
        String u = "/api/catalog-import/bundles";
        String s = "/api/catalog-import/setup";
        String t = "/api/catalog-import/templates";
        Map<String, String> m = new TreeMap<>();
        // Batches
        for (String p : new String[] {"", "/summary", "/{}", "/{}/mutations", "/{}/issues", "/{}/issue-groups"}) {
            m.put("GET " + b + p, "READ");
        }
        m.put("POST " + b + "/{}/accept-baseline", "APPROVE");
        m.put("POST " + b + "/{}/continue", "MANAGE");
        // Bundels
        for (String p : new String[] {"", "/candidates", "/{}", "/{}/batches", "/{}/mutations", "/{}/decisions",
                "/{}/freeze-check", "/{}/psimport-preview"}) {
            m.put("GET " + u + p, "READ");
        }
        m.put("POST " + u, "MANAGE");
        m.put("POST " + u + "/{}/batches", "MANAGE");
        m.put("POST " + u + "/{}/batches/{}/remove", "MANAGE");
        for (String p : new String[] {"/{}/mutations/{}/approve", "/{}/mutations/{}/reject", "/{}/decisions",
                "/{}/freeze", "/{}/cancel"}) {
            m.put("POST " + u + p, "APPROVE");
        }
        // Publicatieruns (5P-8, fase5-pub-design.md par. 4)
        m.put("POST /api/catalog-import/bundles/{}/publication-runs", "APPROVE");
        m.put("GET /api/catalog-import/bundles/{}/publication-runs", "READ");
        m.put("GET /api/catalog-import/publication-runs/{}", "READ");
        m.put("GET /api/catalog-import/publication-runs/{}/artifact", "READ");
        // Herstel van een vastgelopen PREPARING-run (docs/decisions.md 2026-09-27, optie A): zelfde
        // rechtenconventie als het aanvragen zelf.
        m.put("POST /api/catalog-import/publication-runs/{}/abort", "APPROVE");
        // Leveringen, koppelingen, taken
        m.put("POST /api/catalog-import/tasks/{}/deliveries", "MANAGE");
        // Tweede ontvangstweg (beslissingslog 2026-09-27, D8): het lijsten van de servermap vraagt MANAGE en
        // niet READ - het onthult de inhoud van een serverdirectory, en wie mag kijken moet ook mogen inlezen.
        m.put("GET /api/catalog-import/local-source/files", "MANAGE");
        m.put("POST /api/catalog-import/tasks/{}/deliveries/local-source", "MANAGE");
        m.put("GET /api/catalog-import/deliveries/{}", "READ");
        m.put("GET /api/catalog-import/import-links", "READ");
        m.put("GET /api/catalog-import/tasks", "READ");
        // Setup
        m.put("GET " + s + "/overview", "READ");
        // Inrichtingsendpoints voor scherm 1a (S1-B1, beslissingslog 27/09 keuze A1): buiten de
        // setup-API-vlag, dus geen "/setup"-prefix.
        m.put("GET /api/catalog-import/source-organisations", "READ");
        m.put("GET /api/catalog-import/definitions", "READ");
        m.put("GET /api/catalog-import/definitions/{}/revisions", "READ");
        for (String p : new String[] {"/source-organisations", "/definitions", "/definitions/{}/revisions",
                "/revisions/{}/activate", "/revisions/{}/mappings", "/revisions/{}/filters",
                "/revisions/{}/field-criticality", "/links", "/tasks"}) {
            m.put("POST " + s + p, "MANAGE");
        }
        // Templates en links
        m.put("GET " + t, "READ");
        m.put("GET " + t + "/{}/revisions/{}/bookmarks", "READ");
        m.put("GET " + t + "/{}/materialisations", "READ");
        m.put("POST " + t + "/{}/revisions/{}/bookmarks", "MANAGE");
        m.put("POST " + t + "/{}/revisions/{}/bookmarks/{}/usages", "MANAGE");
        m.put("POST " + t + "/{}/materialisations", "MANAGE");
        m.put("GET /api/catalog-import/links/{}/bookmark-values", "READ");
        m.put("PUT /api/catalog-import/links/{}/bookmark-values/{}", "MANAGE");
        // Behandelgeval (S2-B2, docs/design/issue-case-design.md par. 4): enkel MANAGE (design A5).
        m.put("POST /api/catalog-import/issue-cases/{}/status", "MANAGE");
        // /me
        m.put("GET /api/catalog-import/me", NONE);
        return m;
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        AnnotationTypeFilter restController = new AnnotationTypeFilter(RestController.class);
        AnnotationTypeFilter controller = new AnnotationTypeFilter(org.springframework.stereotype.Controller.class);
        // Bewust ZONDER @Conditional-evaluatie: de setup-, template- en linkcontrollers bestaan alleen met
        // catalogimport.setup-api.enabled=true, maar hun endpoints horen in de dekkingscontrole (anders zou deze test
        // exact de endpoints missen die door de vlag beschermd worden).
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.core.type.classreading.MetadataReader reader)
                    throws java.io.IOException {
                return restController.match(reader, getMetadataReaderFactory())
                        || controller.match(reader, getMetadataReaderFactory());
            }
        };
        scanner.addIncludeFilter(restController);
        scanner.addIncludeFilter(controller);
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("be.dda.catalogimport.web")) {
            result.add(ClassUtils.forName(definition.getBeanClassName(), PermissionCoverageTest.class.getClassLoader()));
        }
        return result;
    }
}
