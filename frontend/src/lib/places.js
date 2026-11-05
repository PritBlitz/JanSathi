// Nearby help, behind one small provider (CLAUDE.md: every external provider sits behind an
// interface). Today's provider is OpenStreetMap through Overpass. Places are community-mapped, not
// an official list, and the app says so. The location is rounded to about 1 km before it leaves
// the device, and only after the person asks. Searching by PIN code needs no device location at all;
// India Post's public directory then gives official post offices for it.

const OVERPASS = 'https://overpass-api.de/api/interpreter';
export const RADIUS_METRES = 15000;
export const CSC_LOCATOR_URL = 'https://locator.csccloud.in/';
export const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const POSTAL_API = 'https://api.postalpincode.in/pincode/';
const NOMINATIM = 'https://nominatim.openstreetmap.org/search';
const CSC_NAME = /common service cent|\bcsc\b|jan seva kendra|जन सेवा केंद्र|सामान्य सेवा केंद्र/i;

export const roundCoord = (n) => Math.round(n * 100) / 100;

export function distanceKm(a, b) {
  const rad = (d) => (d * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(h));
}

export function overpassQuery({ lat, lon }, radius = RADIUS_METRES) {
  const near = `(around:${radius},${lat},${lon})`;
  return `[out:json][timeout:20];(nwr${near}["amenity"="townhall"];nwr${near}["office"="government"];nwr${near}["amenity"="post_office"];nwr${near}["name"~"Common Service Cent|CSC|Jan Seva Kendra",i];);out center 80;`;
}

export function kindOf(tags) {
  if (CSC_NAME.test(tags.name || '')) return 'csc';
  if (tags.amenity === 'post_office') return 'post_office';
  return 'office';
}

const KIND_ORDER = { csc: 0, office: 1, post_office: 2 };

/** Named places, CSCs first and then the closest, at most `limit`. */
export function parsePlaces(json, from, limit = 25) {
  return (json.elements || [])
    .map((e) => {
      const lat = e.lat ?? e.center?.lat;
      const lon = e.lon ?? e.center?.lon;
      const tags = e.tags || {};
      if (lat === undefined || lon === undefined || !tags.name) return null;
      return { id: `${e.type}/${e.id}`, name: tags.name, kind: kindOf(tags), lat, lon, km: distanceKm(from, { lat, lon }) };
    })
    .filter(Boolean)
    .sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind] || a.km - b.km)
    .slice(0, limit);
}

export const validPincode = (s) => /^[1-9]\d{5}$/.test(s);

/** India Post's own directory for a PIN code. */
export function parsePostOffices(json) {
  const first = Array.isArray(json) ? json[0] : null;
  if (first?.Status !== 'Success' || !first.PostOffice?.length) return null;
  const offices = first.PostOffice;
  return { district: offices[0].District, state: offices[0].State, offices: offices.map((o) => ({ name: o.Name, type: o.BranchType, block: o.Block })) };
}

/** Looks a PIN code up, then finds mapped places around it. The map search may fail; the directory still shows. */
export async function findByPincode(pin) {
  const res = await fetch(POSTAL_API + pin);
  if (!res.ok) throw new Error(`India Post: HTTP ${res.status}`);
  const area = parsePostOffices(await res.json());
  if (!area) return null;
  let found = { from: null, places: [] };
  try {
    const geo = await (await fetch(`${NOMINATIM}?postalcode=${pin}&country=india&format=json&limit=1`)).json();
    if (geo[0]) found = await findNearby({ lat: Number(geo[0].lat), lon: Number(geo[0].lon) });
  } catch {
    /* the post offices alone still help */
  }
  return { ...area, ...found };
}

export async function findNearby(position) {
  const from = { lat: roundCoord(position.lat), lon: roundCoord(position.lon) };
  const res = await fetch(OVERPASS, { method: 'POST', body: new URLSearchParams({ data: overpassQuery(from) }) });
  if (!res.ok) throw new Error(`Overpass: HTTP ${res.status}`);
  return { from, places: parsePlaces(await res.json(), from) };
}
