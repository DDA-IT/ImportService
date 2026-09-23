import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';

/* Placeholder pages for now */
function NotFoundPage() {
  return (
    <div style={{ padding: '2rem', textAlign: 'center' }}>
      <h1>404 — Pagina niet gevonden</h1>
      <p>De pagina die u zoekt, bestaat niet.</p>
    </div>
  );
}

function BundleListPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <h1>Publicatiebundels</h1>
      <p>Bundelijst (nog niet geïmplementeerd)</p>
    </div>
  );
}

function BundleDetailPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <h1>Bundel detail</h1>
      <p>Bundel detail pagina (nog niet geïmplementeerd)</p>
    </div>
  );
}

function BundleBatchesPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <h1>Bundel - Leden</h1>
      <p>Batches/leden tabblad (nog niet geïmplementeerd)</p>
    </div>
  );
}

function BundleMutationsPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <h1>Bundel - Mutaties</h1>
      <p>Mutaties tabblad (nog niet geïmplementeerd)</p>
    </div>
  );
}

function BundleDecisionsPage() {
  return (
    <div style={{ padding: '2rem' }}>
      <h1>Bundel - Beslissingen</h1>
      <p>Beslissingen tabblad (nog niet geïmplementeerd)</p>
    </div>
  );
}

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <Navigate to="/bundles" replace />,
  },
  {
    path: '/bundles',
    element: <BundleListPage />,
  },
  {
    path: '/bundles/:bundleId',
    element: <BundleDetailPage />,
  },
  {
    path: '/bundles/:bundleId/batches',
    element: <BundleBatchesPage />,
  },
  {
    path: '/bundles/:bundleId/mutations',
    element: <BundleMutationsPage />,
  },
  {
    path: '/bundles/:bundleId/decisions',
    element: <BundleDecisionsPage />,
  },
  {
    path: '*',
    element: <NotFoundPage />,
  },
];
