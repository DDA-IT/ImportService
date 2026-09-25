/**
 * De enige plek waar een backend-foutcode Nederlandse tekst wordt. Geen enkele andere module
 * schrijft zelf een foutzin — zie `docs/design/frontend-scherm3-bundel-design.md` §4 en §2 regel 3.
 */

import type { ApiError } from '../api/http';

export type CodeEntry = {
  title: string;
  explanation: string;
  whatNow?: string;
  /**
   * De servertekst (`error`) van deze code draagt concrete gegevens die de Nederlandse uitleg niet kan
   * bevatten — de conflictvoorbeelden (tot tien) of het aantal betrokken mutaties. Die tekst wordt dan
   * volledig en letterlijk mee getoond (§10.5: "de UI toont die melding volledig en leesbaar"), nooit
   * samengevat of ingekort.
   */
  showBackendDetail?: true;
};

/**
 * Alle stabiele backendcodes relevant voor dit scherm, uit
 * `docs/design/frontend-scherm3-bundel-design.md` §1.2.
 */
export const CODE_MESSAGES: Record<string, CodeEntry> = {
  BUNDLE_NOT_FOUND: {
    title: 'Bundel niet gevonden',
    explanation: 'Deze publicatiebundel bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar de bundellijst en zoek de bundel opnieuw op.',
  },
  BATCH_NOT_FOUND: {
    title: 'Batch niet gevonden',
    explanation: 'Deze batch (levering) bestaat niet (meer).',
  },
  DELIVERY_NOT_FOUND: {
    title: 'Levering niet gevonden',
    explanation: 'Deze levering bestaat niet (meer).',
  },
  BATCH_NOT_IN_BUNDLE: {
    title: 'Batch zit niet in deze bundel',
    explanation: 'De opgevraagde batch is geen (actief) lid van deze bundel.',
  },
  MUTATION_NOT_IN_BUNDLE: {
    title: 'Mutatie zit niet in deze bundel',
    explanation: 'Deze mutatie hoort niet bij een batch die lid is van deze bundel.',
  },
  BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE: {
    title: 'Referentie is al in gebruik met een andere scope',
    explanation:
      'Er bestaat al een bundel met deze bundelreferentie, maar met een andere doelmodus, ' +
      'doelmoment of publicatiebeleid dan wat u nu aanmaakt.',
    whatNow: 'Gebruik een andere referentie, of maak de bundel opnieuw aan met dezelfde scope als de bestaande.',
  },
  BUNDLE_NOT_ASSEMBLING: {
    title: 'Bundel is niet meer in opbouw',
    explanation:
      'Deze actie mag alleen zolang de bundel de status ASSEMBLING heeft. Deze bundel is al bevroren, ' +
      'geannuleerd of verder in het proces.',
  },
  BUNDLE_NOT_FROZEN: {
    title: 'Bundel is nog niet bevroren',
    explanation: 'De PSIMPORT-preview is enkel beschikbaar voor een bundel met de status FROZEN.',
    whatNow: 'Bevries de bundel eerst, of kies een bevroren bundel.',
  },
  BATCH_ALREADY_IN_BUNDLE: {
    title: 'Batch zit al in een bundel',
    explanation: 'Deze batch is al lid van deze of een andere bundel en kan niet nogmaals toegevoegd worden.',
  },
  BATCH_IN_PUBLICATION_BUNDLE: {
    title: 'Batch zit in een publicatiebundel',
    explanation:
      'Aanvaarden als nulmeting (accept-baseline) en opname in een bundel sluiten elkaar per batch uit. ' +
      'Deze batch is al lid van een niet-geannuleerde bundel en kan daarom niet als nulmeting aanvaard worden.',
    whatNow: 'Werk de batch af via de bundel, of annuleer de bundel; daarna is accept-baseline weer mogelijk.',
  },
  BATCH_NOT_ACCEPTABLE: {
    title: 'Batch kan niet aanvaard worden',
    explanation:
      'Aanvaarden als nulmeting kan alleen vanuit de status SCREENED, en één keer per batch. Deze batch is ' +
      'al aanvaard of nog niet (of niet meer) gescreend.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_RESUMABLE: {
    title: 'Batch kan niet hervat worden',
    explanation: 'Hervatten (continue) kan alleen vanuit de status MUTATING. Deze batch staat in een andere status.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_BUNDLEABLE: {
    title: 'Batch is niet bundelbaar',
    explanation: 'Deze batch voldoet niet aan de voorwaarden om in een publicatiebundel opgenomen te worden.',
  },
  BATCH_VALIDATION_NOT_ESTABLISHED: {
    title: 'Batch is nog niet gevalideerd',
    explanation: 'De screening van deze batch is nog niet afgerond; het validatieresultaat staat nog niet vast.',
  },
  BATCH_VALIDATION_BLOCKING: {
    title: 'Batch heeft blokkerende validatiefouten',
    explanation: 'De screening van deze batch bevat blokkerende problemen; de batch kan zo niet toegevoegd worden.',
  },
  BATCH_HAS_DECIDED_MUTATIONS: {
    title: 'Batch draagt al beslissingen',
    explanation:
      'Deze batch draagt al beslissingen; verwijderen zou een ondertekende beslissing uit het dossier ' +
      'laten verdwijnen.',
  },
  MUTATION_NOT_DECIDABLE: {
    title: 'Mutatie is niet beslisbaar',
    explanation: 'Deze mutatie staat in een status waarin goedkeuren of afkeuren niet mogelijk is.',
  },
  MUTATION_BLOCKED_BY_IDENTITY_INCIDENT: {
    title: 'Geblokkeerd door een identiteitsincident',
    explanation:
      'Deze mutatie is geblokkeerd door een kritiek identiteitsincident; deze fase van de applicatie ' +
      'kent hier geen beslispad.',
  },
  IDENTITY_DECISION_NOT_IN_SCOPE: {
    title: 'Identiteitsbeslissing hoort hier niet',
    explanation:
      'Een identiteitsbeslissing schrijft in de referentiestaat; dat komt in een latere fase van de ' +
      'applicatie.',
  },
  DECISION_FILTER_REQUIRED: {
    title: 'Filter ontbreekt',
    explanation:
      'Een groepsbeslissing vereist minstens één filterveld (batch, status, soort, statusreden of ' +
      'wijzigingsgroep).',
  },
  DECISION_FILTER_UNKNOWN_FIELD: {
    title: 'Onbekend filterveld',
    explanation:
      'De groepsbeslissing bevat een veld dat de server niet kent; ze is geweigerd zodat ze niet ' +
      'meer mutaties raakt dan je ziet.',
    showBackendDetail: true,
  },
  BUNDLE_EMPTY: {
    title: 'Bundel is leeg',
    explanation: 'Deze bundel heeft geen (actieve) leden en kan zo niet bevroren worden.',
    whatNow: 'Voeg eerst minstens één batch toe.',
  },
  BUNDLE_HAS_UNDECIDED_MUTATIONS: {
    title: 'Er wachten nog mutaties op een beslissing',
    explanation: 'Bevriezen mag die vraag niet stilzwijgend beantwoorden.',
    whatNow: 'Beoordeel eerst de mutaties met status AWAITING_APPROVAL.',
    showBackendDetail: true,
  },
  SOURCE_STATE_CHANGED_SINCE_SCREENING: {
    title: 'Bronstaat is veranderd sinds de screening',
    explanation: 'De bronstaat is verschoven sinds de screening van een batch in deze bundel.',
    whatNow: 'Screen de betrokken levering opnieuw.',
    showBackendDetail: true,
  },
  BUNDLE_OFFER_CONFLICT: {
    title: 'Conflict tussen aanbiedingen',
    explanation:
      'Twee publiceerbare mutaties in deze bundel raken dezelfde aanbieding. De backend geeft hieronder ' +
      'tot tien voorbeelden.',
    whatNow: 'Keur er één af, of publiceer/annuleer eerst de andere bundel.',
    showBackendDetail: true,
  },
  OFFER_ALREADY_IN_ANOTHER_BUNDLE: {
    title: 'Aanbieding zit al in een andere bundel',
    // Spiegel van PublicationBundleDao.findCrossBundleOfferConflicts: elke andere bundel die niet
    // CANCELLED is telt mee — dus ook een bundel die nog in opbouw is, niet enkel een bevroren.
    explanation:
      'Een aanbieding in deze bundel staat ook publiceerbaar in een andere bundel die niet geannuleerd ' +
      'is (in opbouw of bevroren). De backend geeft hieronder tot tien voorbeelden.',
    whatNow: 'Keur één kant af, of publiceer/annuleer eerst de andere bundel.',
    showBackendDetail: true,
  },
  BUNDLE_CONTENT_CHANGED_DURING_FREEZE: {
    title: 'Inhoud is veranderd tijdens het bevriezen',
    explanation: 'De inhoud van de bundel is tussen het laden en het bevestigen van deze actie gewijzigd.',
    whatNow: 'Laad de bundel opnieuw en probeer opnieuw te bevriezen.',
    showBackendDetail: true,
  },
  BUNDLE_NOT_CANCELLABLE: {
    title: 'Bundel kan niet geannuleerd worden',
    explanation: 'Deze bundel staat in een status waarin annuleren niet meer mogelijk is.',
  },
  TASK_NOT_FOUND: {
    title: 'Taak niet gevonden',
    explanation: 'De gekozen taak bestaat niet (meer).',
    whatNow: 'Laad de takenlijst opnieuw en kies een bestaande taak.',
  },
  TASK_NOT_MANUAL: {
    title: 'Taak is niet manueel',
    explanation: 'Alleen een taak met trigger MANUAL neemt een handmatig geüploade levering aan.',
    whatNow: 'Kies een manuele taak.',
  },
  NO_ACTIVE_REVISION: {
    title: 'Geen actieve revisie',
    explanation: 'De importdefinitie van deze taak heeft geen actieve revisie; een levering kan dus niet gescreend worden.',
    whatNow: 'Activeer eerst een revisie (materialisatie van het sjabloon).',
  },
  TASK_RUN_IN_PROGRESS: {
    title: 'Er loopt al een uitvoering voor deze taak',
    explanation: 'Deze taak laat geen gelijktijdige uitvoeringen toe en er loopt er al een.',
    whatNow: 'Wacht tot de lopende uitvoering klaar is. Was dat uw eigen upload die wegviel? Herhaal dan met dezelfde referentie.',
  },
  DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT: {
    title: 'Referentie is al gebruikt voor een ander bestand',
    explanation:
      'Bij deze taak bestaat al een levering met deze referentie, maar met een andere bestandsinhoud. ' +
      'Er is niets opgeslagen.',
    whatNow: 'Geef dit bestand een nieuwe referentie.',
  },
  BUNDLE_CONTENT_CHANGED_DURING_CANCEL: {
    title: 'Inhoud is veranderd tijdens het annuleren',
    explanation: 'De inhoud van de bundel is tussen het laden en het bevestigen van deze actie gewijzigd.',
    whatNow: 'Laad de bundel opnieuw en probeer opnieuw te annuleren.',
    showBackendDetail: true,
  },
};

