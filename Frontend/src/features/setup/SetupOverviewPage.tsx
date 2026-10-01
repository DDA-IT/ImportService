/**
 * `/setup` — scherm 1a: de inrichtingsboom (S1-F1, `docs/decisions.md` 2026-09-27 "scherm 1a/1b")
 * bronorganisatie → definitie → revisie/koppeling, plus sinds S1-F4 het schrijfdeel op revisieniveau
 * (`docs/design/revision-successor-design.md` §6, endpoints E1 t/m E5).
 *
 * De boom wordt **client-side** opgebouwd uit de drie vlagloze S1-B1-lijstendpoints
 * (`GET /source-organisations`, `GET /definitions`, `GET /definitions/{id}/revisions`) plus het
 * additieve `importDefinitionId`-filter op `GET /import-links` — NIET via het (nog steeds
 * vlaggedekte) `GET /setup/overview`. Elk niveau laadt lazy: een kind-lijst wordt pas bevraagd
 * zodra de gebruiker een rij uitklapt (het kindcomponent wordt dan pas gemount).
 *
 * **NT-7**: onder elke koppeling staan haar taken (`GET /tasks?importLinkId=`, recht Lezen, NT-4-velden):
 * naam, hoe ze start en "nog niet klaar: versie niet geactiveerd" zolang de beschrijving geen actieve
 * versie heeft. "Taak toevoegen" (recht Beheren) opent het stappenplan bij de taakstap.
 *
 * **S1-F4 — het schrijfdeel op revisieniveau.** De boom zelf blijft alleen-lezen en vlagloos; nieuw zijn
 * per revisierij: "Detail openen" dat het volledige revisiedetail laadt (E1, recht `READ`, buiten de
 * vlag) en "Opvolger maken" (E2, recht `MANAGE`, achter `catalogimport.setup-api.enabled`, alleen op een
 * ACTIVE/SUPERSEDED revisie). Het detail zelf draagt het bewerkformulier (E3), de verwijderacties op
 * mappings/filters (E4) en het activeren met de verplichte R-CASE-03-waarschuwing (E5) — alle vier enkel
 * op een DRAFT. De tellers/lijsten van organisaties, definities en koppelingen zijn ongewijzigd.
 *
 * Na een geslaagde kloon springt het scherm meteen naar de nieuwe DRAFT en herlaadt het de revisielijst
 * (expliciete invalidatie, geen cachebibliotheek).
 *
 * **NT-6** (`docs/decisions.md` 2026-09-30, NT-spoor): de knop "Nieuwe leverancier en taak" (recht Beheren)
 * opent het stappenplan `/setup/new`; "Verder inrichten" hervat het bij een organisatie zonder definitie,
 * een eigen definitie zonder koppeling en een koppeling zonder taak. `?definitionId=…&revisionId=…` opent
 * één revisiedetail bovenaan.
 *
 * **NT-10**: elke koppeling heeft "Controleren" (recht Lezen): het scherm `/setup/links/:linkId/check` met de
 * checklist, de proefinlezing en het activeren. Daar landt ook de slotstap van het stappenplan.
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import * as setupApi from '../../api/setup.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import * as tasksApi from '../../api/tasks.ts';
import { PERMISSION_MANAGE, PERMISSION_READ } from '../../api/types.ts';
import type {
  DefinitionRow,
  DefinitionUsageType,
  ImportLinkRow,
  RevisionRow,
  SourceOrganisationRow,
} from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { usePermissionGate } from '../../actor/permissions.ts';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { linkCheckHref } from './check/linkCheck.ts';
import { CreateSuccessorAction } from './CreateSuccessorAction.tsx';
import { RevisionDetailSection } from './RevisionDetailSection.tsx';
import { wizardHref } from './wizard/wizardTypes.ts';
import styles from './SetupOverviewPage.module.css';

/** Het servermaximum per pagina voor de takenlijst van één koppeling (een koppeling heeft er maar enkele). */
const TASKS_PAGE_SIZE = 200;

function ExpandButton({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <button type="button" className={styles.expandButton} aria-expanded={expanded} onClick={onToggle}>
      {expanded ? '▾ Inklappen' : '▸ Uitklappen'}
    </button>
  );
}

