import type { RouteObject } from 'react-router-dom';
import { BundleListPage } from './features/bundles/BundleListPage.tsx';
import { BundleDetailPage } from './features/bundles/BundleDetailPage.tsx';
import { BundleOverviewTab } from './features/bundles/BundleOverviewTab.tsx';
import { BundleBatchesTab } from './features/bundles/BundleBatchesTab.tsx';
import { BundleMutationsTab } from './features/bundles/BundleMutationsTab.tsx';
import { BundleDecisionsTab } from './features/bundles/BundleDecisionsTab.tsx';
import { BatchDetailPage } from './features/batches/BatchDetailPage.tsx';
import { WorkQueuePage } from './features/workqueue/WorkQueuePage.tsx';

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
    ],
  },
  {
    path: '/batches/:batchId',
    element: <BatchDetailPage />,
  },
  {
    path: '*',
    element: <NotFoundPage />,
  },
];
