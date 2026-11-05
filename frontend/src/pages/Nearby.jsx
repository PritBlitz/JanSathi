// Nearby help (brief §6.8): OpenStreetMap and Leaflet. Nothing leaves the device until the person
// taps the button. The list is the main view, so it works with a screen reader and read-aloud;
// the map beside it is extra. Places are community-mapped: the page says so and links the official
// CSC locator.
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../components/Icon.jsx';
import { PageHead } from '../components/SchemeParts.jsx';
import { Warn } from '../components/Trust.jsx';
import { useI18n } from '../lib/i18n-context.jsx';
import { useSay } from '../lib/use-speech.js';
import { CSC_LOCATOR_URL, TILE_URL, findByPincode, findNearby, validPincode } from '../lib/places.js';

function Map({ from, places, label }) {
  const box = useRef(null);
  useEffect(() => {
    let map;
    let gone = false;
    Promise.all([import('leaflet'), import('leaflet/dist/leaflet.css?inline')]).then(([{ default: L }, css]) => {
      if (gone || !box.current) return;
      if (!document.getElementById('leaflet-css')) {
        const style = document.createElement('style');
        style.id = 'leaflet-css';
        style.textContent = css.default;
        document.head.append(style);
      }
      map = L.map(box.current).setView([from.lat, from.lon], 12);
      L.tileLayer(TILE_URL, { maxZoom: 18, attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' }).addTo(map);
      for (const p of places) {
        // Names come from a public map: an element with textContent, because Leaflet treats strings as HTML.
        const label = document.createElement('span');
        label.textContent = p.name;
        L.circleMarker([p.lat, p.lon], { radius: 7, weight: 2 }).addTo(map).bindPopup(label);
      }
    });
    return () => {
      gone = true;
      map?.remove();
    };
  }, [from, places]);
  return <div ref={box} className="nearby-map" role="img" aria-label={label} />;
}

export function Nearby() {
  const { t, formatNumber } = useI18n();
  const say = useSay();
  const [state, setState] = useState('idle'); // idle | locating | done | denied | failed
  const [found, setFound] = useState(null);
  const [pin, setPin] = useState('');

  function locate() {
    if (!navigator.geolocation) return setState('denied');
    setState('locating');
    navigator.geolocation.getCurrentPosition(
      async ({ coords }) => {
        try {
          setFound(await findNearby({ lat: coords.latitude, lon: coords.longitude }));
          setState('done');
        } catch {
          setState('failed');
        }
      },
      () => setState('denied'),
      { maximumAge: 600000, timeout: 15000 },
    );
  }

  async function byPin(e) {
    e.preventDefault();
    if (!validPincode(pin)) return setState('bad_pin');
    setState('locating');
    try {
      const area = await findByPincode(pin);
      setFound(area);
      setState(area ? 'done' : 'no_pin');
    } catch {
      setState('failed');
    }
  }

  const places = found?.places || [];
  const km = (p) => t('nearby.km', { n: formatNumber(Math.round(p.km * 10) / 10) });

  return (
    <div className="page">
      <PageHead kicker={t('nav.help')} title={t('help.title')} lead={t('nearby.lead')}>
        <form className="search-form" onSubmit={byPin}>
          <label className="visually-hidden" htmlFor="pin-input">
            {t('nearby.pin_label')}
          </label>
          <input id="pin-input" inputMode="numeric" maxLength={6} autoComplete="postal-code" value={pin} placeholder={t('nearby.pin_label')} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
          <button className="btn btn-primary" type="submit" disabled={state === 'locating'}>
            <Icon name="search" size={18} /> {t('nearby.pin_find')}
          </button>
        </form>
        <div className="actions">
          <button className="btn btn-outline" type="button" disabled={state === 'locating'} onClick={locate}>
            {t(state === 'locating' ? 'nearby.locating' : 'nearby.find')}
          </button>
          <a className="btn btn-outline" href={CSC_LOCATOR_URL} rel="noopener noreferrer" target="_blank">
            {t('nearby.official')} <Icon name="arrowUpRight" size={18} />
          </a>
        </div>
      </PageHead>
      <p className="small muted">{t('nearby.privacy')}</p>
      <div role="status" aria-live="polite">
        {state === 'denied' && <Warn>{t('nearby.denied')}</Warn>}
        {state === 'bad_pin' && <Warn>{t('nearby.pin_invalid')}</Warn>}
        {state === 'no_pin' && <Warn>{t('nearby.pin_none')}</Warn>}
        {state === 'failed' && <Warn>{t('nearby.failed')}</Warn>}
        {state === 'done' && !places.length && !found?.offices && <Warn>{t('nearby.none')}</Warn>}
      </div>
      {found?.offices && (
        <section className="post-offices" aria-labelledby="po-title">
          <h2 className="group-title" id="po-title">
            {t('nearby.post_offices', { district: found.district, state: found.state })}
          </h2>
          <ul className="nearby-list">
            {found.offices.map((o) => (
              <li key={o.name}>
                <strong>{o.name}</strong>
                <span className="small muted">{o.type}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {places.length > 0 && (
        <>
          <Warn>{t('nearby.caveat')}</Warn>
          <div className="actions">
            <button className="btn btn-outline" type="button" onClick={() => say(places.slice(0, 5).map((p) => `${p.name}, ${km(p)}`).join('. '))}>
              <Icon name="speaker" size={18} /> {t('nearby.read')}
            </button>
          </div>
          <div className="nearby">
            <Map from={found.from} places={places} label={t('nearby.map_label', { n: formatNumber(places.length) })} />
            <ol className="nearby-list">
              {places.map((p) => (
                <li key={p.id}>
                  <strong>{p.name}</strong>
                  <span className="small muted">
                    {t(`nearby.kind_${p.kind}`)} · {km(p)}
                  </span>
                  <a href={`https://www.openstreetmap.org/directions?to=${p.lat},${p.lon}`} rel="noopener noreferrer" target="_blank">
                    {t('nearby.directions')} <Icon name="arrowUpRight" size={16} />
                  </a>
                </li>
              ))}
            </ol>
          </div>
        </>
      )}
    </div>
  );
}
