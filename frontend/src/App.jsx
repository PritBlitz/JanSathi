// Router and page frame. Each route remounts its page, stops speech and moves focus to <main>.
// Home is in the main bundle; every other page loads on first visit (the service worker
// precaches them all, so they still work offline).
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Footer, Nav, Notice, PreviewBanner } from './components/Chrome.jsx';
import { useI18n } from './lib/i18n-context.jsx';
import { useApp } from './lib/app-state.jsx';
import { useRoute } from './lib/router.js';
import { stop } from './lib/speech.js';
import { useReminders } from './lib/reminders.js';
import { Home } from './pages/Home.jsx';

const named = (load, name) => lazy(() => load().then((m) => ({ default: m[name] })));
const Question = named(() => import('./pages/Question.jsx'), 'Question');
const Results = named(() => import('./pages/Results.jsx'), 'Results');
const Scheme = named(() => import('./pages/Scheme.jsx'), 'Scheme');
const Schemes = named(() => import('./pages/Pages.jsx'), 'Schemes');
const Checklist = named(() => import('./pages/Pages.jsx'), 'Checklist');
const Search = named(() => import('./pages/Pages.jsx'), 'Search');
const Help = named(() => import('./pages/Nearby.jsx'), 'Nearby');
const Admin = named(() => import('./pages/Admin.jsx'), 'Admin');
const Grievance = named(() => import('./pages/Grievance.jsx'), 'Grievance');
const Applications = named(() => import('./pages/Applications.jsx'), 'Applications');
const Privacy = named(() => import('./pages/Pages.jsx'), 'Privacy');
const Family = named(() => import('./pages/Family.jsx'), 'Family');
const Slip = named(() => import('./pages/Slip.jsx'), 'Slip');
const Changes = named(() => import('./pages/Changes.jsx'), 'Changes');
const AskSheet = named(() => import('./components/AskSheet.jsx'), 'AskSheet');
const SignIn = named(() => import('./pages/SignIn.jsx'), 'SignIn');
const Guide = named(() => import('./pages/Guide.jsx'), 'Guide');
const Account = named(() => import('./pages/Account.jsx'), 'Account');
const Helper = named(() => import('./pages/Helper.jsx'), 'Helper');

function Page({ route, onAsk }) {
  switch (route.name) {
    case 'check':
      return <Question />;
    case 'results':
      return <Results />;
    case 'scheme':
      return <Scheme slug={route.arg} />;
    case 'schemes':
      return <Schemes category={route.arg} />;
    case 'checklist':
      return <Checklist />;
    case 'search':
      return <Search query={route.query.get('q') || ''} />;
    case 'family':
      return <Family />;
    case 'slip':
      return <Slip />;
    case 'changes':
      return <Changes />;
    case 'login':
      return <SignIn next={route.query.get('next')} />;
    case 'guide':
      return <Guide />;
    case 'account':
      return <Account />;
    case 'helper':
      return <Helper caseId={route.arg} />;
    case 'grievance':
      return <Grievance />;
    case 'admin':
      return <Admin />;
    case 'applications':
      return <Applications />;
    case 'help':
      return <Help />;
    case 'privacy':
      return <Privacy />;
    default:
      return <Home route={route} onAsk={onAsk} />;
  }
}

export function App() {
  const { t } = useI18n();
  const { preview, showNotice } = useApp();
  const route = useRoute();
  useReminders();
  const [asking, setAsking] = useState(false);
  const main = useRef(null);
  const askLoaded = useRef(false);
  if (asking) askLoaded.current = true;
  const isHome = !route.name || route.name === 'how';
  const key = `${route.name}/${route.arg ?? ''}?${route.query}`;

  useEffect(() => {
    stop();
    if (!isHome) {
      window.scrollTo(0, 0);
      main.current?.focus({ preventScroll: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  useEffect(() => {
    const off = () => showNotice(t('common.offline'));
    window.addEventListener('offline', off);
    return () => window.removeEventListener('offline', off);
  }, [showNotice, t]);

  return (
    <>
      <a className="skip" href="#main" onClick={(e) => (e.preventDefault(), main.current?.focus())}>
        {t('nav.skip')}
      </a>
      <Nav route={route} />
      {preview && <PreviewBanner />}
      <Notice />
      <main id="main" ref={main} tabIndex={-1} className={isHome ? 'is-home' : ''}>
        <Suspense fallback={<div className="page-loading" aria-busy="true" />}>
          <Page key={key} route={route} onAsk={() => setAsking(true)} />
        </Suspense>
      </main>
      {/* Loaded on first use; the sheet then stays mounted. */}
      {(asking || askLoaded.current) && (
        <Suspense fallback={null}>
          <AskSheet open={asking} onClose={() => setAsking(false)} />
        </Suspense>
      )}
      <Footer />
    </>
  );
}
