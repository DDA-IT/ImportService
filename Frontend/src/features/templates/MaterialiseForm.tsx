/**
 * S1-F3 — materialiseren (`POST /templates/{definitionId}/materialisations`), het schrijfdeel van
 * scherm 1b (`docs/decisions.md` 2026-09-27 "scherm 1a/1b", S1-F3-alinea).
 *
 * **`mode` heeft bewust geen voorselectie** (ontwerp §4 B1, patroon van `CreateBundleForm`'s
 * `targetMode`): een stil geraden "nieuw" of "hergebruik" bepaalt of twee leveranciers voortaan één
 * configuratie delen. De sentinel `NO_MODE` is de enige beginwaarde, en bij submit zonder keuze volgt een
 * cliëntvalidatiefout in plaats van een verzoek.
 *
 * Wat deze UI spiegelt van de backendregels (de server blijft telkens het vangnet, nooit omgekeerd):
 * - `REUSE_DEFINITION` toont geen definitievelden en geen `DEFINITION`-scope bookmarks, en verstuurt ze
 *   ook niet — spiegel van 400 `DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE` (§6 punt 4).
 * - alleen een `shareable`-definitie is kiesbaar; een niet-deelbare staat er wél, uitgeschakeld, met
 *   `blockingBookmarkName` als reden — spiegel van 409 `DEFINITION_NOT_SHAREABLE` (§6 punt 5).
 * - vult een bookmark een `LINK_*`-plaats, dan verdwijnt het bijhorende invoerveld: één bron per waarde,
 *   spiegel van 400 `LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT` (§4 D7).
 * - een bookmark die de gebruiker niet aanraakt, wordt **niet** meegestuurd ("niet ingevuld", de default
 *   van het sjabloon geldt); "uitdrukkelijk leeg" is een aparte keuze die `""` verstuurt (R-BMK-03).
 *
 * De sjabloonrevisie wordt altijd expliciet meegestuurd, ook de `ACTIVE`: materialiseren uit een
 * `SUPERSEDED` versie mag, maar nooit stilzwijgend (§4 fase A, beslissingslog 23/09 Q3).
 */
import { useMemo, useState, type FormEvent } from 'react';
import * as templatesApi from '../../api/templates.ts';
import {
  BOOKMARK_PLACE_LINK_LIBRARY_CODE,
  BOOKMARK_PLACE_LINK_SEARCH_SUPPLIER,
  BOOKMARK_PLACE_LINK_SUPPLIER_ORGANISATION,
  BOOKMARK_SCOPE_DEFINITION,
  MATERIALISATION_MODES,
  PERMISSION_MANAGE,
  type BookmarkView,
  type MaterialisationMode,
  type MaterialisationView,
  type MaterialiseBookmarkValue,
  type MaterialisedDefinitionView,
  type MaterialiseRequest,
} from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate } from '../../actor/permissions.ts';
import { useAction } from '../../hooks/useAction.ts';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { term, termLabel } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { INVULPUNT, INVULPUNTEN, INVULPUNTEN_CAP } from '../../terms/wording.ts';
import styles from './MaterialiseForm.module.css';

const NO_MODE = '';
type ModeSelection = MaterialisationMode | typeof NO_MODE;

/** NT-11c (V7): de twee manieren komen als Nederlandse zin uit het woordenboek (`materialisationMode`). */
function modeLabel(mode: MaterialisationMode): string {
  return term('materialisationMode', mode).label;
}

/** De melding van de server bij een geslaagde materialisatie in gewoon Nederlands; de code staat in de tooltip. */
function describeWarning(code: string): { label: string; uitleg: string } {
  const known = term('materialisationWarning', code);
  return known.uitleg === ''
    ? { label: 'Een melding van de server', uitleg: 'Voor deze melding bestaat nog geen Nederlandse uitleg.' }
    : { label: known.label, uitleg: known.uitleg };
}

/** Eén invulpunt: onaangeroerd (geen entry) ≠ uitdrukkelijk leeg (`explicitEmpty`) — R-BMK-03. */
type ValueEntry = { text: string; explicitEmpty: boolean };