type FamilyFallback = { match: (code: string) => boolean; explanation: string };

/**
 * Familie-fallbacks voor een onbekende code, op basis van voor-/achtervoegsel — zie §4 regel 3. De
 * titel draagt in dat geval altijd de code letterlijk.
 */
const FAMILY_FALLBACKS: FamilyFallback[] = [
  {
    match: (code) => code.startsWith('CONFIG_') || code.startsWith('CONFIG'),
    explanation: 'De configuratie van de importdefinitie is onvolledig of ongeldig.',
  },
  {
    match: (code) => code.endsWith('_NOT_FOUND'),
    explanation: 'Niet gevonden.',
  },
  {
    match: (code) => code.endsWith('_IN_USE'),
    explanation: 'Die code of scope is al in gebruik.',
  },
  {
    match: (code) => code.includes('_CHANGED'),
    explanation: 'De toestand is ondertussen veranderd; lees opnieuw.',
  },
];

const GENERIC_409_EXPLANATION = 'De bewerking is geweigerd in de huidige toestand.';

/** Formatteert de technische regel die §4 regel 1 altijd verplicht: `<code> · HTTP <status> · <pad>`. */
function technicalLine(code: string | null, status: number, path: string): string {
  return `${code ?? 'geen code'} · HTTP ${status} · ${path}`;
}

