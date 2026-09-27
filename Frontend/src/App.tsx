import { Outlet } from 'react-router-dom';
import { ActorBar } from './actor/ActorBar.tsx';
import { useActor } from './actor/ActorContext.tsx';

const NO_PERMISSIONS_MESSAGE = 'U heeft geen rechten voor CatalogImport.';

function App() {
  const { permissions } = useActor();
  // 5-PERM: zonder enig recht (`/me.permissions = []`) blijven header en menu staan, maar de pagina's worden
  // niet gemount (`Outlet` ontbreekt), zodat hun queries niet starten en geen 403's ophalen.
  const noPermissions = permissions.length === 0;

  return (
    <>
      <header style={{ borderBottom: '1px solid var(--color-border)', padding: 'var(--spacing-4)' }}>
        <div style={{ maxWidth: '1400px', margin: '0 auto' }}>
          <h1 style={{ fontSize: '1.5rem', margin: '0 0 var(--spacing-4) 0' }}>CatalogImport</h1>
          <nav style={{ display: 'flex', gap: 'var(--spacing-4)' }}>
            <a href="/">Werkvoorraad</a>
            <a href="/upload">Levering uploaden</a>
            <a href="/bundles">Publicatiebundels</a>
          </nav>
        </div>
      </header>
      <ActorBar />
      <main style={{ maxWidth: '1400px', margin: '0 auto', width: '100%' }}>
        {noPermissions ? (
          <div role="alert" data-testid="no-permissions" style={{ padding: 'var(--spacing-4)' }}>
            <p>{NO_PERMISSIONS_MESSAGE}</p>
            <p>Vraag de beheerder om het recht om CatalogImport te lezen (catalogImport.read).</p>
          </div>
        ) : (
          <Outlet />
        )}
      </main>
    </>
  );
}

export default App;