export type MaterialiseFormProps = {
  definitionId: number;
  /** De gekozen sjabloonrevisie; altijd expliciet meegestuurd, nooit impliciet "de ACTIVE". */
  templateRevisionId: number;
  templateRevisionNumber: number;
  templateRevisionStatus: string;
  /** De al geladen invulset van deze revisie (S1-F2). */
  bookmarks: BookmarkView[];
  /** De al geladen materialisatiehistoriek (S1-F2) — de keuzelijst voor hergebruik. */
  materialisations: MaterialisedDefinitionView[];
  /** Na een geslaagde materialisatie: historiek herladen en de nieuwe koppeling openen. */
  onMaterialised: (result: MaterialisationView) => void;
  /**
   * NT-7 — voorinvulling van "Leverancierscode" (het stappenplan kent de leverancier al). Enkel een beginwaarde:
   * het veld blijft bewerkbaar en de server valideert zoals altijd. Wijzigt de waarde, dan hoort de aanroeper
   * het formulier met een nieuwe `key` opnieuw te monteren.
   */
  defaultSupplierCode?: string;
};

/** Welke `LINK_*`-plaatsen al door een bookmark gevuld worden (§4 D7). */
function placesFilledByBookmark(bookmarks: BookmarkView[]): Set<string> {
  const places = new Set<string>();
  for (const bookmark of bookmarks) {
    for (const usage of bookmark.usages) {
      places.add(usage.placeKind);
    }
  }
  return places;
}

/** `true` wanneer het sjabloon zelf een bruikbare standaardwaarde levert (`''` is er géén). */
function hasUsableDefault(bookmark: BookmarkView): boolean {
  return bookmark.defaultValue !== null && bookmark.defaultValue.trim() !== '';
}

