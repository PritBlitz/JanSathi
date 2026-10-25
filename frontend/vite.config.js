import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/** Emits sw.js with the build's hashed asset names, so the first visit is enough to work offline. */
function serviceWorker() {
  return {
    name: 'jansathi-sw',
    apply: 'build',
    generateBundle(_, bundle) {
      const assets = Object.keys(bundle).filter((f) => f.startsWith('assets/'));
      const version = createHash('sha256').update(assets.join('\n')).digest('hex').slice(0, 12);
      const source = readFileSync(new URL('./src/sw.js', import.meta.url), 'utf8')
        .replace('__VERSION__', version)
        .replace('__BUILD_ASSETS__', JSON.stringify(assets));
      this.emitFile({ type: 'asset', fileName: 'sw.js', source });
    },
  };
}

// Relative base: the built PWA works from any static host path and from the Spring Boot jar.
// In dev, /api is proxied to the Spring Boot API (npm run start:backend); when it is not
// running, the app falls back to the static bundle in public/data.
export default defineConfig(({ command }) => ({
  base: './',
  plugins: [react(), serviceWorker()],
  define: command === 'serve' ? { 'import.meta.env.VITE_API_BASE': JSON.stringify('') } : {},
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  preview: { port: 4173 },
  build: {
    target: 'es2022',
    cssCodeSplit: false,
  },
}));
