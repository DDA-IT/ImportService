/**
 * Tab "Gebruikt door" van het importdefinitiedetail (R2, mock `tTabGebruikt`).
 *
 * - Herbruikbaar: een kaart per gematerialiseerde importdefinitie (`MaterialisedDefinitionView`: naam, aantal
 *   koppelingen, deelbaar of niet met het blokkerende invulpunt, op welke versie ze gebaseerd is) met haar koppelingen
 *   (`GET /import-links?importDefinitionId=`) en "Naar leverancier". De waarden van de invulpunten van een koppeling
 *   (bestaande `LinkBookmarkValuesSection`) openen met de knop bij de koppeling.
 * - Eigen: de ene leverancier met haar koppelingen.
 *
 * Wat de API niet levert staat er niet: of een koppeling "klaar" is (dat zijn taken en versies per koppeling) toont de
 * mock wel, hier staat enkel of de koppeling actief is.
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import * as importLinksApi from '../../api/importLinks.ts';
import type { DefinitionRow, ImportLinkRow, MaterialisedDefinitionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { DEFINITIONS_TEXT } from '../../terms/importDefinitions.ts';
import { BasedOnReusable } from '../setup/BasedOnReusable.tsx';
import { FlagOffNotice } from '../setup/FlagOffNotice.tsx';
import { fetchAllPages } from '../setup/setupDashboardModel.ts';
import { supplierHref } from '../setup/supplierRoutes.ts';
import { LinkBookmarkValuesSection } from './LinkBookmarkValuesSection.tsx';
import { isSetupApiDisabledError } from './setupApiFlag.ts';
import { useDefinitionDetailContext } from './TemplateDetailPage.tsx';
import styles from '../setup/SetupScreens.module.css';

const TEXT = DEFINITIONS_TEXT.usedBy;

type SelectedLink = { linkId: number; linkCode: string };

function LinkList({
  links,
  selectedLinkId,
  onSelect,
}: {
  links: readonly ImportLinkRow[];
  selectedLinkId: number | null;
  onSelect?: (link: SelectedLink) => void;
}) {
  if (links.length === 0) {
    return <p className={styles.empty}>{TEXT.noLinks}</p>;
  }
  return (
    <ul className={styles.taskList} data-testid="definition-links">
      {links.map((link) => (
        <li key={link.id}>
          <span className={styles.grow}>
            <strong>{link.name}</strong> · {TEXT.linkLibrary(link.libraryCode)}
          </span>
          <ToneBadge label={link.active ? TEXT.linkActive : TEXT.linkInactive} tone={link.active ? 'ready' : 'neutral'} />
          {onSelect !== undefined && (
            <button
              type="button"
              className={
                selectedLinkId === link.id
                  ? `${button.secondary} ${button.small} ${button.selected}`
                  : `${button.secondary} ${button.small}`
              }
              aria-pressed={selectedLinkId === link.id}
              data-testid={`select-link-${link.id}`}
              onClick={() => onSelect({ linkId: link.id, linkCode: link.code })}
            >
              {TEXT.showValues}
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}

function MaterialisedLinks({
  definitionId,
  selectedLinkId,
  onSelect,
}: {
  definitionId: number;
  selectedLinkId: number | null;
  onSelect: (link: SelectedLink) => void;
}) {
  const links = useQuery(`definition-links:${definitionId}`, (signal) =>
    fetchAllPages((page, size, s) => importLinksApi.listImportLinks({ importDefinitionId: definitionId, page, size }, s), signal),
  );
  return (
    <>
      {links.error !== null && <ErrorBanner error={links.error} />}
      {links.loading && links.data === null && <p className={styles.loading}>{TEXT.linksLoading}</p>}
      {links.data !== null && <LinkList links={links.data} selectedLinkId={selectedLinkId} onSelect={onSelect} />}
    </>
  );
}

function MaterialisedCard({
  row,
  organisationId,
  bookmarkLabel,
  selectedLinkId,
  onSelect,
}: {
  row: MaterialisedDefinitionView;
  /** De leverancier of vereniging van de gebruikende importdefinitie (uit de lijst van alle importdefinities). */
  organisationId: number | null;
  /** Het mensenlabel van het blokkerende invulpunt, als de invulpunten geladen zijn. */
  bookmarkLabel: string | null;
  selectedLinkId: number | null;
  onSelect: (link: SelectedLink) => void;
}) {
  const titleId = `used-by-${row.definitionId}`;
  return (
    <section
      className={`${pageCss.card} ${pageCss.cardPad}`}
      role="group"
      aria-labelledby={titleId}
      data-testid={`used-by-${row.definitionId}`}
    >
      <div className={styles.linkCardTop}>
        <h3 id={titleId}>{row.definitionName}</h3>
        <ToneBadge label={row.shareable ? TEXT.shareable : TEXT.notShareable} tone={row.shareable ? 'neutral' : 'awaiting'} />
      </div>
      <p className={styles.rowMeta}>
        {TEXT.basedOn(row.templateRevisionNumber)} · {TEXT.linkCount(row.importLinkCount)}
      </p>
      {!row.shareable && (
        <div
          className={styles.banner}
          role="note"
          data-testid={`not-shareable-${row.definitionId}`}
          title={row.blockingBookmarkName !== null ? `Technische naam van het invulpunt: ${row.blockingBookmarkName}` : undefined}
        >
          <p>{bookmarkLabel !== null ? TEXT.notShareableWarningNamed(bookmarkLabel) : TEXT.notShareableWarning}</p>
        </div>
      )}
      <MaterialisedLinks definitionId={row.definitionId} selectedLinkId={selectedLinkId} onSelect={onSelect} />
      {organisationId !== null && (
        <div className={styles.linkActions}>
          <Link
            className={`${button.secondary} ${button.small}`}
            to={supplierHref(organisationId)}
            data-testid={`to-supplier-${row.definitionId}`}
          >
            {TEXT.toSupplier}
          </Link>
        </div>
      )}
    </section>
  );
}

