// Line icons (24 px grid, 2 px stroke, round joins: DESIGN §8). Always decorative: the
// button or link around an icon carries the text label or aria-label.
const PATHS = {
  arrow: 'M5 12h14M13 6l6 6-6 6',
  arrowUpRight: 'M7 17L17 7M8 7h9v9',
  arrowLeft: 'M19 12H5M11 18l-6-6 6-6',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  speaker: 'M4 9v6h4l5 4V5L8 9H4zM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11',
  stop: 'M7 7h10v10H7z',
  globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z',
  menu: 'M4 7h16M4 12h16M4 17h16',
  close: 'M6 6l12 12M18 6L6 18',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z',
  auto: 'M12 3a9 9 0 1 0 0 18V3z M12 3a9 9 0 0 1 0 18',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  question: 'M9.2 9a3 3 0 1 1 4.3 2.7c-.9.4-1.5 1.2-1.5 2.1v.7M12 17.5v.5',
  dash: 'M7 12h10',
  shield: 'M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6l7-3z',
  doc: 'M7 3h7l5 5v13H7V3zM14 3v5h5M10 13h6M10 17h6',
  warn: 'M12 4l9 16H3l9-16zM12 10v4M12 17v.5',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4',
  pause: 'M8 5v14M16 5v14',
  play: 'M7 5l12 7-12 7V5z',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
};

export function Icon({ name, size = 22, className = '' }) {
  return (
    <svg className={`icon ${className}`} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={PATHS[name]} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** The waveform logo mark (DESIGN §8): stands for voice. */
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 28 28" aria-hidden="true" focusable="false">
      <rect x="1" y="10" width="4" height="8" rx="2" />
      <rect x="7" y="5" width="4" height="18" rx="2" />
      <rect x="13" y="1" width="4" height="26" rx="2" />
      <rect x="19" y="7" width="4" height="14" rx="2" />
      <rect x="25" y="11" width="2.5" height="6" rx="1.25" />
    </svg>
  );
}
