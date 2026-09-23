import type { RouteObject } from 'react-router-dom';
import { BundleListPage } from './features/bundles/BundleListPage.tsx';
import { BundleDetailPage } from './features/bundles/BundleDetailPage.tsx';
import { BundleOverviewTab } from './features/bundles/BundleOverviewTab.tsx';
import { BundleBatchesTab } from './features/bundles/BundleBatchesTab.tsx';
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

/* Bouwstap F8 (herbruikbaar mutatielijst-component) volgt nog. */
function BundleMutationsPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <p>Mutaties tabblad (nog niet geïmplementeerd — bouwstap F8)</p>
    </div>
  );
}

/* Bouwstap F11 (alleen-lezen beslissingsregister) volgt nog. */
function BundleDecisionsPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <p>Beslissingen tabblad (nog niet geïmplementeerd — bouwstap F11)</p>
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
      { path: 'mutations', element: <BundleMutationsPage /> },
      { path: 'decisions', element: <BundleDecisionsPage /> },
    ],
  },
  {
    path: '*',
    element: <NotFoundPage />,
  },
];
