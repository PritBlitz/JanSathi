import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App.jsx';
import { AppProvider, KEYS } from './lib/app-state.jsx';
import { SessionProvider } from './lib/session.jsx';
import { I18nProvider, loadAll } from './lib/i18n-context.jsx';
import { detectLang, store } from './lib/i18n.js';
import { loadBundle } from './lib/data.js';
import { applyTheme } from './components/Chrome.jsx';
import './styles/tokens.css';
import './styles/base.css';
import './styles/home.css';
import './styles/pages.css';

async function start() {
  applyTheme(store.get(KEYS.theme));
  if (store.get(KEYS.motion) === '1') document.documentElement.classList.add('reduce-motion');
  // Entrance motion only if the script arrives quickly; on a slow network the shell has already
  // been read, and animating it in again would just make it jump.
  if (performance.now() < 1200) document.documentElement.classList.add('anim');

  const wantPreview = new URLSearchParams(location.search).has('preview');
  const [i18n, data] = await Promise.all([loadAll(detectLang()), loadBundle({ wantPreview })]);
  document.getElementById('boot')?.remove();

  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <I18nProvider initial={i18n}>
        <SessionProvider>
          <AppProvider data={data}>
            <App />
          </AppProvider>
        </SessionProvider>
      </I18nProvider>
    </StrictMode>,
  );

  if ('serviceWorker' in navigator && import.meta.env.PROD) navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {});
}

start().catch((err) => {
  console.error(err);
  const boot = document.getElementById('boot');
  if (boot) boot.dataset.state = 'error';
});
