/**
 * Tab "Bestand en velden" van het leveranciersdetail: de actieve versie (of anders het nieuwste concept, met een
 * melding) van de importdefinitie van de leverancier staat meteen leesbaar op het scherm, zonder klik. Een andere
 * versie kies je met de keuzelijst of via `?versie=`; hebben er meerdere importdefinities, dan staat er een keuzelijst
 * voor de importdefinitie (`?definitie=`).
 *
 * Het blok zelf (leesbeeld, versiekeuze, "Bewerken") is `DefinitionFieldsPanel`, gedeeld met het
 * importdefinitiedetail (R2). Een opvolger maken staat bij het tabblad Versies. `?bewerken=1` opent het bewerkblok meteen.
 */

import { useSearchParams } from 'react-router-dom';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { DefinitionFieldsPanel } from './DefinitionFieldsPanel.tsx';
import { isOwnDefinition } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { parseId } from './supplierRoutes.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export function SupplierFieldsTab() {
  const { bundle, reload } = useSupplierDetailContext();
  const [params, setParams] = useSearchParams();

  const definitions = [...bundle.supplier.definitions].sort(
    (a, b) => Number(isOwnDefinition(b.definition)) - Number(isOwnDefinition(a.definition)),
  );
  if (definitions.length === 0) {
    return <p className={styles.empty}>{TEXT.noDefinition}</p>;
  }

  const requestedDefinition = parseId(params.get('definitie'));
  const entry = definitions.find((candidate) => candidate.definition.id === requestedDefinition) ?? definitions[0]!;
  const definition = entry.definition;
  const revisions = bundle.revisions[definition.id] ?? [];

  function select(next: { definitionId: number; revisionId?: number }) {
    const nextParams = new URLSearchParams();
    nextParams.set('definitie', String(next.definitionId));
    if (next.revisionId !== undefined) {
      nextParams.set('versie', String(next.revisionId));
    }
    setParams(nextParams, { replace: true });
  }

  return (
    <div data-testid="supplier-fields">
      {definitions.length > 1 && (
        <p>
          <label className={styles.selectLabel}>
            {TEXT.definitionSelectLabel}
            <select
              value={definition.id}
              onChange={(event) => select({ definitionId: Number(event.target.value) })}
              data-testid="definition-select"
            >
              {definitions.map((candidate) => (
                <option key={candidate.definition.id} value={candidate.definition.id}>
                  {candidate.definition.name}
                </option>
              ))}
            </select>
          </label>
        </p>
      )}
      {!isOwnDefinition(definition) && <p className={styles.rowMeta}>{TEXT.reusableNote}</p>}

      <DefinitionFieldsPanel
        // Een andere importdefinitie begint met een gesloten bewerkblok.
        key={definition.id}
        definition={definition}
        revisions={revisions}
        requestedRevisionId={parseId(params.get('versie'))}
        onSelectRevision={(revisionId) => select({ definitionId: definition.id, revisionId })}
        editRequested={params.get('bewerken') === '1'}
        addressKey={params.toString()}
        onChanged={reload}
      />
    </div>
  );
}
