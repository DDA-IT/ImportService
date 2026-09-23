import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import './styles/tokens.css';
import './styles/reset.css';
import App from './App.tsx';
import { routes } from './routes.tsx';

const router = createBrowserRouter([
  {
    element: <App />,
    children: routes,
  },
]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
