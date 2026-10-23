/**
 * Tab "Versies" van het importdefinitiedetail (R2): de historiek van de versies met "Bekijken" (opent Bestand en velden
 * met die versie), "Opvolger maken" en bij een concept "Bewerken en activeren" (opent Bestand en velden met het bewerkblok
 * open, waar de bestaande activeeractie staat). Dezelfde kaart als op het leveranciersdetail: `VersionHistoryCard`.
 */

import { useNavigate } from 'react-router-dom';
import { BasedOnReusable } from '../setup/BasedOnReusable.tsx';
import { VersionHistoryCard } from '../setup/VersionHistoryCard.tsx';
import { definitionHref } from './definitionRoutes.ts';
import { useDefinitionDetailContext } from './TemplateDetailPage.tsx';

export function DefinitionVersionsTab() {
  const { bundle, reusable, reload } = useDefinitionDetailContext();
  const navigate = useNavigate();
  const { definition, revisions, allDefinitions } = bundle;
  const reusablesOfOrganisation = allDefinitions.filter(
    (other) => other.usageType === 'REUSABLE_TEMPLATE' && other.sourceOrganisationId === definition.sourceOrganisationId,
  );

  return (
    <div data-testid="definition-versions">
      <VersionHistoryCard
        definition={definition}
        revisions={revisions}
        note={reusable ? undefined : <BasedOnReusable definitionId={definition.id} reusables={reusablesOfOrganisation} />}
        viewHref={(revision) => definitionHref(definition.id, 'fields', { revisionId: revision.id })}
        editHref={(revision) => definitionHref(definition.id, 'fields', { revisionId: revision.id, edit: true })}
        onSuccessorCreated={(created) => {
          // De nieuwe versie wordt meteen geopend (bewerkblok open); de lijst herlaadt mee.
          reload();
          navigate(definitionHref(definition.id, 'fields', { revisionId: created.id, edit: true }));
        }}
      />
    </div>
  );
}
