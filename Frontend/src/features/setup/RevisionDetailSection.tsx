/**
 * S1-F4 — het revisiedetail van scherm 1a: alles lezen (endpoint E1, vlagloos, recht `READ`) en op een
 * `DRAFT` de drie schrijfacties eronder (`docs/design/revision-successor-design.md` §6):
 * wijzigen (E3, {@link RevisionEditForm}), een geërfde mapping/recordfilter verwijderen (E4) en
 * activeren (E5, {@link ActivateRevisionAction} met de verplichte R-CASE-03-waarschuwing).
 *
 * Het leesdeel werkt op **elke** revisiestatus en blijft volledig bruikbaar wanneer
 * `catalogimport.setup-api.enabled` uit staat: enkel de schrijfacties horen achter die vlag (A1). Op een
 * niet-DRAFT revisie wordt het bewerkformulier en de verwijderknop niet getoond maar staat er de reden
 * bij (`revisionPolicy.editGate`), want een bevroren revisie wordt nooit bijgewerkt — daarvoor bestaat de
 * opvolgrevisie.
 *
 * **Harde ontwerpgrens §2:** de bookmarkdeclaraties en hun `DEFINITION`-waarden staan hier uitsluitend
 * alleen-lezen. Er is geen actie om een declaratie toe te voegen, te wijzigen of te verwijderen, en die
 * komt hier ook niet: zo is per constructie bewijsbaar dat een opvolgrevisie nooit een bestaande
 * LINK-bookmarkwaarde tot wees maakt. De tekst bij dat blok zegt dat expliciet, zodat de afwezigheid van
 * de actie geen vergetelheid lijkt.
 *
 * De vier configuratiehashes worden getoond als herkomstinformatie, met de waarschuwing dat ze de
 * mappings, filters, kritiek-overrules, drempels en het prijsbeleid **niet** dekken (ontdekking ontwerp
 * §9): twee revisies die enkel daarin verschillen, dragen dezelfde `compositeConfigHash`.
 *
 * NT-11c (V7): elk veld en elke waarde staat in gewoon Nederlands (woordenboek `revisionField` en de domeinen
 * voor de waarden); de technische veldnamen, hashes en versienummers staan enkel onder "Technische details (voor
 * support)", de code van een waarde in de tooltip. NT-11d: het begrip heet in alle tekst "invulpunt" (`terms/wording.ts`).
 */

import { useState, type ReactNode } from 'react';
import * as setupApi from '../../api/setup.ts';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { PERMISSION_MANAGE, type RevisionDetail } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { gateTitle } from '../../terms/gateTitle.ts';
import { termLabel } from '../../terms/index.ts';
import { INVULPUNT, INVULPUNT_CAP, INVULPUNTEN } from '../../terms/wording.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { ActivateRevisionAction } from './ActivateRevisionAction.tsx';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { RevisionEditForm } from './RevisionEditForm.tsx';
import { editGate } from './revisionPolicy.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './RevisionDetailSection.module.css';

const BOOKMARK_BOUNDARY_TEXT =
  `Gedeclareerde ${INVULPUNTEN} zijn hier bewust alleen-lezen: dit scherm voegt er geen toe, wijzigt en ` +
  `verwijdert er geen. Zo kan een opvolger nooit een bestaande waarde van een ${INVULPUNT} van een koppeling ` +
  'zonder bijhorende declaratie achterlaten. Declaraties horen bij het sjabloonbeheer.';

/** "—" voor een waarde die niet vastgesteld is; nooit 0 en nooit een lege plek. */
function show(value: string | number | null): string {
  if (value === null) {
    return '—';
  }
  if (value === '') {
    return '(uitdrukkelijk leeg)';
  }
  return String(value);
}

function bool(value: boolean): string {
  return value ? 'ja' : 'nee';
}

function dateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