/** Toont de actieve revisie van een definitie — `null` is expliciet "geen actieve revisie", nooit "#0". */
function ActiveRevision({ activeRevisionId }: { activeRevisionId: number | null }) {
  if (activeRevisionId === null) {
    return <span className={styles.noActiveRevision}>geen actieve versie</span>;
  }
  return <span>actieve versie: #{activeRevisionId}</span>;
}

/**
 * NT-6 — "Verder inrichten": opent het stappenplan bij de volgende stap voor een onderdeel zonder
 * vervolg (organisatie zonder beschrijving, beschrijving zonder koppeling, koppeling zonder taak). Zonder
 * recht Beheren uitgeschakeld mét reden, zoals elke andere schrijfactie.
 */
function ContinueSetupAction({ href, testId }: { href: string; testId: string }) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  if (!manageGate.allowed) {
    return (
      <button type="button" className={styles.expandButton} disabled title={manageGate.reason} data-testid={testId}>
        Verder inrichten
      </button>
    );
  }
  return (
    <Link className={styles.expandButton} to={href} data-testid={testId}>
      Verder inrichten
    </Link>
  );
}

/**
 * NT-7 — "Taak toevoegen": opent het stappenplan bij de taakstap van deze koppeling. Recht Beheren; zonder dat
 * recht uitgeschakeld mét reden, zoals elke andere schrijfactie.
 */
function AddTaskAction({ href, testId }: { href: string; testId: string }) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  if (!manageGate.allowed) {
    return (
      <button type="button" className={styles.expandButton} disabled title={manageGate.reason} data-testid={testId}>
        Taak toevoegen
      </button>
    );
  }
  return (
    <Link className={styles.expandButton} to={href} data-testid={testId}>
      Taak toevoegen
    </Link>
  );
}

/**
 * NT-7 — de taken van één koppeling (`GET /tasks?importLinkId=`, recht Lezen): naam, hoe ze start en of ze al
 * klaar is. Dezelfde vraag beslist ook over "Verder inrichten" (NT-6, enkel bij een koppeling zonder taak), zodat
 * er per koppeling maar één takenverzoek is. Een taak zonder actieve versie krijgt "nog niet klaar", zoals bij
 * Levering uploaden: ze kan nog geen levering aannemen.
 */