export function DefinitionUsedByTab() {
  const { bundle, reusable, extras } = useDefinitionDetailContext();
  const [selected, setSelected] = useState<SelectedLink | null>(null);
  const { definition, organisation, links, allDefinitions } = bundle;

  if (!reusable) {
    const reusablesOfOrganisation: DefinitionRow[] = allDefinitions.filter(
      (other) => other.usageType === 'REUSABLE_TEMPLATE' && other.sourceOrganisationId === definition.sourceOrganisationId,
    );
    return (
      <div data-testid="definition-used-by">
        {organisation === null ? (
          <p className={styles.empty}>{TEXT.none}</p>
        ) : (
          <section
            className={`${pageCss.card} ${pageCss.cardPad}`}
            role="group"
            aria-labelledby="used-by-own"
            data-testid="used-by-own"
          >
            <div className={styles.linkCardTop}>
              <h3 id="used-by-own">{TEXT.ownSupplier(organisation.name)}</h3>
              <ToneBadge label={TEXT.tagOwn} tone="neutral" />
            </div>
            <p className={styles.rowMeta}>{TEXT.ownNote}</p>
            <BasedOnReusable definitionId={definition.id} reusables={reusablesOfOrganisation} />
            <LinkList links={links} selectedLinkId={null} />
            <div className={styles.linkActions}>
              <Link
                className={`${button.secondary} ${button.small}`}
                to={supplierHref(organisation.id)}
                data-testid="to-supplier-own"
              >
                {TEXT.toSupplier}
              </Link>
            </div>
          </section>
        )}
      </div>
    );
  }

  if (extras.data === null) {
    return <p className={styles.loading}>{DEFINITIONS_TEXT.loading}</p>;
  }
  const { materialisations, bookmarks } = extras.data;
  if (!materialisations.ok) {
    return (
      <div data-testid="definition-used-by">
        {isSetupApiDisabledError(materialisations.error) ? (
          <FlagOffNotice message={TEXT.flagOff} className={styles.alert} />
        ) : (
          <ErrorBanner error={materialisations.error} />
        )}
      </div>
    );
  }
  if (materialisations.data.length === 0) {
    return (
      <section className={`${pageCss.card} ${pageCss.cardPad}`} data-testid="definition-used-by">
        <p className={styles.empty}>{TEXT.none}</p>
      </section>
    );
  }

  const organisationIdOf = (definitionId: number): number | null =>
    allDefinitions.find((candidate) => candidate.id === definitionId)?.sourceOrganisationId ?? null;
  const labelOf = (name: string | null): string | null =>
    name === null || bookmarks === null || !bookmarks.ok
      ? null
      : (bookmarks.data.bookmarks.find((bookmark) => bookmark.name === name)?.label ?? null);

  return (
    <div data-testid="definition-used-by">
      <div className={styles.linkGrid}>
        {materialisations.data.map((row) => (
          <MaterialisedCard
            key={row.definitionId}
            row={row}
            organisationId={organisationIdOf(row.definitionId)}
            bookmarkLabel={labelOf(row.blockingBookmarkName)}
            selectedLinkId={selected?.linkId ?? null}
            onSelect={setSelected}
          />
        ))}
      </div>
      {selected !== null && <LinkBookmarkValuesSection linkId={selected.linkId} linkCode={selected.linkCode} />}
    </div>
  );
}
