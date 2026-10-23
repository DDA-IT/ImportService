/**
 * G2c-3 — het extra veld (mapping) van een DRAFT-revisie vervangen (`PUT /setup/revisions/{rid}/mappings/{mid}`,
 * `docs/design/g2-design.md` §G2c). De dialoog opent vooraf ingevuld met de huidige mapping.
 *
 * De bewerking is ofwel één soort, ofwel een keten met drie vaste vakken die de gesloten grammatica afdwingen:
 * [Uitknippen] → [Voor- of achtervoegsel] → [één waardebewerking]. Bij omzetten (MAP en MAP_CONTAINS) staat er een
 * tabeleditor met een plakvak (tab of `;` per regel, voorbeeld vóór overnemen, geen bestandsupload). De client controleert
 * enkel de vorm (`mappingReplaceModel.ts`); of de keten en de configuratie kloppen beslist de server, en die fout staat
 * als `ErrorBanner` in de open dialoog. Na succes herlaadt de ouder het detail: de mapping heeft een **nieuw id**.
 */

import { useId, useState, type ReactNode } from 'react';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { PERMISSION_MANAGE, type CreateMappingRequest, type MappingView, type RevisionDetail } from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { gateTitle } from '../../actor/gate.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import {
  CHAIN,
  CHAIN_STAGE_KINDS,
  KIND_KEYS,
  ON_MISS_KEEP_SOURCE,
  ON_MISS_REJECT,
  SINGLE_KINDS,
  buildMappingRequest,
  emptyStage,
  initialMappingForm,
  mappingProblems,
  parsePaste,
  tableKind,
  type MappingForm,
  type Stage,
  type TableRow,
} from './mappingReplaceModel.ts';
import { editGate } from './revisionPolicy.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import button from '../../components/Button.module.css';
import styles from './MappingReplaceDialog.module.css';

/** `single` of het nummer (0-2) van het ketenvak; ook de voorloper van de testids. */
type StageId = 'single' | 0 | 1 | 2;

const STAGE_TITLES = ['1. Uitknippen', '2. Voor- of achtervoegsel', '3. Waardebewerking'];

/** Het label bij elke instelling; de sleutels zijn die van `KIND_KEYS`. */
const SETTING_LABELS: Record<string, string> = {
  value: 'Waarde die de bewerking geeft',
  prefix: 'Voorvoegsel',
  suffix: 'Achtervoegsel',
  sources: 'Kolommen om samen te voegen (één per regel)',
  separator: 'Scheidingsteken',
  index: 'Welk deel bewaard blijft (1 = het eerste)',
  start: 'Vanaf positie (1 = het begin)',
  length: 'Aantal tekens (leeg = de rest)',
  operand: 'Getal',
  operandField: 'Of: een kolom',
  base: 'Basis (getal)',
  baseField: 'Of: een basiskolom',
};

const SETTING_HINTS: Record<string, string> = {
  prefix: 'Spaties aan het begin of einde blijven niet bewaard.',
  suffix: 'Spaties aan het begin of einde blijven niet bewaard.',
  operand: 'Vul het getal of de kolom in, niet allebei.',
  base: 'Vul de basis of de basiskolom in, niet allebei.',
};

function kindLabel(kind: string): string {
  return termLabel('transformKind', kind, 'andere bewerking');
}

export type ReplaceMappingActionProps = {
  revision: RevisionDetail;
  mapping: MappingView;
  /** Na een geslaagde vervanging: de ouder herlaadt het detail (de mapping heeft een nieuw id). */
  onReplaced: () => void;
};

/** De knop "Wijzigen…" bij een extra veld, met de dialoog. Enkel MANAGE en enkel op een concept (`editGate`). */
export function ReplaceMappingAction({ revision, mapping, onReplaced }: ReplaceMappingActionProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, editGate(revision.status));
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className={`${button.secondary} ${button.small}`}
        disabled={!gate.allowed}
        title={gateTitle(gate)}
        data-testid={`replace-mapping-${mapping.id}`}
        onClick={() => setOpen(true)}
      >
        Wijzigen…
      </button>
      {open && (
        <MappingReplaceDialog
          revisionId={revision.id}
          mapping={mapping}
          onClose={() => setOpen(false)}
          onReplaced={() => {
            setOpen(false);
            onReplaced();
          }}
        />
      )}
    </>
  );
}

