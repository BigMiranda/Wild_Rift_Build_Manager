import React from 'react';
import { createRoot } from 'react-dom/client';
import { STATIC } from './publicMode.js';
import './styles.css';

// The static site (Rodada 11.b) is its own small app: the full app's build never loads it, and the site's never
// loads the full app (the branch Vite does not take is dropped from the bundle).
const root = createRoot(document.getElementById('root'));
const show = (App) => root.render(<React.StrictMode><App /></React.StrictMode>);
if (STATIC) {
  import('./site/StaticApp.jsx').then((m) => show(m.default));
} else {
  import('./App.jsx').then((m) => show(m.default));
}