/** Eén regel van het overzicht: het Nederlandse woord (met uitleg in de tooltip) en de waarde. */
function Row({ label, value }: { label: ReactNode; value: ReactNode }) {
  return (
    <div className={styles.figure}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/** Een regel met een veld uit het woordenboek `revisionField` als label. */
function FieldRow({ name, value }: { name: string; value: ReactNode }) {
  return <Row label={<Term domain="revisionField" code={name} />} value={value} />;
}

type ChildKind = 'mapping' | 'filter';

/**
 * De verwijderactie van één geërfde kindrij (E4). Bevestiging via `ConfirmDialog` zonder redenveld: het
 * endpoint bewaart geen reden, en een veld aanbieden waarvan de inhoud weggegooid wordt, zou misleiden.
 *
 * Een 409 met een `CONFIG_BOOKMARK_*`-code betekent dat een bookmarkdeclaratie van deze revisie nog op
 * deze rij steunt; er is dan niets verwijderd. Die melding blijft in de open dialoog staan.
 */
function DeleteChildRowAction({
  revisionId,
  status,
  kind,
  rowId,
  description,
  onDeleted,
}: {
  revisionId: number;
  status: RevisionDetail['status'];
  kind: ChildKind;
  rowId: number;
  description: string;
  onDeleted: () => void;
}) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, editGate(status));
  const [open, setOpen] = useState(false);
  // 204 levert geen body; `useAction` kan geslaagd en mislukt alleen onderscheiden aan `undefined`.
  // Daarom geeft deze wrapper expliciet `true` terug bij succes — anders zou een 409 er voor de UI
  // hetzelfde uitzien als een geslaagde verwijdering.
  const runner = useAction(async () => {
    if (kind === 'mapping') {
      await setupRevisionsApi.deleteMapping(revisionId, rowId);
    } else {
      await setupRevisionsApi.deleteFilter(revisionId, rowId);
    }
    return true as const;
  });

  const noun = kind === 'mapping' ? 'extra veld' : 'filter op de regels';
  const testId = `delete-${kind}-${rowId}`;

  async function handleConfirm() {
    const result = await runner.execute();
    if (result === undefined) {
      // Mislukt (bv. 409 CONFIG_BOOKMARK_PLACE_UNRESOLVED): er is niets verwijderd, dialoog blijft open.
      return;
    }
    setOpen(false);
    onDeleted();
  }

  const flagOff = runner.error !== null && isSetupApiDisabledError(runner.error);

  return (
    <>
      <button
        type="button"
        className={styles.deleteButton}
        disabled={!gate.allowed}
        title={gateTitle(gate)}
        data-testid={testId}
        onClick={() => {
          runner.reset();
          setOpen(true);
        }}
      >
        Verwijderen
      </button>
      {open && (
        <ConfirmDialog
          open
          title={`${noun.charAt(0).toUpperCase()}${noun.slice(1)} verwijderen`}
          body={
            <div className={styles.dialogBody}>
              <p>{description} wordt uit dit concept verwijderd. Andere versies blijven ongemoeid.</p>
              <p className={styles.muted}>
                Steunt een gedeclareerd {INVULPUNT} van dit concept nog op deze rij, dan weigert de server en wordt er
                niets verwijderd.
              </p>
            </div>
          }
          reasonRequirement="none"
          // Ander opschrift dan de knop die de dialoog opende, zodat de bevestiging ondubbelzinnig is.
          confirmLabel="Definitief verwijderen"
          cancelLabel="Sluiten zonder verwijderen"
          variant="danger"
          pending={runner.pending}
          error={
            runner.error === null ? undefined : flagOff ? (
              <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} className={styles.flagOff} />
            ) : (
              <ErrorBanner error={runner.error} />
            )
          }
          onConfirm={handleConfirm}
          onCancel={() => setOpen(false)}
        />
      )}
    </>
  );
}

export type RevisionDetailSectionProps = {
  definitionId: number;
  revisionId: number;
  /** Na een geslaagde wijziging/activatie: de ouder herlaadt de revisielijst van de boom. */
  onRevisionChanged: () => void;
};

export function RevisionDetailSection({
  definitionId,
  revisionId,
  onRevisionChanged,
}: RevisionDetailSectionProps) {
  const key = `revision-detail:${definitionId}:${revisionId}`;
  const detail = useQuery(key, (signal) => setupApi.getRevisionDetail(definitionId, revisionId, signal));

  function afterWrite() {
    // Expliciete invalidatie (geen cachebibliotheek): eerst het detail, dan de boom erboven.
    detail.reload();
    onRevisionChanged();
  }

  return (
    <section className={styles.section} data-testid="revision-detail">
      <h4 className={styles.title}>Detail van versie #{revisionId}</h4>

      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {detail.data !== null && (
        <RevisionBody revision={detail.data} onChanged={afterWrite} />
      )}
    </section>
  );
}

/**
 * Het geladen detail: samenvatting, dan het bewerkformulier (of de reden waarom er geen is), dan de
 * kindrijen en — op een DRAFT — de activatieknop met de R-CASE-03-waarschuwing.
 */
