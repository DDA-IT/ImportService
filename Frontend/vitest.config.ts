import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // Zware jsdom-tests (bv. GroupDecisionDialog F9.10, UploadPage, FieldsEditor, FilterGroupDialog) duren onder de
    // belasting van de volledige suite soms meer dan de standaardgrens, terwijl ze los slagen. Ruimere grens i.p.v.
    // een vals rode suite. Workers beperkt tot de helft van de cores zodat jsdom-workers elkaar niet uithongeren.
    testTimeout: 20000,
    hookTimeout: 20000,
    maxWorkers: '50%',
  },
});
