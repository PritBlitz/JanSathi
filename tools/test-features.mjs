// Unit tests for the pure logic behind deadlines, relations, reminders, tracking, the photo check
// and nearby help. The schemes here are test fixtures for the logic, not scheme content.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanApplication, daysBetween, deadlinesFor, missingDocuments, relationsAmong, reminderDue } from '../frontend/src/lib/schedule.js';
import { canCheck, judgeDocument } from '../frontend/src/lib/ocr-check.js';
import { distanceKm, kindOf, overpassQuery, parsePlaces, parsePostOffices, roundCoord, validPincode } from '../frontend/src/lib/places.js';
import { applyTranslations, isMachineTranslated } from '../frontend/src/lib/translated.js';

const today = '2026-11-04';

test('deadlines: closed ones and other states drop out, soonest first', () => {
  const s = { deadlines: [
    { closes_at: '2026-12-01' },
    { closes_at: '2026-11-10', state_code: 'UP' },
    { closes_at: '2026-11-03' },
    { closes_at: '2026-11-04' },
    { closes_at: '2026-11-05', state_code: 'TN' },
  ] };
  assert.deepEqual(deadlinesFor(s, 'UP', today).map((d) => d.closes_at), ['2026-11-04', '2026-11-10', '2026-12-01']);
  assert.equal(deadlinesFor(s, undefined, today).length, 4);
  assert.deepEqual(deadlinesFor({}, 'UP', today), []);
});

test('days between crosses months', () => assert.equal(daysBetween('2026-10-30', '2026-11-04'), 5));

const schemes = [
  { slug: 'a', sources: [{ id: 's', publisher: 'P', url: 'u' }], relations: [{ kind: 'excludes', with: 'b', source: 's' }, { kind: 'stacks_with', with: 'c', source: 's' }, { kind: 'excludes', with: 'd', source: 's' }] },
  { slug: 'b', sources: [{ id: 's' }], relations: [{ kind: 'excludes', with: 'a', source: 's' }] },
  { slug: 'c', sources: [] },
  { slug: 'd', sources: [] },
];
const results = [{ scheme: 'a', result: 'LIKELY_ELIGIBLE' }, { scheme: 'b', result: 'LIKELY_ELIGIBLE' }, { scheme: 'c', result: 'LIKELY_ELIGIBLE' }, { scheme: 'd', result: 'NOT_ELIGIBLE' }];

test('relations: only between likely schemes, each pair once, conflicts first', () => {
  const notes = relationsAmong(schemes, results);
  assert.deepEqual(notes.map((n) => [n.kind, n.a, n.b]), [['excludes', 'a', 'b'], ['stacks_with', 'a', 'c']]);
  assert.equal(notes[0].source.publisher, 'P');
});

const docScheme = { slug: 'x', documents: [{ code: 'photo', mandatory: true }, { code: 'marksheet', mandatory: true }, { code: 'extra', mandatory: false }], deadlines: [{ closes_at: '2026-11-10' }] };
const schemeBy = (slug) => (slug === 'x' ? docScheme : undefined);

test('missing documents counts mandatory ones not marked as have', () => {
  assert.deepEqual(missingDocuments({ schemes: ['x', 'gone'], have: { photo: true } }, schemeBy), ['marksheet']);
});

test('reminders: off, too soon, nothing to say, and due', () => {
  const ctx = { checklist: { schemes: ['x'], have: {} }, schemeBy, state: null, today };
  assert.equal(reminderDue({ enabled: false }, ctx), null);
  assert.equal(reminderDue({ enabled: true, last: '2026-11-01' }, ctx), null);
  const due = reminderDue({ enabled: true, last: '2026-10-28' }, ctx);
  assert.equal(due.missing, 2);
  assert.deepEqual(due.soon, [{ slug: 'x', closes_at: '2026-11-10' }]);
  const none = { ...ctx, checklist: { schemes: [], have: {} } };
  assert.equal(reminderDue({ enabled: true, last: null }, none), null);
});

test('tracking keeps a valid status and removes personal numbers from the reason', () => {
  assert.equal(cleanApplication({ status: 'nonsense' }, today), null);
  assert.deepEqual(cleanApplication({ status: 'applied', reason: 'ignored' }, today), { status: 'applied', reason: null, updated: today });
  const r = cleanApplication({ status: 'rejected', reason: 'Wrong account 1234 5678 9012 given' }, today);
  assert.equal(r.reason.includes('1234'), false);
  assert.equal(cleanApplication({ status: 'rejected', reason: 'x'.repeat(400) }, today).reason.length, 300);
});

