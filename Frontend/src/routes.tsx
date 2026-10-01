import type { RouteObject } from 'react-router-dom';
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
import { NewSupplierWizardPage } from './features/setup/wizard/NewSupplierWizardPage.tsx';
import { LinkCheckPage } from './features/setup/check/LinkCheckPage.tsx';
import { TemplateListPage } from './features/templates/TemplateListPage.tsx';
import { TemplateDetailPage } from './features/templates/TemplateDetailPage.tsx';
import { IssueCaseListPage } from './features/issuecases/IssueCaseListPage.tsx';
import { IssueCaseDetailPage } from './features/issuecases/IssueCaseDetailPage.tsx';

/* Placeholder pages for now */
function NotFoundPage() {
  return (
    <div style={{ padding: '2rem', textAlign: 'center' }}>
      <h1>404 — Pagina niet gevonden</h1>
      <p>De pagina die u zoekt, bestaat niet.</p>
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
    path: '/setup/new',
    element: <NewSupplierWizardPage />,
  },
  {
    path: '/setup/links/:linkId/check',
    element: <LinkCheckPage />,
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
