/**
 * S1-F2 — scherm-specifieke afhandeling van de 404-zonder-code die `CatalogImportTemplateController`
 * geeft wanneer `catalogimport.setup-api.enabled=false` staat (`@ConditionalOnProperty`, de bean/route
 * bestaat dan niet). Bewust NIET via de generieke `describe()`-fallback in `errors/codes.ts` (die kent
 * geen aparte regel voor een 404 zonder code, zie `docs/decisions.md` 2026-09-27 "scherm 1a/1b" —
 * S1-F2-alinea): dit scherm hangt volledig van die vlag af, dus krijgt het zijn eigen, duidelijke tekst
 * in plaats van de generieke "De bewerking is geweigerd".
 */
import type { ApiError } from '../../api/http.ts';

export const SETUP_API_DISABLED_MESSAGE =
  'De sjabloon-/materialisatiewizard is niet actief op deze omgeving ' +
  '(catalogimport.setup-api.enabled=false). Dit scherm is alleen bruikbaar op een ontwikkelmachine met ' +
  'die vlag aan.';

/** `true` voor een 404 zonder `code` — het patroon van een uitgeschakelde `@ConditionalOnProperty`-controller. */
export function isSetupApiDisabledError(error: ApiError): boolean {
  return error.status === 404 && error.code === null;
}