const printed = 'Government of Uttar Pradesh Income Certificate this is to certify that the annual income of the family';

test('photo check: readable and matching, readable but unclear, unreadable', () => {
  assert.equal(judgeDocument('income_certificate', printed, 80), 'looks_right');
  assert.equal(judgeDocument('caste_certificate', printed, 80), 'cannot_tell');
  assert.equal(judgeDocument('income_certificate', printed, 20), 'not_readable');
  assert.equal(judgeDocument('income_certificate', 'income', 90), 'not_readable');
  assert.equal(judgeDocument('income_certificate', 'भारत सरकार उत्तर प्रदेश आय प्रमाण पत्र जिला लखनऊ', 70), 'looks_right');
});

test('photo check is not offered where no printed words identify a document', () => {
  assert.equal(canCheck('photo'), false);
  assert.equal(canCheck('aadhaar'), true);
});

test('nearby: location is rounded before it is used', () => {
  assert.equal(roundCoord(28.61394), 28.61);
  assert.equal(roundCoord(77.20902), 77.21);
  assert.ok(overpassQuery({ lat: 28.61, lon: 77.21 }).includes('around:15000,28.61,77.21'));
});

test('nearby: distance, kinds, and CSCs before closer offices', () => {
  assert.ok(Math.abs(distanceKm({ lat: 28.61, lon: 77.21 }, { lat: 28.71, lon: 77.21 }) - 11.1) < 0.2);
  assert.equal(kindOf({ name: 'Jan Seva Kendra Rampur' }), 'csc');
  assert.equal(kindOf({ name: 'Rampur SO', amenity: 'post_office' }), 'post_office');
  assert.equal(kindOf({ name: 'Tehsil office', office: 'government' }), 'office');
  const from = { lat: 28.61, lon: 77.21 };
  const places = parsePlaces({ elements: [
    { type: 'node', id: 1, lat: 28.611, lon: 77.21, tags: { name: 'Near PO', amenity: 'post_office' } },
    { type: 'way', id: 2, center: { lat: 28.7, lon: 77.21 }, tags: { name: 'Far CSC Centre' } },
    { type: 'node', id: 3, lat: 28.6, lon: 77.2, tags: { amenity: 'townhall' } },
  ] }, from);
  assert.deepEqual(places.map((p) => p.name), ['Far CSC Centre', 'Near PO']);
});

test('translations change display text only, and fall back to English', () => {
  const bundle = {
    schemes: [{ slug: 'x', content: { en: { name: 'N', summary: 'S', benefit: 'B', how_to_apply: 'H' }, hi: { name: 'हि' } }, rules: [{ code: 'r', logic: { '==': [1, 1] }, text: { en: 'R' } }] }],
    documents: { documents: { d: { name: { en: 'D' }, how_to: { en: 'How' } } } },
  };
  const out = applyTranslations(bundle, { schemes: { x: { name: 'নাম', rules: { r: 'নিয়ম' } } }, documents: { d: { name: 'নথি' } } }, 'bn');
  assert.equal(out.schemes[0].content.bn.name, 'নাম');
  assert.equal(out.schemes[0].content.bn.summary, 'S');
  assert.equal(isMachineTranslated(out.schemes[0], 'bn'), true);
  assert.deepEqual(out.schemes[0].rules[0].logic, bundle.schemes[0].rules[0].logic);
  assert.equal(out.schemes[0].rules[0].text.bn, 'নিয়ম');
  assert.equal(out.documents.documents.d.name.bn, 'নথি');
  assert.equal(bundle.schemes[0].content.bn, undefined);
  assert.equal(applyTranslations(bundle, { schemes: { x: { name: 'bad' } } }, 'hi').schemes[0].content.hi.name, 'हि');
  assert.equal(applyTranslations(bundle, null, 'bn'), bundle);
});

test('pincode: valid forms and India Post replies', () => {
  assert.equal(validPincode('226001'), true);
  assert.equal(validPincode('026001'), false);
  assert.equal(validPincode('22600'), false);
  assert.equal(parsePostOffices([{ Status: 'Error', PostOffice: null }]), null);
  const a = parsePostOffices([{ Status: 'Success', PostOffice: [{ Name: 'A P Sabha', BranchType: 'Sub Post Office', District: 'Lucknow', State: 'Uttar Pradesh', Block: 'Lucknow' }] }]);
  assert.equal(a.district, 'Lucknow');
  assert.equal(a.offices[0].name, 'A P Sabha');
});
