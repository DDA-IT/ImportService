/**
 * S1-F4 — de schermspecifieke melding voor de 404-zonder-code die `CatalogImportSetupController` geeft
 * wanneer `catalogimport.setup-api.enabled=false` staat (`@ConditionalOnProperty`: de bean en de route
 * bestaan dan niet). Geldt voor de vier schrijfpaden E2/E3/E4/E5 van scherm 1a.
 *
 * Bewust NIET via de generieke `describe()`-fallback in `errors/codes.ts`: die kent geen aparte regel
 * voor een 404 zonder code en zou hier "De bewerking is geweigerd" tonen, wat de gebruiker in de
 * verkeerde richting stuurt (hij zou naar de status van de revisie gaan zoeken). Zelfde keuze en zelfde
 * vorm als `features/templates/setupApiFlag.ts` voor scherm 1b.
 *
 * De **detectie** wordt uit dat bestand hergebruikt in plaats van gekopieerd: het is exact hetzelfde
 * technische patroon (een uitgeschakelde `@ConditionalOnProperty`-controller), en twee implementaties
 * zouden op termijn uit elkaar lopen. Alleen de tekst verschilt, want het leesdeel van scherm 1a
 * (`api/setup.ts`, E1) blijft wél werken met de vlag uit — anders dan bij scherm 1b, waar het hele
 * scherm van de vlag afhangt.
 *
 * NT-11c (V7): de zichtbare melding is gewoon Nederlands; de naam van de serverinstelling staat enkel onder
 * "Technische details (voor support)" (`FlagOffNotice`).
 */
export { isSetupApiDisabledError } from '../templates/setupApiFlag.ts';

export const SETUP_WRITE_API_DISABLED_MESSAGE =
  'De schrijfacties op de inrichting (een opvolger maken, een concept aanpassen, activeren) staan niet ' +
  'open op deze omgeving. Er is niets gewijzigd. Lezen blijft wel werken: het detail van de versie ' +
  'hieronder is volledig en actueel.';
