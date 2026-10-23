/**
 * Tab "Versies" van het leveranciersdetail: de historiek van de versies per importdefinitie, elk met zijn status en de
 * acties die vandaag bestaan: "Bekijken" (opent Bestand en velden met die versie), "Opvolger maken"
 * (`CreateSuccessorAction`, E2) en bij een concept "Bewerken en activeren" (opent Bestand en velden met het bewerkblok
 * open). De kaart zelf is `VersionHistoryCard`, gedeeld met het importdefinitiedetail (R2).
 */

import { useNavigate } from 'react-router-dom';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { BasedOnReusable } from './BasedOnReusable.tsx';
import { isOwnDefinition } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { supplierHref } from './supplierRoutes.ts';
import { VersionHistoryCard } from './VersionHistoryCard.tsx';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export function SupplierVersionsTab() {
  const { bundle, reload } = useSupplierDetailContext();
  const navigate = useNavigate();
  const organisationId = bundle.supplier.organisation.id;
  const definitions = bundle.supplier.definitions;

  if (definitions.length === 0) {
    return <p className={styles.empty}>{TEXT.noDefinition}</p>;
  }

  const reusables = definitions.map((entry) => entry.definition).filter((definition) => !isOwnDefinition(definition));

  return (
    <div data-testid="supplier-versions">
      {definitions.map(({ definition }) => (
        <VersionHistoryCard
          key={definition.id}
          definition={definition}
          revisions={bundle.revisions[definition.id] ?? []}
          note={
            isOwnDefinition(definition) ? (
              <BasedOnReusable
                definitionId={definition.id}
                basedOnDefinitionId={definition.basedOnDefinitionId}
                reusables={reusables}
              />
            ) : undefined
          }
          viewHref={(revision) =>
            supplierHref(organisationId, 'fields', { definitionId: definition.id, revisionId: revision.id })
          }
          editHref={(revision) =>
            supplierHref(organisationId, 'fields', { definitionId: definition.id, revisionId: revision.id, edit: true })
          }
          onSuccessorCreated={(created) => {
            // De nieuwe versie wordt meteen geopend (bewerkblok open); de lijst herlaadt mee.
            reload();
            navigate(
              supplierHref(organisationId, 'fields', { definitionId: definition.id, revisionId: created.id, edit: true }),
            );
          }}
        />
      ))}
    </div>
  );
}
