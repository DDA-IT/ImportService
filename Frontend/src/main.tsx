import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import '@fontsource-variable/public-sans';
import '@fontsource/barlow/600.css';
import '@fontsource/barlow/700.css';
import './styles/tokens.css';
import './styles/reset.css';
import App from './App.tsx';
import { routes } from './routes.tsx';
import { ActorProvider } from './actor/ActorContext.tsx';

const router = createBrowserRouter([
  {
    element: <App />,
    children: routes,
  },
]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ActorProvider onRestorePath={(path) => void router.navigate(path, { replace: true })}>
      <RouterProvider router={router} />
    </ActorProvider>
  </StrictMode>,
);
