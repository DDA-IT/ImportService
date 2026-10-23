import { Link, type RouteObject } from 'react-router-dom';
import button from './components/Button.module.css';
import { FileIcon } from './components/icons.tsx';
import pageCss from './components/Page.module.css';
import { BundleListPage } from './features/bundles/BundleListPage.tsx';
import { BundleDetailPage } from './features/bundles/BundleDetailPage.tsx';
import { BundleOverviewTab } from './features/bundles/BundleOverviewTab.tsx';
import { BundleBatchesTab } from './features/bundles/BundleBatchesTab.tsx';
import { BundleMutationsTab } from './features/bundles/BundleMutationsTab.tsx';
import { BundleDecisionsTab } from './features/bundles/BundleDecisionsTab.tsx';
import { BundlePublicationTab } from './features/bundles/BundlePublicationTab.tsx';
import { BatchDetailPage } from './features/batches/BatchDetailPage.tsx';
import { UploadPage } from './features/upload/UploadPage.tsx';
import { WorkQueuePage } from './features/workqueue/WorkQueuePage.tsx';
import { SetupOverviewPage } from './features/setup/SetupOverviewPage.tsx';
import { SupplierDetailPage } from './features/setup/SupplierDetailPage.tsx';
import { SupplierOverviewTab } from './features/setup/SupplierOverviewTab.tsx';
import { SupplierFieldsTab } from './features/setup/SupplierFieldsTab.tsx';
import { SupplierVersionsTab } from './features/setup/SupplierVersionsTab.tsx';
import { SupplierLinksTab } from './features/setup/SupplierLinksTab.tsx';
import { NewSupplierWizardPage } from './features/setup/wizard/NewSupplierWizardPage.tsx';
import { LinkCheckPage } from './features/setup/check/LinkCheckPage.tsx';
import { LegacyReceiptListPage } from './features/legacyconfig/LegacyReceiptListPage.tsx';
import { LegacyReceiptDetailPage } from './features/legacyconfig/LegacyReceiptDetailPage.tsx';
import { TemplateListPage } from './features/templates/TemplateListPage.tsx';
import { TemplateDetailPage } from './features/templates/TemplateDetailPage.tsx';
import { IssueCaseListPage } from './features/issuecases/IssueCaseListPage.tsx';
import { IssueCaseDetailPage } from './features/issuecases/IssueCaseDetailPage.tsx';
import { NOT_FOUND_BACK } from './terms/navigation.ts';

/** Lege staat (mock .empty) met een weg terug naar Inlezingen. */
export function NotFoundPage() {
  return (
    <div className={pageCss.card}>
      <div className={pageCss.empty}>
        <FileIcon aria-hidden="true" />
        <h1 className={pageCss.subtitle}>404 — Pagina niet gevonden</h1>
        <p>De pagina die u zoekt, bestaat niet.</p>
        <p>
          <Link to="/" className={button.primary}>
            {NOT_FOUND_BACK}
          </Link>
        </p>
      </div>
    </div>
  );
}

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <WorkQueuePage />,
  },
  {
    path: '/bundles',
    element: <BundleListPage />,
  },
  {
    path: '/bundles/:bundleId',
    element: <BundleDetailPage />,
    children: [
      { index: true, element: <BundleOverviewTab /> },
      { path: 'batches', element: <BundleBatchesTab /> },
      { path: 'mutations', element: <BundleMutationsTab /> },
      { path: 'decisions', element: <BundleDecisionsTab /> },
      { path: 'publication', element: <BundlePublicationTab /> },
    ],
  },
  {
    path: '/batches/:batchId',
    element: <BatchDetailPage />,
  },
  {
    path: '/upload',
    element: <UploadPage />,
  },
  {
    path: '/setup',
    element: <SetupOverviewPage />,
  },
  {
    path: '/setup/suppliers/:organisationId',
    element: <SupplierDetailPage />,
    children: [
      { index: true, element: <SupplierOverviewTab /> },
      { path: 'fields', element: <SupplierFieldsTab /> },
      { path: 'versions', element: <SupplierVersionsTab /> },
      { path: 'links', element: <SupplierLinksTab /> },
    ],
  },
  {
    path: '/setup/new',
    element: <NewSupplierWizardPage />,
  },
  {
    path: '/setup/links/:linkId/check',
    element: <LinkCheckPage />,
  },
  {
    path: '/setup/legacy-config',
    element: <LegacyReceiptListPage />,
  },
  {
    path: '/setup/legacy-config/:receiptId',
    element: <LegacyReceiptDetailPage />,
  },
  {
    path: '/templates',
    element: <TemplateListPage />,
  },
  {
    path: '/templates/:definitionId',
    element: <TemplateDetailPage />,
  },
  {
    path: '/issue-cases',
    element: <IssueCaseListPage />,
  },
  {
    path: '/issue-cases/:caseId',
    element: <IssueCaseDetailPage />,
  },
  {
    path: '*',
    element: <NotFoundPage />,
  },
];
