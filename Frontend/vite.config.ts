import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Frontend calls to /api are proxied to the CatalogImport backend (Web module).
      // See docs/decisions.md "2026-09-22 — Frontend: stack en locatie".
      // Fase 5-AUTH (docs/design/fase5-auth-design.md §6, C10): changeOrigin blijft false zodat de Host
      // localhost:5173 blijft en Spring de OIDC redirect_uri op :5173 bouwt (callback loopt via de proxy).
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: false,
      },
      '/oauth2': {
        target: 'http://localhost:8081',
        changeOrigin: false,
      },
      '/login': {
        target: 'http://localhost:8081',
        changeOrigin: false,
      },
    },
  },
})
