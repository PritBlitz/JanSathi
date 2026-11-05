import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/** Emits sw.js with the build's hashed asset names, so the first visit is enough to work offline. */
function serviceWorker() {
  return {
    name: 'jansathi-sw',
    apply: 'build',
    generateBundle(_, bundle) {
      // Lazy chunks of the OCR and map libraries are left to load on first use.
      const assets = Object.keys(bundle).filter((f) => f.startsWith('assets/') && !/tesseract|leaflet/i.test(f));
      const version = createHash('sha256').update(assets.join('\n')).digest('hex').slice(0, 12);
      const source = readFileSync(new URL('./src/sw.js', import.meta.url), 'utf8')
        .replace('__VERSION__', version)
        .replace('__BUILD_ASSETS__', JSON.stringify(assets));
      this.emitFile({ type: 'asset', fileName: 'sw.js', source });
    },
  };
}

// The OCR engine and language files, served from this site so a photo check needs no third party.
// They come from node_modules; only people who use the check download them.
const OCR_FILES = {
  'worker.min.js': 'tesseract.js/dist/worker.min.js',
  'tesseract-core-lstm.wasm.js': 'tesseract.js-core/tesseract-core-lstm.wasm.js',
  'tesseract-core-simd-lstm.wasm.js': 'tesseract.js-core/tesseract-core-simd-lstm.wasm.js',
  'tesseract-core-relaxedsimd-lstm.wasm.js': 'tesseract.js-core/tesseract-core-relaxedsimd-lstm.wasm.js',
  'eng.traineddata.gz': '@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz',
  'hin.traineddata.gz': '@tesseract.js-data/hin/4.0.0_best_int/hin.traineddata.gz',
};
const ocrPath = (name) => new URL(`./node_modules/${OCR_FILES[name]}`, import.meta.url);

function ocrAssets() {
  return {
    name: 'jansathi-ocr',
    generateBundle() {
      for (const name of Object.keys(OCR_FILES)) this.emitFile({ type: 'asset', fileName: `ocr/${name}`, source: readFileSync(ocrPath(name)) });
    },
    configureServer(server) {
      server.middlewares.use('/ocr', (req, res, next) => {
        const name = req.url.split('?')[0].slice(1);
        if (!OCR_FILES[name] || !existsSync(ocrPath(name))) return next();
        createReadStream(ocrPath(name)).pipe(res);
      });
    },
  };
}

// Relative base: the built PWA works from any static host path and from the Spring Boot jar.
// In dev, /api is proxied to the Spring Boot API (npm run start:backend); when it is not
// running, the app falls back to the static bundle in public/data.
export default defineConfig(({ command }) => ({
  base: './',
  plugins: [react(), serviceWorker(), ocrAssets()],
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
