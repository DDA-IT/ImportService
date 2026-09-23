import { Outlet } from 'react-router-dom';
import { ActorBar } from './actor/ActorBar.tsx';

function App() {
  return (
    <>
      <header style={{ borderBottom: '1px solid var(--color-border)', padding: 'var(--spacing-4)' }}>
        <div style={{ maxWidth: '1400px', margin: '0 auto' }}>
          <h1 style={{ fontSize: '1.5rem', margin: '0 0 var(--spacing-4) 0' }}>CatalogImport</h1>
          <nav style={{ display: 'flex', gap: 'var(--spacing-4)' }}>
            <a href="/">Werkvoorraad</a>
            <a href="/bundles">Publicatiebundels</a>
          </nav>
        </div>
      </header>
      <ActorBar />
      <main style={{ maxWidth: '1400px', margin: '0 auto', width: '100%' }}>
        <Outlet />
      </main>
    </>
  );
}

export default App;