/**
 * Vertaalt een `ApiError` naar de Nederlandse weergave, volgens de vijf regels van §4.
 *
 * `detail` is de letterlijke, volledige servertekst voor een bekende code met `showBackendDetail`
 * (bv. de conflictvoorbeelden van `BUNDLE_OFFER_CONFLICT`), anders `null`. Additief: bestaande
 * aanroepers die `detail` niet lezen, gedragen zich ongewijzigd.
 */
export function describe(error: ApiError): {
  title: string;
  explanation: string;
  whatNow: string | null;
  technical: string;
  detail: string | null;
} {
  const technical = technicalLine(error.code, error.status, error.path);

  // Regel 5: 500 of netwerkfout/afgebroken verzoek (status 0) — de server levert bewust geen details.
  if (error.status === 0 || error.status === 500) {
    return {
      title: error.status === 0 ? 'Geen verbinding met de server' : 'Onverwachte serverfout',
      explanation:
        error.status === 0
          ? 'Het verzoek kon niet verstuurd worden, of het antwoord kwam niet aan. De server geeft in dit ' +
            'geval geen verdere details.'
          : 'De server gaf een onverwachte fout terug. De server stuurt bewust geen foutdetails mee bij dit ' +
            'type fout.',
      whatNow: 'Raadpleeg het serverlogboek voor de precieze oorzaak.',
      technical,
      detail: null,
    };
  }

  // Regel 2 en 3: er is een code.
  if (error.code !== null) {
    const known = CODE_MESSAGES[error.code];
    if (known) {
      return {
        title: known.title,
        explanation: known.explanation,
        whatNow: known.whatNow ?? null,
        technical,
        // Volledig en letterlijk, nooit ingekort: de voorbeelden zijn precies wat de gebruiker nodig heeft.
        detail: known.showBackendDetail === true ? error.backendMessage : null,
      };
    }

    const family = FAMILY_FALLBACKS.find((f) => f.match(error.code!));
    const explanation = family ? family.explanation : GENERIC_409_EXPLANATION;
    return {
      title: `Geweigerd (${error.code})`,
      explanation,
      whatNow: null,
      technical,
      detail: null,
    };
  }

  // 413 zonder code: `MaxUploadSizeExceededException` heeft geen handler die een code levert
  // (zie ontdekking in docs/decisions.md 2026-09-23); enkel de status is betrouwbaar.
  if (error.status === 413 && error.code === null) {
    return {
      title: 'Bestand te groot',
      explanation: 'De server weigert dit bestand omdat het groter is dan de toegelaten uploadgrootte.',
      whatNow: 'Splits het bestand, of vraag de beheerder de limiet (CATALOG_MAX_UPLOAD_SIZE) te verhogen.',
      technical,
      detail: null,
    };
  }

  // Regel 4: 400 zonder code — de Engelse backendMessage letterlijk tonen, geen verzonnen Nederlandse zin.
  if (error.status === 400) {
    return {
      title: 'Ongeldige invoer',
      explanation:
        error.backendMessage ?? 'De server wees dit verzoek af, maar leverde geen verdere toelichting.',
      whatNow: 'De server levert hier geen stabiele foutcode; dit is de letterlijke ontwikkelaarstekst.',
      technical,
      detail: null,
    };
  }

  // Geen van de bovenstaande regels dekt dit geval expliciet (bv. een 404/409 zonder code, wat volgens
  // §1.2 vandaag niet voorkomt). Toon wat er wel is, zonder een oorzaak te verzinnen.
  return {
    title: 'De bewerking is geweigerd',
    explanation: error.backendMessage ?? 'De server leverde geen verdere toelichting bij deze fout.',
    whatNow: null,
    technical,
    detail: null,
  };
}
