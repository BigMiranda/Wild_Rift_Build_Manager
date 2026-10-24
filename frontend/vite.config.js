import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // API_TARGET points a second dev server at another backend (e.g. a copy of the database on another port).
    proxy: { '/api': process.env.API_TARGET ?? 'http://localhost:8080' },
  },
  build: {
    // `npm run build` drops the SPA into the backend so a single `mvnw spring-boot:run` serves everything.
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
});
