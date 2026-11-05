// Reads a photo on this device with Tesseract (WebAssembly). The file never leaves the browser, so
// "delete after processing" holds by construction: the worker is ended and nothing is kept. The
// engine and language files come from this site (dist/ocr, see vite.config.js), on first use only.
import { judgeDocument } from './ocr-check.js';

const MAX_BYTES = 12 * 1024 * 1024;

/** @returns {Promise<'too_large' | 'not_readable' | 'looks_right' | 'cannot_tell'>} */
export async function checkDocumentPhoto(file, code) {
  if (file.size > MAX_BYTES) return 'too_large';
  const { createWorker } = await import('tesseract.js');
  const base = new URL(`${import.meta.env.BASE_URL}ocr/`, location.href).href;
  const worker = await createWorker(['eng', 'hin'], 1, {
    workerPath: `${base}worker.min.js`,
    corePath: base,
    langPath: base,
    workerBlobURL: false,
    cacheMethod: 'none',
  });
  try {
    const { data } = await worker.recognize(file);
    return judgeDocument(code, data.text, data.confidence);
  } finally {
    await worker.terminate();
  }
}