function LinkTasks({
  link,
  organisationId,
  canContinue,
}: {
  link: ImportLinkRow;
  organisationId: number;
  canContinue: boolean;
}) {
  const tasks = useQuery(`setup-link-tasks:${link.id}`, (signal) =>
    tasksApi.listTasks({ importLinkId: link.id, page: 0, size: TASKS_PAGE_SIZE }, signal),
  );
  const href = wizardHref({ organisationId, definitionId: link.importDefinitionId, linkId: link.id });

  return (
    <div className={styles.tasks} data-testid={`link-tasks-${link.id}`}>
      <h5 className={styles.childTitle}>Taken</h5>
      {tasks.error !== null && <ErrorBanner error={tasks.error} />}
      {tasks.loading && tasks.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {tasks.data !== null && (
        <>
          {tasks.data.content.length === 0 ? (
            <p className={styles.empty}>
              Nog geen taak: zonder taak kan er geen levering opgeladen worden.{' '}
              {canContinue && <ContinueSetupAction href={href} testId={`continue-link-${link.id}`} />}
            </p>
          ) : (
            <ul className={styles.list}>
              {tasks.data.content.map((task) => (
                <li key={task.id} className={styles.taskRow} data-testid={`task-${task.id}`}>
                  <span>{task.name}</span>
                  <span>
                    <Term domain="taskTrigger" code={task.triggerType} />
                  </span>
                  {!task.active && <span>niet actief</span>}
                  {task.activeRevisionId === null && (
                    <span className={styles.noActiveRevision} data-testid={`task-not-ready-${task.id}`}>
                      nog niet klaar: versie niet geactiveerd
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
          {canContinue && <AddTaskAction href={href} testId={`add-task-${link.id}`} />}
        </>
      )}
    </div>
  );
}

/** Koppelingenlijst (lazy) van één definitie, elke koppeling met haar taken (NT-7). */
function ImportLinksList({
  definitionId,
  organisationId,
  usageType,
}: {
  definitionId: number;
  organisationId: number;
  usageType: DefinitionUsageType;
}) {
  // Het stappenplan maakt enkel eigen definities (A1); een sjabloon krijgt hier geen "Verder inrichten".
  const canContinue = usageType !== 'REUSABLE_TEMPLATE';
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const key = `setup-import-links:${definitionId}:${page}:${size}`;
  const links = useQuery(key, (signal) =>
    importLinksApi.listImportLinks({ importDefinitionId: definitionId, page, size }, signal),
  );

  return (
    <div className={styles.childSection}>
      <h4 className={styles.childTitle}>Koppelingen</h4>
      {links.error !== null && <ErrorBanner error={links.error} />}
      {links.loading && links.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {links.data !== null && (
        <>
          {links.data.content.length === 0 ? (
            <p className={styles.empty}>
              Geen koppelingen voor deze beschrijving.{' '}
              {canContinue && (
                <ContinueSetupAction
                  href={wizardHref({ organisationId, definitionId })}
                  testId={`continue-definition-${definitionId}`}
                />
              )}
            </p>
          ) : (
            <ul className={styles.list}>
              {links.data.content.map((link: ImportLinkRow) => (
                <li key={link.id} className={styles.listItem}>
                  <div className={styles.linkRow}>
                    <span className={styles.code}>{link.code}</span>
                    <span>{link.name}</span>
                    <span>
                      leverancier {link.supplierCode} · bibliotheek {link.libraryCode}
                    </span>
                    <span>{link.active ? 'actief' : 'inactief'}</span>
                    <Link
                      className={styles.expandButton}
                      to={linkCheckHref({ linkId: link.id, definitionId: link.importDefinitionId })}
                      title="Is deze koppeling klaar? Checklist, proefinlezing en activeren."
                      data-testid={`check-link-${link.id}`}
                    >
                      Controleren
                    </Link>
                  </div>
                  <LinkTasks link={link} organisationId={organisationId} canContinue={canContinue} />
                </li>
              ))}
            </ul>
          )}
          <Pager
            page={links.data.page}
            size={links.data.size}
            totalElements={links.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

/**
 * Revisielijst (lazy) van één definitie, met sinds S1-F4 per rij het openen van het revisiedetail (E1)
 * en "Opvolger maken" (E2). Het gekozen detail staat onder de lijst, niet in de rij: het is een groot
 * blok met een eigen bewerkformulier en hoort niet in een lijstrij te passen.
 */
function RevisionsList({
  definitionId,
  onDefinitionChanged,
}: {
  definitionId: number;
  /**
   * Activeren verplaatst `activeRevisionId` van de definitie; zonder deze terugmelding zou de
   * definitierij erboven blijven zeggen "actieve revisie: #<oude>". De definitielijst herlaadt daarom mee.
   */
  onDefinitionChanged: () => void;
}) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [openRevisionId, setOpenRevisionId] = useState<number | null>(null);
  const key = `setup-revisions:${definitionId}:${page}:${size}`;
  const revisions = useQuery(key, (signal) => setupApi.listDefinitionRevisions(definitionId, { page, size }, signal));

  return (
    <div className={styles.childSection}>
      <h4 className={styles.childTitle}>Versies</h4>
      {revisions.error !== null && <ErrorBanner error={revisions.error} />}
      {revisions.loading && revisions.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {revisions.data !== null && (
        <>
          {revisions.data.content.length === 0 ? (
            <p className={styles.empty}>Geen versies voor deze beschrijving.</p>
          ) : (
            <ul className={styles.list}>
              {revisions.data.content.map((revision: RevisionRow) => (
                <li key={revision.id} className={styles.listItem}>
                  <div className={styles.revisionRow}>
                    <span>Versie {revision.revisionNumber}</span>
                    <StatusBadge status={revision.status} domain="revisionStatus" />
                    <button
                      type="button"
                      className={styles.expandButton}
                      aria-expanded={openRevisionId === revision.id}
                      data-testid={`open-revision-${revision.id}`}
                      onClick={() =>
                        setOpenRevisionId((current) => (current === revision.id ? null : revision.id))
                      }
                    >
                      {openRevisionId === revision.id ? 'Detail sluiten' : 'Detail openen'}
                    </button>
                    <CreateSuccessorAction
                      revision={revision}
                      onCreated={(created) => {
                        // De nieuwe DRAFT kan op een andere pagina van deze lijst vallen; ze wordt
                        // daarom rechtstreeks geopend én de lijst wordt herladen.
                        revisions.reload();
                        setOpenRevisionId(created.id);
                      }}
                    />
                  </div>
                </li>
              ))}
            </ul>
          )}
          <Pager
            page={revisions.data.page}
            size={revisions.data.size}
            totalElements={revisions.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}

      {openRevisionId !== null && (
        <RevisionDetailSection
          definitionId={definitionId}
          revisionId={openRevisionId}
          onRevisionChanged={() => {
            revisions.reload();
            onDefinitionChanged();
          }}
        />
      )}
    </div>
  );
}

/** Eén definitierij; klapt lazy open naar revisies + koppelingen. */
function DefinitionNode({
  definition,
  onDefinitionChanged,
}: {
  definition: DefinitionRow;
  onDefinitionChanged: () => void;
}) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className={styles.listItem}>
      <div className={styles.nodeRow}>
        <ExpandButton expanded={expanded} onToggle={() => setExpanded((v) => !v)} />
        <span className={styles.code}>{definition.code}</span>
        <span>{definition.name}</span>
        <span>
          <Term domain="definitionUsage" code={definition.usageType} unknownLabel="Andere soort beschrijving" />
        </span>
        <ActiveRevision activeRevisionId={definition.activeRevisionId} />
      </div>
      {expanded && (
        <div className={styles.children}>
          <RevisionsList definitionId={definition.id} onDefinitionChanged={onDefinitionChanged} />
          <ImportLinksList
            definitionId={definition.id}
            organisationId={definition.sourceOrganisationId}
            usageType={definition.usageType}
          />
        </div>
      )}
    </li>
  );
}

/** Definitielijst (lazy) van één bronorganisatie. */
function DefinitionsList({ sourceOrganisationId }: { sourceOrganisationId: number }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const key = `setup-definitions:${sourceOrganisationId}:${page}:${size}`;
  const definitions = useQuery(key, (signal) =>
    setupApi.listDefinitions({ sourceOrganisationId, page, size }, signal),
  );

  return (
    <div className={styles.childSection}>
      <h3 className={styles.childTitle}>Beschrijvingen van het bestand</h3>
      {definitions.error !== null && <ErrorBanner error={definitions.error} />}
      {definitions.loading && definitions.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {definitions.data !== null && (
        <>
          {definitions.data.content.length === 0 ? (
            <p className={styles.empty}>
              Geen beschrijvingen voor deze leverancier of aankoopvereniging.{' '}
              <ContinueSetupAction
                href={wizardHref({ organisationId: sourceOrganisationId })}
                testId={`continue-organisation-${sourceOrganisationId}`}
              />
            </p>
          ) : (
            <ul className={styles.list}>
              {definitions.data.content.map((definition) => (
                <DefinitionNode
                  key={definition.id}
                  definition={definition}
                  onDefinitionChanged={definitions.reload}
                />
              ))}
            </ul>
          )}
          <Pager
            page={definitions.data.page}
            size={definitions.data.size}
            totalElements={definitions.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

/** Eén bronorganisatierij; klapt lazy open naar de definitielijst. */
function OrganisationNode({ organisation }: { organisation: SourceOrganisationRow }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className={styles.listItem}>
      <div className={styles.nodeRow}>
        <ExpandButton expanded={expanded} onToggle={() => setExpanded((v) => !v)} />
        <span className={styles.code}>{organisation.code}</span>
        <span>{organisation.name}</span>
        <span>
          <Term domain="organisationType" code={organisation.type} />
        </span>
        <span>{organisation.active ? 'actief' : 'inactief'}</span>
      </div>
      {expanded && (
        <div className={styles.children}>
          <DefinitionsList sourceOrganisationId={organisation.id} />
        </div>
      )}
    </li>
  );
}

/**
 * NT-6 — de knop naar het stappenplan. Zonder recht Beheren uitgeschakeld mét reden (nooit verborgen),
 * zoals elke andere schrijfactie (5-PERM).
 */
function NewSupplierButton() {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  if (!manageGate.allowed) {
    return (
      <p className={styles.intro}>
        <button
          type="button"
          className={styles.expandButton}
          disabled
          title={manageGate.reason}
          aria-describedby="new-supplier-blocked"
          data-testid="new-supplier-button"
        >
          Nieuwe leverancier en taak
        </button>{' '}
        <span id="new-supplier-blocked" data-testid="new-supplier-blocked">
          {manageGate.reason}
        </span>
      </p>
    );
  }
  return (
    <p className={styles.intro}>
      <Link className={styles.expandButton} to="/setup/new" data-testid="new-supplier-button">
        Nieuwe leverancier en taak
      </Link>
    </p>
  );
}

function parseId(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * NT-6 — `?definitionId=…&revisionId=…` opent één revisiedetail bovenaan (met "Revisie activeren"). Zo kan
 * de slotstap van het stappenplan rechtstreeks naar de conceptversie verwijzen, zonder de boom (die per
 * niveau pagineert) automatisch open te klappen.
 */
function OpenedRevision() {
  const [params, setParams] = useSearchParams();
  const definitionId = parseId(params.get('definitionId'));
  const revisionId = parseId(params.get('revisionId'));
  if (definitionId === null || revisionId === null) {
    return null;
  }
  return (
    <section className={styles.childSection} aria-label="Geopende versie" data-testid="opened-revision">
      <h2 className={styles.childTitle}>Geopende versie</h2>
      <p className={styles.intro}>
        Controleer de versie hieronder en activeer ze met &laquo;Versie activeren&raquo;. Pas daarna kan er op
        de taak een levering opgeladen worden.{' '}
        <button type="button" className={styles.expandButton} onClick={() => setParams({})}>
          Sluiten
        </button>
      </p>
      {/* Het detail herlaadt zichzelf na een wijziging of activatie. De boom hieronder laadt pas bij het
          uitklappen en is dus niet mee te herladen; een al uitgeklapte tak toont de nieuwe stand na
          opnieuw uitklappen. */}
      <RevisionDetailSection definitionId={definitionId} revisionId={revisionId} onRevisionChanged={() => {}} />
    </section>
  );
}

export function SetupOverviewPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const key = `setup-source-organisations:${page}:${size}`;
  const organisations = useQuery(key, (signal) => setupApi.listSourceOrganisations({ page, size }, signal));

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p role="alert">{readGate.reason}</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Inrichting</h1>
      <p className={styles.intro}>
        Overzicht van leveranciers en aankoopverenigingen, de beschrijvingen van hun bestanden,
        de versies daarvan, koppelingen en taken. Klik op &laquo;Uitklappen&raquo; om een niveau te
        openen. Bij een versie kunt u het volledige detail openen; bij een actieve of eerder actieve versie een
        opvolger maken, en bij een concept dat aanpassen en activeren.
      </p>
      <WhatIsThis>
        Een leverancier of aankoopvereniging levert bestanden aan. De beschrijving van het bestand legt vast hoe zo
        een bestand gelezen wordt; ze heeft versies, waarvan er hoogstens één actief is. Een koppeling verbindt de
        beschrijving met één leverancier en één Prodis-bibliotheek, en een taak is de ingang waarop u leveringen
        oplaadt.
      </WhatIsThis>
      <NewSupplierButton />
      <OpenedRevision />

      {organisations.error !== null && <ErrorBanner error={organisations.error} />}
      {organisations.loading && organisations.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {organisations.data !== null && (
        <>
          {organisations.data.content.length === 0 ? (
            <p className={styles.empty}>Geen leveranciers of aankoopverenigingen gevonden.</p>
          ) : (
            <ul className={styles.list} data-testid="source-organisations">
              {organisations.data.content.map((organisation) => (
                <OrganisationNode key={organisation.id} organisation={organisation} />
              ))}
            </ul>
          )}
          <Pager
            page={organisations.data.page}
            size={organisations.data.size}
            totalElements={organisations.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}
