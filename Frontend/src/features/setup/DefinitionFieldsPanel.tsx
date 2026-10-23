/**
 * Het blok "Bestand en velden" van één importdefinitie: de gekozen versie (de actieve, anders het nieuwste concept) staat
 * meteen leesbaar op het scherm als Velden-grid, met een keuzelijst voor een andere versie en een conceptmelding.
 * Gedeeld door het leveranciersdetail en het importdefinitiedetail (R2); de aanroeper bepaalt hoe een andere versie
 * gekozen wordt (adresbalk) en wat er na een wijziging herlaadt.
 *
 * E1 (beslissingslog 2026-10-23 "Nieuwe bewerkervaring"): dezelfde lay-out om te lezen en te bewerken. Alleen op een
 * concept staat in de kopstrook de schakelaar Bekijken|Bewerken (standaard Bekijken; `?bewerken=1` zet Bewerken aan). In
 * Bewerken heeft elke rij van Herkenning en Basis een potlood dat een zijpaneel opent (`FieldPanel`, direct bewaren met
 * een PATCH). Een actieve of vervangen versie is alleen-lezen, met "Opvolger maken" (of "Open het concept" als er al een
 * concept is). Extra velden, filters en activeren blijven tot E2/E3/E4 bereikbaar via "Meer opties" (de bestaande
 * `RevisionDetailSection`).
 */

import { useEffect, useState, type ReactNode } from 'react';
import { usePermissionGate } from '../../actor/permissions.ts';
import * as setupApi from '../../api/setup.ts';
import { PERMISSION_MANAGE, type DefinitionRow, type RevisionDetail, type RevisionRow } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { term } from '../../terms/index.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { EDITOR_TEXT } from '../../terms/setupEditor.ts';
import { CreateSuccessorAction } from './CreateSuccessorAction.tsx';
import { FieldPanel, type PanelTarget } from './FieldPanel.tsx';
import { RevisionDetailSection } from './RevisionDetailSection.tsx';
import { RevisionReadView } from './RevisionReadView.tsx';
import { defaultRevisionOf } from './setupDashboardModel.ts';
import fieldsEditor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export type DefinitionFieldsPanelProps = {
  definition: DefinitionRow;
  revisions: readonly RevisionRow[];
  /** De gevraagde versie uit de adresbalk (anders de actieve, anders het nieuwste concept). */
  requestedRevisionId: number | null;
  onSelectRevision: (revisionId: number) => void;
  /** `true` als het adres vraagt om Bewerken meteen aan te zetten (`bewerken=1`; enkel op een concept). */
  editRequested: boolean;
  /** Verandert bij elke adreswijziging, zodat een adres met `bewerken=1` Bewerken ook aanzet als het tabblad al open stond. */
  addressKey: string;
  /** Na een wijziging: de ouder herlaadt de versielijst. */
  onChanged: () => void;
  /** Extra inhoud onder het leesbeeld (bv. de invulpunten van een herbruikbare importdefinitie). */
  extra?: (revision: RevisionRow) => ReactNode;
};

export function DefinitionFieldsPanel({
  definition,
  revisions,
  requestedRevisionId,
  onSelectRevision,
  editRequested,
  addressKey,
  onChanged,
  extra,
}: DefinitionFieldsPanelProps) {
  const [editing, setEditing] = useState(editRequested);
  useEffect(() => {
    if (editRequested) {
      setEditing(true);
    }
  }, [editRequested, addressKey]);

  const revision = defaultRevisionOf(revisions, requestedRevisionId);
  if (revision === null) {
    return <p className={styles.empty}>{TEXT.noVersions}</p>;
  }

  return (
    <section className={`${pageCss.card} ${pageCss.cardPad}`} aria-labelledby="fields-heading">
      <h2 id="fields-heading" className={pageCss.subtitle} tabIndex={-1}>
        {TEXT.headingFor(
          definition.name,
          revision.revisionNumber,
          term('revisionStatus', revision.status).label.toLowerCase(),
        )}
      </h2>
      <div className={styles.vdHead}>
        <label className={styles.selectLabel}>
          {TEXT.versionSelectLabel}
          <select
            value={revision.id}
            onChange={(event) => {
              setEditing(false);
              onSelectRevision(Number(event.target.value));
            }}
            data-testid="version-select"
          >
            {[...revisions]
              .sort((a, b) => b.revisionNumber - a.revisionNumber)
              .map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {TEXT.versionOption(candidate.revisionNumber, term('revisionStatus', candidate.status).label.toLowerCase())}
                </option>
              ))}
          </select>
        </label>
      </div>
      {revision.status === 'DRAFT' && (
        <div className={styles.banner} role="status" data-testid="draft-banner">
          <p>{TEXT.draftBanner}</p>
        </div>
      )}
      <RevisionFields
        // Een andere versie begint met een leeg paneel en een schone melding.
        key={revision.id}
        definition={definition}
        revisions={revisions}
        revision={revision}
        editing={editing}
        onEditingChange={setEditing}
        onSelectRevision={onSelectRevision}
        onChanged={onChanged}
        extra={extra}
      />
    </section>
  );
}

type RevisionFieldsProps = {
  definition: DefinitionRow;
  revisions: readonly RevisionRow[];
  revision: RevisionRow;
  editing: boolean;
  onEditingChange: (editing: boolean) => void;
  onSelectRevision: (revisionId: number) => void;
  onChanged: () => void;
  extra?: (revision: RevisionRow) => ReactNode;
};