function RevisionBody({ revision, onChanged }: { revision: RevisionDetail; onChanged: () => void }) {
  const gate = editGate(revision.status);
  return (
    <>
      <RevisionSummary revision={revision} />

      {gate.allowed ? (
        // `key` op het revisie-id: schakelt de gebruiker naar een andere revisie, dan begint het
        // formulier met de waarden van díe revisie in plaats van met de vorige ingevulde tekst.
        <RevisionEditForm key={revision.id} revision={revision} onUpdated={onChanged} />
      ) : (
        <p className={styles.notEditable} title={gateTitle(gate)} data-testid="revision-not-editable-reason">
          {gate.reason}
        </p>
      )}

      <ChildRows revision={revision} onChanged={onChanged} />

      {revision.status === 'DRAFT' && (
        <ActivateRevisionAction revision={revision} onActivated={onChanged} />
      )}
    </>
  );
}

/** Alle scalaire velden, gegroepeerd zoals de revisie zelf: herkenning, opbouw, prijs, drempels, herkomst. */
function RevisionSummary({ revision }: { revision: RevisionDetail }) {
  return (
    <div className={styles.summary} data-testid="revision-summary">
      <p className={styles.headline}>
        Versie {revision.revisionNumber} <StatusBadge status={revision.status} domain="revisionStatus" /> van beschrijving{' '}
        {revision.definitionId}
        {revision.basedOnRevisionId !== null && <> · gebaseerd op versie #{revision.basedOnRevisionId}</>}
      </p>
      <p className={styles.changeReason}>Wijzigingsreden: {show(revision.changeReason)}</p>

      <h5 className={styles.groupTitle}>Herkenning van een artikel</h5>
      <dl className={styles.figures}>
        <FieldRow
          name="identityProfileKind"
          value={<Term domain="identityProfile" code={revision.identityProfileKind} unknownLabel="Andere herkenning" />}
        />
        <FieldRow name="supplierField" value={revision.identitySupplierField} />
        <FieldRow name="supplierGroupField" value={revision.identitySupplierGroupField} />
        <FieldRow name="supplierReferenceField" value={revision.identitySupplierReferenceField} />
        <FieldRow name="discountCodeField" value={show(revision.identityDiscountCodeField)} />
        <FieldRow name="canonicalisationVersion" value={String(revision.recordCanonicalisationVersion)} />
      </dl>

      <h5 className={styles.groupTitle}>Opbouw van het bestand</h5>
      <dl className={styles.figures}>
        <FieldRow
          name="structureFormat"
          value={<Term domain="structureFormat" code={revision.structureFormat} unknownLabel="Ander formaat" />}
        />
        <FieldRow name="charset" value={revision.structureCharset} />
        <FieldRow name="delimiter" value={revision.structureDelimiter} />
        <FieldRow name="quoteChar" value={show(revision.structureQuoteChar)} />
        <FieldRow name="hasHeader" value={bool(revision.structureHasHeader)} />
        <FieldRow name="headerLineNumber" value={String(revision.structureHeaderLineNumber)} />
        <FieldRow
          name="fieldReferenceKind"
          value={
            <Term domain="fieldReferenceKind" code={revision.structureFieldReferenceKind} unknownLabel="Andere herkenning" />
          }
        />
        <FieldRow name="expectedColumnCount" value={show(revision.structureExpectedColumnCount)} />
        <FieldRow
          name="deliverySetKind"
          value={<Term domain="deliverySetKind" code={revision.accessDeliverySetKind} unknownLabel="Andere soort" />}
        />
      </dl>

      <h5 className={styles.groupTitle}>Kolommen en prijsbeleid</h5>
      <dl className={styles.figures}>
        <FieldRow name="basePriceField" value={revision.recordBasePriceField} />
        <FieldRow name="descriptionField" value={show(revision.recordDescriptionField)} />
        <FieldRow name="currencyField" value={show(revision.recordCurrencyField)} />
        <FieldRow name="basePriceZeroAllowed" value={bool(revision.basePriceZeroAllowed)} />
        <FieldRow name="basePriceNegativeAllowed" value={bool(revision.basePriceNegativeAllowed)} />
        <FieldRow name="priceDeviationPercent" value={show(revision.priceDeviationPercent)} />
        <FieldRow
          name="priceDeviationSeverity"
          value={<Term domain="severity" code={revision.priceDeviationSeverity} unknownLabel="Andere ernst" />}
        />
        <FieldRow name="priceDerivationTolerance" value={show(revision.priceDerivationTolerance)} />
        <FieldRow name="priceAvgShortWindow" value={String(revision.priceAvgShortWindow)} />
        <FieldRow name="priceAvgLongWindow" value={String(revision.priceAvgLongWindow)} />
        <FieldRow
          name="priceControlModel"
          value={<Term domain="priceControlModel" code={revision.priceControlModel} unknownLabel="Andere manier" />}
        />
      </dl>

      <h5 className={styles.groupTitle}>Drempels</h5>
      <dl className={styles.figures}>
        <FieldRow name="creationThresholdAbsolute" value={String(revision.creationThresholdAbsolute)} />
        <FieldRow name="creationThresholdSharePercent" value={show(revision.creationThresholdSharePercent)} />
        <FieldRow name="maxCriticalSharePercent" value={show(revision.maxCriticalSharePercent)} />
        <FieldRow name="maxRejectedSharePercent" value={show(revision.maxRejectedSharePercent)} />
        <FieldRow name="bulkIncidentSharePercent" value={show(revision.bulkIncidentSharePercent)} />
      </dl>

      <h5 className={styles.groupTitle}>Herkomst en goedkeuring</h5>
      <dl className={styles.figures}>
        <Row label="Aangemaakt op" value={dateTime(revision.createdAt)} />
        <Row label="Aangemaakt door" value={show(revision.createdBy)} />
        <Row label="Laatst gewijzigd op" value={dateTime(revision.updatedAt)} />
        <Row label="Goedgekeurd op" value={dateTime(revision.approvedAt)} />
        <Row label="Goedgekeurd door" value={show(revision.approvedBy)} />
      </dl>

      <p className={styles.hashNote}>
        De technische herkomstgegevens (versienummers en vingerafdrukken) staan hieronder onder &laquo;Technische
        details&raquo;. Ze dekken alleen de basisinstellingen — niet de extra velden, filters, kritiek per veld,
        drempels of het prijsbeleid. Twee versies die enkel daarin verschillen, dragen dus dezelfde samengestelde
        vingerafdruk; gebruik hem nooit als antwoord op &laquo;zijn deze versies gelijk?&raquo;.
      </p>
      <TechnicalDetails
        items={[
          { name: 'accessVersion', value: String(revision.accessVersion) },
          { name: 'accessConfigHash', value: show(revision.accessConfigHash) },
          { name: 'structureVersion', value: String(revision.structureVersion) },
          { name: 'structureConfigHash', value: show(revision.structureConfigHash) },
          { name: 'recordRulesVersion', value: String(revision.recordRulesVersion) },
          { name: 'recordRulesConfigHash', value: show(revision.recordRulesConfigHash) },
          { name: 'compositeConfigHash', value: show(revision.compositeConfigHash) },
          { name: 'maxCriticalRecords (niet meer in gebruik)', value: String(revision.maxCriticalRecords) },
          { name: 'maxRejectedRecords (niet meer in gebruik)', value: show(revision.maxRejectedRecords) },
        ]}
      />
    </div>
  );
}

/**
 * Het woord bij een doelveld van een extra veld of een veld met aangepaste kritiek. Een code die niet in de
 * veldcatalogus van het woordenboek staat, toont "Ander veld"; de code zelf blijft in de tooltip.
 */
function FieldKeyTerm({ code }: { code: string }) {
  return <Term domain="fieldKey" code={code} unknownLabel="Ander veld" />;
}

/** Extra velden, filters, kritiek per veld, bookmarkdeclaraties en hun DEFINITION-waarden. */
function ChildRows({ revision, onChanged }: { revision: RevisionDetail; onChanged: () => void }) {
  const draft = revision.status === 'DRAFT';
  const bookmarkLabels = new Map(revision.bookmarks.map((bookmark) => [bookmark.name, bookmark.label] as const));
  return (
    <>
      <div className={styles.childBlock}>
        <h5 className={styles.groupTitle}>Extra velden ({revision.mappings.length})</h5>
        {revision.mappings.length === 0 ? (
          <p className={styles.empty}>Deze versie heeft geen extra velden.</p>
        ) : (
          <ul className={styles.list} data-testid="revision-mappings">
            {revision.mappings.map((mapping) => (
              <li key={mapping.id} className={styles.listItem}>
                <span className={styles.sequence}>#{mapping.sequenceNumber}</span>
                <span>
                  <FieldKeyTerm code={mapping.targetFieldCode} />
                </span>
                <span>
                  uit <span className={styles.code}>{mapping.sourceReference}</span>
                </span>
                <span>
                  <Term domain="mappingValueKind" code={mapping.valueKind} unknownLabel="Andere herkomst" />
                </span>
                <span>
                  <Term domain="criticality" code={mapping.criticality} unknownLabel="Andere kritiek" />
                </span>
                {draft && (
                  <DeleteChildRowAction
                    revisionId={revision.id}
                    status={revision.status}
                    kind="mapping"
                    rowId={mapping.id}
                    description={`Extra veld #${mapping.sequenceNumber} naar "${termLabel('fieldKey', mapping.targetFieldCode, 'een ander veld')}"`}
                    onDeleted={onChanged}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className={styles.childBlock}>
        <h5 className={styles.groupTitle}>Filters op de regels ({revision.filters.length})</h5>
        {revision.filters.length === 0 ? (
          <p className={styles.empty}>Deze versie heeft geen filters op de regels.</p>
        ) : (
          <ul className={styles.list} data-testid="revision-filters">
            {revision.filters.map((filter) => (
              <li key={filter.id} className={styles.listItem}>
                <span className={styles.sequence}>#{filter.sequenceNumber}</span>
                <span className={styles.code}>{filter.sourceReference}</span>
                <span>
                  <Term domain="filterOperator" code={filter.operator} unknownLabel="andere vergelijking" />
                </span>
                <span>{show(filter.compareValue)}</span>
                <span>
                  <Term domain="filterOutcome" code={filter.outcome} unknownLabel="Andere uitkomst" />
                </span>
                {draft && (
                  <DeleteChildRowAction
                    revisionId={revision.id}
                    status={revision.status}
                    kind="filter"
                    rowId={filter.id}
                    description={`Filter #${filter.sequenceNumber} op kolom ${filter.sourceReference}`}
                    onDeleted={onChanged}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className={styles.childBlock}>
        <h5 className={styles.groupTitle}>Kritiek per veld aangepast ({revision.fieldCriticalities.length})</h5>
        {revision.fieldCriticalities.length === 0 ? (
          <p className={styles.empty}>Voor deze versie is de kritiek van geen enkel veld aangepast.</p>
        ) : (
          <ul className={styles.list} data-testid="revision-field-criticalities">
            {revision.fieldCriticalities.map((row) => (
              <li key={row.fieldKey} className={styles.listItem}>
                <span>
                  <FieldKeyTerm code={row.fieldKey} />
                </span>
                <span>
                  <Term domain="criticality" code={row.criticality} unknownLabel="Andere kritiek" />
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className={styles.childBlock}>
        <h5 className={styles.groupTitle}>Gedeclareerde {INVULPUNTEN} ({revision.bookmarks.length})</h5>
        <p className={styles.boundary} data-testid="bookmark-boundary-note">
          {BOOKMARK_BOUNDARY_TEXT}
        </p>
        {revision.bookmarks.length === 0 ? (
          <p className={styles.empty}>Deze versie declareert geen {INVULPUNTEN}.</p>
        ) : (
          <ul className={styles.list} data-testid="revision-bookmarks">
            {revision.bookmarks.map((bookmark) => (
              <li key={bookmark.id} className={styles.listItem}>
                {/* De technische naam staat in de tooltip (V7), niet als zichtbare tekst. */}
                <span className={styles.code} title={`Technische naam: ${bookmark.name}`}>
                  {bookmark.label}
                </span>
                <span>
                  <Term domain="bookmarkDataType" code={bookmark.dataType} unknownLabel="Ander soort waarde" /> ·{' '}
                  <Term domain="bookmarkScope" code={bookmark.valueScope} unknownLabel="Andere geldigheid" />
                </span>
                <span>{bookmark.required ? 'verplicht' : 'optioneel'}</span>
              </li>
            ))}
          </ul>
        )}
        {revision.bookmarkValues.length > 0 && (
          <ul className={styles.list} data-testid="revision-bookmark-values">
            {revision.bookmarkValues.map((value) => (
              <li key={value.bookmarkName} className={styles.listItem}>
                <span className={styles.code} title={`Technische naam: ${value.bookmarkName}`}>
                  {bookmarkLabels.get(value.bookmarkName) ?? `${INVULPUNT_CAP} zonder declaratie`}
                </span>
                <span>{show(value.valueText)}</span>
                <span>
                  <Term domain="bookmarkDataType" code={value.dataType} unknownLabel="Ander soort waarde" />
                </span>
                <span>
                  ingevuld door {show(value.filledBy)} op {dateTime(value.filledAt)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