type DialogProps = {
  revisionId: number;
  mapping: MappingView;
  onClose: () => void;
  onReplaced: () => void;
};

/** De dialoog zelf; wordt enkel gemonteerd als hij open is, zodat elke opening met de huidige mapping begint. */
export function MappingReplaceDialog({ revisionId, mapping, onClose, onReplaced }: DialogProps) {
  const { actor } = useActor();
  const uid = useId();
  const [{ form: initial, unsupported }] = useState(() => initialMappingForm(mapping));
  const [form, setForm] = useState<MappingForm>(initial);
  const runner = useAction((body: CreateMappingRequest) =>
    setupRevisionsApi.replaceMapping(revisionId, mapping.id, body),
  );

  const problems = unsupported === null ? mappingProblems(form, mapping, kindLabel) : [unsupported];
  const table = tableKind(form);

  function change(patch: Partial<MappingForm>) {
    setForm((previous) => ({ ...previous, ...patch }));
    runner.reset();
  }

  function stageOf(id: StageId): Stage {
    return id === 'single' ? form.single : form.stages[id];
  }

  function changeStage(id: StageId, stage: Stage) {
    if (id === 'single') {
      change({ single: stage });
    } else {
      const stages: [Stage, Stage, Stage] = [form.stages[0], form.stages[1], form.stages[2]];
      stages[id] = stage;
      change({ stages });
    }
  }

  function setKind(id: StageId, kind: string) {
    if (kind === CHAIN) {
      // Van één bewerking naar een keten: de gekozen bewerking schuift naar haar vak; wat niet past, vervalt zichtbaar.
      const stages: [Stage, Stage, Stage] = [emptyStage(), emptyStage(), emptyStage()];
      const at = CHAIN_STAGE_KINDS.findIndex((kinds) => kinds.includes(form.single.kind));
      if (at >= 0) {
        stages[at] = { ...form.single, extra: '' };
      }
      // De notatie-instellingen van de mapping horen bij een keten op de mapping zelf.
      change({ chain: true, stages, mappingExtra: form.single.extra });
      return;
    }
    const current = stageOf(id);
    // Een andere bewerking begint met lege instellingen; onbekende instellingen van de mapping blijven staan.
    changeStage(id, { kind, settings: {}, extra: id === 'single' ? current.extra : '' });
  }

  async function handleConfirm() {
    const built = buildMappingRequest(form, mapping, kindLabel, actor);
    if ('problems' in built) {
      return;
    }
    const replaced = await runner.execute(built.body);
    if (replaced === undefined) {
      // Mislukt (400/409): er is niets gewijzigd, de dialoog blijft open met de melding van de server.
      return;
    }
    onReplaced();
  }

  const flagOff = runner.error !== null && isSetupApiDisabledError(runner.error);
  const fieldId = (name: string) => `${uid}-${name}`;

  return (
    <ConfirmDialog
      open
      title={`Extra veld #${mapping.sequenceNumber} wijzigen`}
      body={
        <div className={styles.body} data-testid="mapping-replace-dialog">
          <p>
            Doelveld: <strong>{termLabel('fieldKey', mapping.targetFieldCode, 'een ander veld')}</strong>. Het extra veld
            wordt vervangen door de nieuwe instellingen, op dezelfde plaats in de lijst. De server controleert of alles
            samen klopt.
          </p>

          <Field label="Kolom in het bronbestand" htmlFor={fieldId('source')} required={mapping.valueKind === 'SOURCE_FIELD'}>
            <input
              id={fieldId('source')}
              data-testid="mapping-source"
              value={form.sourceReference}
              onChange={(event) => change({ sourceReference: event.target.value })}
            />
          </Field>
          <Field label="Positie in het bestand (optioneel)" htmlFor={fieldId('position')}>
            <input
              id={fieldId('position')}
              data-testid="mapping-position"
              inputMode="numeric"
              value={form.expectedPosition}
              onChange={(event) => change({ expectedPosition: event.target.value })}
            />
          </Field>
          <Field label="Vaste waarde van het veld" htmlFor={fieldId('fixed')} required={mapping.valueKind === 'FIXED_VALUE'}>
            <input
              id={fieldId('fixed')}
              data-testid="mapping-fixed"
              value={form.fixedValue}
              onChange={(event) => change({ fixedValue: event.target.value })}
            />
          </Field>
          <Field label="Standaardwaarde (als de kolom leeg is)" htmlFor={fieldId('default')}>
            <input
              id={fieldId('default')}
              data-testid="mapping-default"
              value={form.defaultValue}
              onChange={(event) => change({ defaultValue: event.target.value })}
            />
          </Field>
          <Field label="Maximale lengte (optioneel)" htmlFor={fieldId('maxlen')}>
            <input
              id={fieldId('maxlen')}
              data-testid="mapping-max-length"
              inputMode="numeric"
              value={form.maxLength}
              onChange={(event) => change({ maxLength: event.target.value })}
            />
          </Field>
          <Field label="Aantal decimalen (optioneel)" htmlFor={fieldId('scale')}>
            <input
              id={fieldId('scale')}
              data-testid="mapping-decimal-scale"
              inputMode="numeric"
              value={form.decimalScale}
              onChange={(event) => change({ decimalScale: event.target.value })}
            />
          </Field>
          <Check
            label="Verplicht"
            checked={form.required}
            testId="mapping-required"
            onChange={(checked) => change({ required: checked })}
          />
          <Check
            label="Nul is toegelaten"
            checked={form.zeroAllowed}
            testId="mapping-zero-allowed"
            onChange={(checked) => change({ zeroAllowed: checked })}
          />
          <Check
            label="Negatief is toegelaten"
            checked={form.negativeAllowed}
            testId="mapping-negative-allowed"
            onChange={(checked) => change({ negativeAllowed: checked })}
          />

          <h3 className={styles.section}>Bewerking op de waarde</h3>
          <Field label="Soort bewerking" htmlFor={fieldId('kind')}>
            <select
              id={fieldId('kind')}
              data-testid="mapping-kind"
              value={form.chain ? CHAIN : form.single.kind}
              onChange={(event) => {
                if (event.target.value !== CHAIN && form.chain) {
                  change({
                    chain: false,
                    single: { ...emptyStage(event.target.value), extra: form.mappingExtra },
                    mappingExtra: '',
                  });
                } else {
                  setKind('single', event.target.value);
                }
              }}
            >
              {SINGLE_KINDS.map((kind) => (
                <option key={kind} value={kind}>
                  {kindLabel(kind)}
                </option>
              ))}
              <option value={CHAIN}>{kindLabel(CHAIN)}</option>
            </select>
          </Field>

          {!form.chain && (
            <StageFields
              id="single"
              stage={form.single}
              uid={uid}
              onChange={(stage) => changeStage('single', stage)}
            />
          )}

          {form.chain && (
            <>
              <p className={styles.muted}>
                De bewerkingen lopen altijd in deze volgorde; elk vak is optioneel, maar minstens één is nodig.
              </p>
              {([0, 1, 2] as const).map((index) => (
                <div key={index} className={styles.stage} data-testid={`mapping-stage-${index}`}>
                  <p className={styles.stageTitle}>{STAGE_TITLES[index]}</p>
                  <Field label="Bewerking" htmlFor={fieldId(`stage-${index}-kind`)}>
                    <select
                      id={fieldId(`stage-${index}-kind`)}
                      data-testid={`stage-${index}-kind`}
                      value={form.stages[index].kind}
                      onChange={(event) => setKind(index, event.target.value)}
                    >
                      <option value="">(geen)</option>
                      {(CHAIN_STAGE_KINDS[index] ?? []).map((kind) => (
                        <option key={kind} value={kind}>
                          {kindLabel(kind)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <StageFields
                    id={index}
                    stage={form.stages[index]}
                    uid={uid}
                    onChange={(stage) => changeStage(index, stage)}
                  />
                </div>
              ))}
              {form.mappingExtra !== '' && (
                <Field
                  label="Overige instellingen van het veld (notatie)"
                  htmlFor={fieldId('mapping-extra')}
                  hint="Deze instellingen blijven ongewijzigd behouden; pas ze enkel aan als u weet wat ze doen."
                >
                  <input
                    id={fieldId('mapping-extra')}
                    data-testid="mapping-extra"
                    value={form.mappingExtra}
                    onChange={(event) => change({ mappingExtra: event.target.value })}
                  />
                </Field>
              )}
            </>
          )}

          {table !== null && (
            <TableEditor
              kind={table}
              rows={form.rows}
              onChange={(rows) => change({ rows })}
              uid={uid}
            />
          )}

          {problems.length > 0 && (
            <ul className={styles.problems} data-testid="mapping-problems">
              {problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          )}
        </div>
      }
      reasonRequirement="none"
      confirmLabel="Extra veld vervangen"
      cancelLabel="Sluiten zonder wijzigen"
      pending={runner.pending}
      confirmBlockedReason={problems.length > 0 ? 'Los eerst de meldingen hierboven op.' : null}
      error={
        runner.error === null ? undefined : flagOff ? (
          <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} className={styles.flagOff} />
        ) : (
          <ErrorBanner error={runner.error} />
        )
      }
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}

function Check({
  label,
  checked,
  testId,
  onChange,
}: {
  label: string;
  checked: boolean;
  testId: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className={styles.check}>
      <input type="checkbox" data-testid={testId} checked={checked} onChange={(event) => onChange(event.target.checked)} />
      {label}
    </label>
  );
}

/** De instellingen van één bewerking; de vaste tabel `KIND_KEYS` bepaalt welke velden er staan. */
function StageFields({
  id,
  stage,
  uid,
  onChange,
}: {
  id: StageId;
  stage: Stage;
  uid: string;
  onChange: (stage: Stage) => void;
}) {
  const keys = KIND_KEYS[stage.kind] ?? [];
  const set = (key: string, value: string) => onChange({ ...stage, settings: { ...stage.settings, [key]: value } });
  const fields: ReactNode[] = [];
  for (const key of keys) {
    const inputId = `${uid}-stage-${id}-${key}`;
    const testId = `stage-${id}-${key}`;
    const value = stage.settings[key] ?? '';
    if (key === 'caseSensitive') {
      fields.push(
        <Check
          key={key}
          label="Hoofdlettergevoelig"
          checked={value === 'true'}
          testId={testId}
          onChange={(checked) => set(key, checked ? 'true' : 'false')}
        />,
      );
    } else if (key === 'onMiss') {
      fields.push(
        <Field key={key} label="Bij een waarde die niet in de tabel staat" htmlFor={inputId}>
          <select
            id={inputId}
            data-testid={testId}
            value={value === ON_MISS_KEEP_SOURCE ? ON_MISS_KEEP_SOURCE : ON_MISS_REJECT}
            onChange={(event) => set(key, event.target.value)}
          >
            <option value={ON_MISS_REJECT}>De regel afkeuren</option>
            <option value={ON_MISS_KEEP_SOURCE}>De waarde ongewijzigd doorlaten (met een melding)</option>
          </select>
        </Field>,
      );
    } else if (key === 'sources') {
      fields.push(
        <Field key={key} label={SETTING_LABELS[key] ?? key} htmlFor={inputId}>
          <textarea id={inputId} data-testid={testId} value={value} onChange={(event) => set(key, event.target.value)} />
        </Field>,
      );
    } else {
      fields.push(
        <Field key={key} label={SETTING_LABELS[key] ?? key} htmlFor={inputId} hint={SETTING_HINTS[key]}>
          <input id={inputId} data-testid={testId} value={value} onChange={(event) => set(key, event.target.value)} />
        </Field>,
      );
    }
  }
  if (stage.extra !== '') {
    fields.push(
      <Field
        key="extra"
        label="Overige instellingen van deze bewerking"
        htmlFor={`${uid}-stage-${id}-extra`}
        hint="Onbekende instellingen blijven ongewijzigd behouden; de server weigert ze als ze bij deze bewerking niet horen."
      >
        <input
          id={`${uid}-stage-${id}-extra`}
          data-testid={`stage-${id}-extra`}
          value={stage.extra}
          onChange={(event) => onChange({ ...stage, extra: event.target.value })}
        />
      </Field>,
    );
  }
  return <>{fields}</>;
}

/** De vertaaltabel (bron → nieuwe waarde) met een plakvak: tab of `;` per regel, eerst een voorbeeld. */
export function TableEditor({
  kind,
  rows,
  onChange,
  uid,
}: {
  kind: 'MAP' | 'MAP_CONTAINS';
  rows: TableRow[];
  onChange: (rows: TableRow[]) => void;
  uid: string;
}) {
  const [pasted, setPasted] = useState('');
  const preview = parsePaste(
    pasted,
    rows.map((row) => row.source),
  );
  const hasText = pasted.trim() !== '';

  function setRow(index: number, patch: Partial<TableRow>) {
    onChange(rows.map((row, at) => (at === index ? { ...row, ...patch } : row)));
  }

  return (
    <div className={styles.table} data-testid="value-map-editor">
      <h3 className={styles.section}>
        {kind === 'MAP' ? 'Omzettingstabel' : 'Omzettingstabel (op een deel van de waarde)'}
      </h3>
      <p className={styles.muted}>
        {kind === 'MAP'
          ? 'Elke bronwaarde krijgt de nieuwe waarde ernaast; een lege nieuwe waarde zet om naar leeg.'
          : 'De eerste rij waarvan de bronwaarde in de waarde voorkomt, bepaalt de nieuwe waarde; de volgorde telt.'}
      </p>
      {rows.length > 0 && (
        <div className={`${styles.tableRow} ${styles.tableHead}`}>
          <span>Bronwaarde</span>
          <span>Nieuwe waarde</span>
          <span />
        </div>
      )}
      {rows.map((row, index) => (
        // De volgorde is betekenisvol (MAP_CONTAINS) en rijen hebben geen eigen sleutel: de index is hier de identiteit.
        <div key={index} className={styles.tableRow} data-testid={`value-map-row-${index}`}>
          <input
            className={styles.cell}
            aria-label={`Bronwaarde rij ${index + 1}`}
            data-testid={`value-map-source-${index}`}
            value={row.source}
            onChange={(event) => setRow(index, { source: event.target.value })}
          />
          <input
            className={styles.cell}
            aria-label={`Nieuwe waarde rij ${index + 1}`}
            data-testid={`value-map-target-${index}`}
            value={row.target}
            onChange={(event) => setRow(index, { target: event.target.value })}
          />
          <button
            type="button"
            className={`${button.dangerOutlined} ${button.small}`}
            data-testid={`value-map-remove-${index}`}
            onClick={() => onChange(rows.filter((_, at) => at !== index))}
          >
            Verwijderen
          </button>
        </div>
      ))}
      <div className={styles.tableActions}>
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          data-testid="value-map-add"
          onClick={() => onChange([...rows, { source: '', target: '' }])}
        >
          Rij toevoegen
        </button>
      </div>

      <div className={styles.paste}>
        <Field
          label="Regels plakken"
          htmlFor={`${uid}-paste`}
          hint="Eén regel per rij, twee kolommen: bronwaarde en nieuwe waarde, gescheiden door een tab (uit een spreadsheet) of een puntkomma."
        >
          <textarea
            id={`${uid}-paste`}
            data-testid="value-map-paste"
            value={pasted}
            onChange={(event) => setPasted(event.target.value)}
          />
        </Field>
        {hasText && (
          <div data-testid="value-map-preview">
            <p>
              Voorbeeld: {preview.rows.length} {preview.rows.length === 1 ? 'rij' : 'rijen'} klaar om toe te voegen
              {preview.errors.length > 0 ? `, ${preview.errors.length} met een fout` : ''}.
            </p>
            {preview.rows.length > 0 && (
              <ul className={styles.previewList}>
                {preview.rows.map((row, index) => (
                  <li key={index}>
                    {row.source} → {row.target === '' ? '(leeg)' : row.target}
                  </li>
                ))}
              </ul>
            )}
            {preview.errors.length > 0 && (
              <ul className={`${styles.previewList} ${styles.previewError}`} data-testid="value-map-preview-errors">
                {preview.errors.map((error) => (
                  <li key={error}>{error}</li>
                ))}
              </ul>
            )}
            {preview.errors.length > 0 && (
              <p className={styles.muted}>Er wordt niets toegevoegd zolang er fouten zijn; pas de tekst aan.</p>
            )}
          </div>
        )}
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          data-testid="value-map-apply"
          disabled={!hasText || preview.errors.length > 0 || preview.rows.length === 0}
          onClick={() => {
            onChange([...rows, ...preview.rows]);
            setPasted('');
          }}
        >
          Toevoegen aan de tabel
        </button>
      </div>
    </div>
  );
}