/** De kopstrook, het grid (lezen en bewerken), het zijpaneel en de "Meer opties" van één gekozen versie. */
function RevisionFields({
  definition,
  revisions,
  revision,
  editing,
  onEditingChange,
  onSelectRevision,
  onChanged,
  extra,
}: RevisionFieldsProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const isDraft = revision.status === 'DRAFT';
  const editingNow = editing && isDraft && manageGate.allowed;
  const [moreOpen, setMoreOpen] = useState(false);
  const [panel, setPanel] = useState<{ target: PanelTarget; opener: HTMLElement } | null>(null);
  const [saved, setSaved] = useState<string[] | null>(null);

  const detail = useQuery(`supplier-revision-read:${definition.id}:${revision.id}`, (signal) =>
    setupApi.getRevisionDetail(definition.id, revision.id, signal),
  );
  // De legacy-herkomst laadt apart en lui: een fout of een trage herkomst blokkeert het leesbeeld nooit.
  const origin = useQuery(`supplier-revision-legacy-origin:${definition.id}:${revision.id}`, (signal) =>
    setupApi.getRevisionLegacyOrigin(definition.id, revision.id, signal),
  );
  const originRevisionId = origin.data?.originRevisionId ?? null;
  const originVersionNumber =
    originRevisionId === null ? null : (revisions.find((candidate) => candidate.id === originRevisionId)?.revisionNumber ?? null);

  const draftId =
    definition.draftRevisionId ?? revisions.find((candidate) => candidate.status === 'DRAFT')?.id ?? null;
  const draftNumber =
    definition.draftRevisionNumber ?? revisions.find((candidate) => candidate.id === draftId)?.revisionNumber ?? null;

  function afterChange() {
    detail.reload();
    origin.reload();
    onChanged();
  }

  function setMode(next: boolean) {
    setSaved(null);
    setPanel(null);
    onEditingChange(next);
  }

  const detailData: RevisionDetail | null = detail.data;

  return (
    <>
      <div className={fieldsEditor.headStrip} data-testid="mode-strip">
        {isDraft ? (
          <div className={fieldsEditor.segment} role="group" aria-label={EDITOR_TEXT.switchLabel}>
            <button type="button" aria-pressed={!editingNow} data-testid="mode-view" onClick={() => setMode(false)}>
              {EDITOR_TEXT.view}
            </button>
            <button
              type="button"
              aria-pressed={editingNow}
              disabled={!manageGate.allowed}
              title={manageGate.allowed ? undefined : manageGate.reason}
              data-testid="mode-edit"
              onClick={() => setMode(true)}
            >
              {EDITOR_TEXT.edit}
            </button>
          </div>
        ) : (
          <>
            <p className={fieldsEditor.frozen} data-testid="frozen-note">
              {EDITOR_TEXT.frozen(
                revision.status === 'ACTIVE' ? 'ACTIVE' : revision.status === 'SUPERSEDED' ? 'SUPERSEDED' : 'OTHER',
              )}
            </p>
            {(revision.status === 'ACTIVE' || revision.status === 'SUPERSEDED') &&
              (draftId !== null ? (
                <button
                  type="button"
                  className={`${button.secondary} ${button.small}`}
                  data-testid="open-draft"
                  onClick={() => onSelectRevision(draftId)}
                >
                  {EDITOR_TEXT.openDraft(draftNumber)}
                </button>
              ) : (
                <CreateSuccessorAction
                  revision={revision}
                  onCreated={(created) => {
                    onChanged();
                    onSelectRevision(created.id);
                  }}
                />
              ))}
          </>
        )}
      </div>
      {saved !== null && (
        <div className={fieldsEditor.toast} role="status" data-testid="save-toast">
          <p>{EDITOR_TEXT.saved}</p>
          {saved.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      )}

      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detailData === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {detailData !== null && (
        <RevisionReadView
          revision={detailData}
          legacyOrigin={origin.data}
          legacyFailed={origin.error !== null}
          legacyOriginVersionNumber={originVersionNumber}
          editing={editingNow}
          onEditRow={(key, opener) => {
            setSaved(null);
            setPanel({ target: { kind: 'row', key }, opener });
          }}
          onEditUppercase={(opener) => {
            setSaved(null);
            setPanel({ target: { kind: 'uppercase' }, opener });
          }}
        />
      )}
      {extra?.(revision)}

      {panel !== null && detailData !== null && editingNow && (
        <FieldPanel
          key={panel.target.kind === 'row' ? panel.target.key : panel.target.kind}
          revision={detailData}
          target={panel.target}
          sourceOrganisationCode={definition.sourceOrganisationCode}
          opener={panel.opener}
          onClose={() => setPanel(null)}
          onSaved={(result) => {
            setPanel(null);
            setSaved((result.warnings ?? []).map((warning) => warning.message));
            afterChange();
          }}
        />
      )}

      <div className={styles.editBlock}>
        <div className={styles.btnRow}>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            aria-expanded={moreOpen}
            aria-controls="supplier-edit-block"
            data-testid="open-edit"
            onClick={() => setMoreOpen((open) => !open)}
          >
            {EDITOR_TEXT.moreOptions}
          </button>
          <span className={styles.rowMeta}>{EDITOR_TEXT.moreOptionsHint}</span>
        </div>
        {moreOpen && (
          <div id="supplier-edit-block">
            <RevisionDetailSection
              definitionId={definition.id}
              sourceOrganisationCode={definition.sourceOrganisationCode}
              revisionId={revision.id}
              onRevisionChanged={afterChange}
            />
          </div>
        )}
      </div>
    </>
  );
}
