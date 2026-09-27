import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // Zware jsdom-tests (bv. GroupDecisionDialog F9.10, UploadPage) duurden onder de belasting van de volledige suite
    // ~5 s en faalden dan op de standaardgrens, terwijl ze los slagen. Ruimere grens i.p.v. een vals rode suite.
    testTimeout: 15000,
    hookTimeout: 15000,
  },
});
