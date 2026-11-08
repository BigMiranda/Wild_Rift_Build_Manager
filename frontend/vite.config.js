import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // The static site (Rodada 11.b) is served from a folder of GitHub Pages (/<repo>/): relative paths by default, or
  // the folder given in VITE_BASE ("/meu-repo/").
  base: mode === 'estatico' ? (process.env.VITE_BASE || './') : '/',
  server: {
    port: 5173,
    // API_TARGET points a second dev server at another backend (e.g. a copy of the database on another port).
    proxy: { '/api': process.env.API_TARGET ?? 'http://localhost:8080' },
  },
  build: mode === 'estatico'
    // the page only; tools/publicar_site.py puts it next to the generated api/ in site-publico/
    ? { outDir: 'dist-estatico', emptyOutDir: true }
    // `npm run build` drops the SPA into the backend so a single `mvnw spring-boot:run` serves everything.
    : { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
}));