export function MaterialiseForm({
  definitionId,
  templateRevisionId,
  templateRevisionNumber,
  templateRevisionStatus,
  bookmarks,
  materialisations,
  onMaterialised,
  defaultSupplierCode,
}: MaterialiseFormProps) {
  const { actor } = useActor();
  const manageGate = usePermissionGate(PERMISSION_MANAGE);

  const [mode, setMode] = useState<ModeSelection>(NO_MODE);
  const [reuseDefinitionId, setReuseDefinitionId] = useState<string>('');
  const [definitionCode, setDefinitionCode] = useState('');
  const [definitionName, setDefinitionName] = useState('');
  const [changeReason, setChangeReason] = useState('');
  const [linkCode, setLinkCode] = useState('');
  const [linkName, setLinkName] = useState('');
  const [supplierOrganisationCode, setSupplierOrganisationCode] = useState(defaultSupplierCode ?? '');
  const [libraryCode, setLibraryCode] = useState('');
  const [librarySearchSupplierCode, setLibrarySearchSupplierCode] = useState('');
  const [values, setValues] = useState<Record<string, ValueEntry>>({});
  const [validationError, setValidationError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<MaterialisationView | null>(null);

  const reuse = mode === 'REUSE_DEFINITION';
  const places = useMemo(() => placesFilledByBookmark(bookmarks), [bookmarks]);
  const supplierFromBookmark = places.has(BOOKMARK_PLACE_LINK_SUPPLIER_ORGANISATION);
  const libraryFromBookmark = places.has(BOOKMARK_PLACE_LINK_LIBRARY_CODE);
  const searchSupplierFromBookmark = places.has(BOOKMARK_PLACE_LINK_SEARCH_SUPPLIER);

  // Bij hergebruik liggen de DEFINITION-waarden vast in de bestaande revisie: niet tonen, niet versturen.
  const scopedBookmarks = bookmarks.filter(
    (bookmark) => !(reuse && bookmark.valueScope === BOOKMARK_SCOPE_DEFINITION),
  );

  const { execute, pending, error, reset } = useAction((body: MaterialiseRequest) =>
    templatesApi.materialiseTemplate(definitionId, body),
  );

  function setText(name: string, text: string) {
    setValues((previous) => ({ ...previous, [name]: { text, explicitEmpty: false } }));
  }

  function setExplicitEmpty(name: string, explicitEmpty: boolean) {
    setValues((previous) => {
      if (!explicitEmpty) {
        const next = { ...previous };
        // Het vinkje weghalen zet de bookmark terug op "niet ingevuld", niet op een lege waarde.
        delete next[name];
        return next;
      }
      return { ...previous, [name]: { text: '', explicitEmpty: true } };
    });
  }

  function collectValues(): MaterialiseBookmarkValue[] {
    const collected: MaterialiseBookmarkValue[] = [];
    for (const bookmark of scopedBookmarks) {
      const entry = values[bookmark.name];
      if (entry === undefined) {
        continue;
      }
      if (entry.explicitEmpty) {
        collected.push({ name: bookmark.name, value: '' });
        continue;
      }
      if (entry.text === '') {
        // Een leeggemaakt tekstveld is "niet ingevuld"; uitdrukkelijk leeg vraagt het vinkje.
        continue;
      }
      collected.push({ name: bookmark.name, value: entry.text });
    }
    return collected;
  }

  /** De eerste cliëntvalidatiefout, of `null`. De server valideert alles opnieuw. */
  function firstValidationError(): string | null {
    if (mode === NO_MODE) {
      return 'Kies of dit een nieuwe beschrijving van het bestand wordt of een bestaande hergebruikt.';
    }
    if (reuse && reuseDefinitionId === '') {
      return 'Kies de bestaande beschrijving waaraan deze koppeling moet hangen.';
    }
    if (!reuse && definitionCode.trim() === '') {
      return 'Vul de code van de beschrijving in.';
    }
    if (!reuse && definitionName.trim() === '') {
      return 'Vul de naam van de beschrijving in.';
    }
    if (linkCode.trim() === '') {
      return 'Vul een koppelingscode in.';
    }
    if (linkName.trim() === '') {
      return 'Vul een koppelingsnaam in.';
    }
    if (!supplierFromBookmark && supplierOrganisationCode.trim() === '') {
      return 'Vul de leverancierscode in.';
    }
    if (!libraryFromBookmark && libraryCode.trim() === '') {
      return 'Vul de bibliotheekcode in.';
    }
    for (const bookmark of scopedBookmarks) {
      if (!bookmark.required || hasUsableDefault(bookmark)) {
        continue;
      }
      const entry = values[bookmark.name];
      if (entry === undefined || entry.explicitEmpty || entry.text.trim() === '') {
        return `Vul het verplichte ${INVULPUNT} "${bookmark.label}" in; een uitdrukkelijk lege waarde geldt niet als invulling.`;
      }
    }
    return null;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!manageGate.allowed) {
      return;
    }
    setOutcome(null);
    reset();

    const problem = firstValidationError();
    if (problem !== null) {
      setValidationError(problem);
      return;
    }
    setValidationError(null);

    const result = await execute({
      templateRevisionId,
      mode: mode as MaterialisationMode,
      reuseDefinitionId: reuse ? Number(reuseDefinitionId) : null,
      definitionCode: reuse ? null : definitionCode.trim(),
      definitionName: reuse ? null : definitionName.trim(),
      changeReason: reuse || changeReason.trim() === '' ? null : changeReason.trim(),
      linkCode: linkCode.trim(),
      linkName: linkName.trim(),
      supplierOrganisationCode: supplierFromBookmark ? null : supplierOrganisationCode.trim(),
      libraryCode: libraryFromBookmark ? null : libraryCode.trim(),
      librarySearchSupplierCode:
        searchSupplierFromBookmark || librarySearchSupplierCode.trim() === ''
          ? null
          : librarySearchSupplierCode.trim(),
      bookmarkValues: collectValues(),
      materialisedBy: actor,
    });
    if (result === undefined) {
      return;
    }
    setOutcome(result);
    // Het formulier gaat leeg terug naar de beginstand, `mode` inbegrepen (patroon `CreateBundleForm`).
    // Materialiseren is bewust NIET idempotent (ontwerp A35): een tweede klik met dezelfde codes zou de
    // gebruiker een 409 `LINK_CODE_IN_USE` opleveren in plaats van een duidelijke, lege volgende invulling.
    setMode(NO_MODE);
    setReuseDefinitionId('');
    setDefinitionCode('');
    setDefinitionName('');
    setChangeReason('');
    setLinkCode('');
    setLinkName('');
    setSupplierOrganisationCode('');
    setLibraryCode('');
    setLibrarySearchSupplierCode('');
    setValues({});
    onMaterialised(result);
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} data-testid="materialise-form">
      <h3 className={styles.title}>Materialiseren</h3>
      <p className={styles.revisionRow}>
        Sjabloonversie: <strong>{templateRevisionNumber}</strong> ({term('revisionStatus', templateRevisionStatus).label})
        {templateRevisionStatus === 'SUPERSEDED' && (
          <span className={styles.supersededNote}>
            {' '}
            — dit is een oudere sjabloonversie; dat mag, maar gebeurt hier bewust en niet stilzwijgend.
          </span>
        )}
      </p>

      <Field
        label="Nieuw of hergebruik"
        htmlFor="materialise-mode"
        required
        hint="Geen automatische keuze: er is bewust geen voorselectie. Deze keuze bepaalt of twee leveranciers één configuratie delen."
        help={
          <>
            <strong>Nieuw</strong> maakt een nieuwe beschrijving van het bestand met een conceptversie en een
            koppeling; die beschrijving is dan van de organisatie van het sjabloon. <strong>Hergebruik</strong>{' '}
            hangt alleen een koppeling aan een bestaande beschrijving die gedeeld mag worden; die beschrijving blijft
            ongewijzigd en heeft daarna meerdere leveranciers.
          </>
        }
      >
        <select
          id="materialise-mode"
          className={styles.select}
          value={mode}
          onChange={(event) => setMode(event.target.value as ModeSelection)}
        >
          <option value={NO_MODE}>— kies nieuw of hergebruik —</option>
          {MATERIALISATION_MODES.map((option) => (
            <option key={option} value={option}>
              {modeLabel(option)}
            </option>
          ))}
        </select>
      </Field>

      {mode === 'NEW_DEFINITION' && (
        <>
          <Field
            label="Code van de beschrijving"
            htmlFor="materialise-definition-code"
            required
            help={term('setupField', 'code').uitleg}
          >
            <input
              id="materialise-definition-code"
              className={styles.input}
              type="text"
              value={definitionCode}
              onChange={(event) => setDefinitionCode(event.target.value)}
              maxLength={50}
            />
          </Field>
          <Field label="Naam van de beschrijving"htmlFor="materialise-definition-name" required>
            <input
              id="materialise-definition-name"
              className={styles.input}
              type="text"
              value={definitionName}
              onChange={(event) => setDefinitionName(event.target.value)}
              maxLength={200}
            />
          </Field>
          <Field
            label="Wijzigingsreden"
            htmlFor="materialise-change-reason"
            hint="Optioneel; zonder opgave bewaart de server een vaste zin met sjabloon en versie."
          >
            <input
              id="materialise-change-reason"
              className={styles.input}
              type="text"
              value={changeReason}
              onChange={(event) => setChangeReason(event.target.value)}
              maxLength={500}
            />
          </Field>
        </>
      )}

      {reuse && (
        <Field
          label="Bestaande beschrijving"
          htmlFor="materialise-reuse-definition"
          required
          hint="Alleen een deelbare beschrijving uit dit sjabloon; de beschrijving en haar versie blijven ongewijzigd."
        >
          <select
            id="materialise-reuse-definition"
            className={styles.select}
            value={reuseDefinitionId}
            onChange={(event) => setReuseDefinitionId(event.target.value)}
          >
            <option value="">— kies een bestaande beschrijving —</option>
            {materialisations.map((row) => {
              // De technische naam van de bookmark staat niet in de zichtbare tekst (V7): het label van de bookmark,
              // of — bij een onbekende naam — een neutrale omschrijving.
              const blockingLabel =
                row.blockingBookmarkName === null
                  ? null
                  : (bookmarks.find((bookmark) => bookmark.name === row.blockingBookmarkName)?.label ?? null);
              const reason =
                row.blockingBookmarkName !== null
                  ? blockingLabel !== null
                    ? `niet deelbaar door ${INVULPUNT} "${blockingLabel}"`
                    : `niet deelbaar door een ${INVULPUNT} van dit sjabloon`
                  : 'niet deelbaar: geen herkomstversie uit dit sjabloon';
              const revisionPart =
                row.templateRevisionNumber !== null ? `sjabloonversie ${row.templateRevisionNumber}` : 'geen sjabloonversie';
              return (
                <option
                  key={row.definitionId}
                  value={String(row.definitionId)}
                  disabled={!row.shareable}
                  title={
                    row.blockingBookmarkName !== null
                      ? `Technische naam van het ${INVULPUNT}: ${row.blockingBookmarkName}`
                      : undefined
                  }
                >
                  {row.definitionCode} — {row.definitionName} ({revisionPart}, {row.importLinkCount}{' '}
                  koppeling(en))
                  {row.shareable ? '' : ` — ${reason}`}
                </option>
              );
            })}
          </select>
        </Field>
      )}

      {mode !== NO_MODE && (
        <>
          <Field label="Koppelingscode" htmlFor="materialise-link-code" required>
            <input
              id="materialise-link-code"
              className={styles.input}
              type="text"
              value={linkCode}
              onChange={(event) => setLinkCode(event.target.value)}
              maxLength={50}
            />
          </Field>
          <Field label="Koppelingsnaam" htmlFor="materialise-link-name" required>
            <input
              id="materialise-link-name"
              className={styles.input}
              type="text"
              value={linkName}
              onChange={(event) => setLinkName(event.target.value)}
              maxLength={200}
            />
          </Field>

          {supplierFromBookmark ? (
            <p className={styles.fromBookmark}>
              De leverancierscode komt uit een {INVULPUNT} van dit sjabloon (bestemming:{' '}
              {term('bookmarkPlace', BOOKMARK_PLACE_LINK_SUPPLIER_ORGANISATION).label}); vul ze hieronder bij dat{' '}
              {INVULPUNT} in.
            </p>
          ) : (
            <Field
              label="Leverancierscode"
              htmlFor="materialise-supplier-code"
              required
              help={term('setupField', 'supplierCode').uitleg}
            >
              <input
                id="materialise-supplier-code"
                className={styles.input}
                type="text"
                value={supplierOrganisationCode}
                onChange={(event) => setSupplierOrganisationCode(event.target.value)}
                maxLength={50}
              />
            </Field>
          )}

          {libraryFromBookmark ? (
            <p className={styles.fromBookmark}>
              De bibliotheekcode komt uit een {INVULPUNT} van dit sjabloon (bestemming:{' '}
              {term('bookmarkPlace', BOOKMARK_PLACE_LINK_LIBRARY_CODE).label}); vul ze hieronder bij dat {INVULPUNT} in.
            </p>
          ) : (
            <Field
              label="Bibliotheekcode"
              htmlFor="materialise-library-code"
              required
              help={term('setupField', 'libraryCode').uitleg}
            >
              <input
                id="materialise-library-code"
                className={styles.input}
                type="text"
                value={libraryCode}
                onChange={(event) => setLibraryCode(event.target.value)}
                maxLength={20}
              />
            </Field>
          )}

          {searchSupplierFromBookmark ? (
            <p className={styles.fromBookmark}>
              De bibliotheekzoekleverancier komt uit een {INVULPUNT} van dit sjabloon (bestemming:{' '}
              {term('bookmarkPlace', BOOKMARK_PLACE_LINK_SEARCH_SUPPLIER).label}).
            </p>
          ) : (
            <Field
              label="Bibliotheekzoekleverancier"
              htmlFor="materialise-search-supplier-code"
              hint="Optioneel en wordt nooit automatisch uit de leverancier afgeleid; blijft ze leeg, dan meldt de server dat met een waarschuwing."
              help={term('setupField', 'librarySearchSupplierCode').uitleg}
            >
              <input
                id="materialise-search-supplier-code"
                className={styles.input}
                type="text"
                value={librarySearchSupplierCode}
                onChange={(event) => setLibrarySearchSupplierCode(event.target.value)}
                maxLength={50}
              />
            </Field>
          )}

          <fieldset className={styles.fieldset} data-testid="materialise-bookmark-values">
            <legend className={styles.legend}>{INVULPUNTEN_CAP}</legend>
            <WhatIsThis>
              Een {INVULPUNT} is een waarde die u hier invult. Een waarde &laquo;voor de hele beschrijving&raquo; wordt bij
              het materialiseren vastgelegd; een waarde &laquo;per koppeling&raquo; geldt alleen voor deze koppeling.
              Laat u een optionele waarde leeg, dan geldt de standaardwaarde van het sjabloon, als die er is.
            </WhatIsThis>
            {reuse && (
              <p className={styles.scopeNote}>
                Bij hergebruik worden alleen de waarden per koppeling gevraagd: de waarden voor de hele beschrijving
                liggen vast in de bestaande versie en zouden bij elke leverancier op die beschrijving tegelijk
                veranderen.
              </p>
            )}
            {scopedBookmarks.length === 0 ? (
              <p className={styles.empty}>Geen {INVULPUNTEN} om in te vullen.</p>
            ) : (
              scopedBookmarks.map((bookmark) => {
                const entry = values[bookmark.name];
                const explicitEmpty = entry?.explicitEmpty === true;
                const inputId = `materialise-bookmark-${bookmark.name}`;
                return (
                  <div key={bookmark.id} className={styles.bookmarkRow} data-testid={`bookmark-field-${bookmark.name}`}>
                    <Field
                      label={bookmark.label}
                      htmlFor={inputId}
                      required={bookmark.required}
                      hint={
                        hasUsableDefault(bookmark)
                          ? `${termLabel('bookmarkScope', bookmark.valueScope, 'Andere geldigheid')} · standaard uit het sjabloon: ${bookmark.defaultValue}`
                          : termLabel('bookmarkScope', bookmark.valueScope, 'Andere geldigheid')
                      }
                      help={
                        <>
                          {bookmark.description !== null && bookmark.description !== '' && <>{bookmark.description} </>}
                          Soort waarde: {termLabel('bookmarkDataType', bookmark.dataType, 'Ander soort waarde')}.
                        </>
                      }
                    >
                      <input
                        id={inputId}
                        className={styles.input}
                        type="text"
                        value={entry?.text ?? ''}
                        disabled={explicitEmpty}
                        onChange={(event) => setText(bookmark.name, event.target.value)}
                        maxLength={500}
                      />
                    </Field>
                    <div className={styles.checkboxRow}>
                      <input
                        id={`${inputId}-empty`}
                        type="checkbox"
                        checked={explicitEmpty}
                        onChange={(event) => setExplicitEmpty(bookmark.name, event.target.checked)}
                      />{' '}
                      <label htmlFor={`${inputId}-empty`}>
                        Uitdrukkelijk leeg laten (verstuurt een lege waarde; niet hetzelfde als niets
                        invullen)
                      </label>
                    </div>
                  </div>
                );
              })
            )}
          </fieldset>
        </>
      )}

      <p className={styles.actorRow}>
        Gematerialiseerd door: <strong>{actor}</strong>
      </p>

      {validationError !== null && (
        <p className={styles.validationError} role="alert">
          {validationError}
        </p>
      )}
      {error !== null && <ErrorBanner error={error} />}

      {outcome !== null && (
        <div className={styles.notice} role="status" data-testid="materialise-result">
          <p>
            {outcome.definitionCreated
              ? `Nieuwe beschrijving ${outcome.definitionCode} gematerialiseerd`
              : `Bestaande beschrijving ${outcome.definitionCode} hergebruikt`}{' '}
            — koppeling <strong>{outcome.importLinkCode}</strong>, versie{' '}
            {outcome.definitionRevisionNumber} ({term('revisionStatus', outcome.definitionRevisionStatus).label}).
            Gebruikte sjabloonversie: {outcome.templateRevisionNumber} (
            {term('revisionStatus', outcome.templateRevisionStatus).label}).
          </p>
          {outcome.warnings.length > 0 && (
            <ul className={styles.warnings} data-testid="materialise-warnings">
              {outcome.warnings.map((warning, index) => {
                const described = describeWarning(warning.code);
                const bookmarkLabel =
                  warning.bookmarkName === null
                    ? null
                    : (bookmarks.find((bookmark) => bookmark.name === warning.bookmarkName)?.label ?? null);
                return (
                  <li
                    key={`${warning.code}-${warning.bookmarkName ?? ''}-${index}`}
                    title={`technische code: ${warning.code}${warning.bookmarkName !== null ? ` · ${warning.bookmarkName}` : ''} · ${warning.message}`}
                  >
                    {described.label}
                    {bookmarkLabel !== null ? ` (${INVULPUNT} "${bookmarkLabel}")` : ''} — {described.uitleg}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {!manageGate.allowed && (
        <p className={styles.validationError} id="materialise-permission-reason" data-testid="permission-reason-materialise">
          {manageGate.reason}
        </p>
      )}
      <button
        type="submit"
        className={styles.submit}
        disabled={pending || !manageGate.allowed}
        title={manageGate.allowed ? undefined : manageGate.reason}
        aria-describedby={manageGate.allowed ? undefined : 'materialise-permission-reason'}
      >
        {pending ? 'Bezig…' : 'Materialiseren'}
      </button>
    </form>
  );
}
