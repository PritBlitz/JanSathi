// node --test tools/translate-content.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { dueNow, hashOf, numbersOf, plan, problem, sources, termsOf } from './translate-content.mjs';

const scheme = { slug: 'x', content: { en: { name: 'PM-X', summary: 'Pays ₹6,000 a year.' } }, rules: [{ code: 'age', text: { en: 'Age 18 to 40' } }] };
const documents = { aadhaar: { name: { en: 'Aadhaar card' }, how_to: { en: 'Go to a centre.' } } };

test('sources are every English field, by their path in the file', () => {
  assert.deepEqual([...sources([scheme], documents).keys()], ['schemes.x.name', 'schemes.x.summary', 'schemes.x.rules.age', 'documents.aadhaar.name', 'documents.aadhaar.how_to']);
});

test('numbers compare across scripts and grouping, URLs do not count', () => {
  assert.deepEqual(numbersOf('₹2,50,000 in 2026-27 at https://a.gov.in/5.pdf'), ['2026', '250000', '27']);
  assert.deepEqual(numbersOf('২,৫০,০০০ টাকা ২০২৬-২৭'), ['2026', '250000', '27']);
  assert.deepEqual(numbersOf('௧௮ വർഷം ൪൦'), ['18', '40']);
});

test('terms are URLs and acronyms', () => {
  assert.deepEqual(termsOf('Use PM-KISAN at a CSC after e-KYC, see https://pmkisan.gov.in/.'), ['https://pmkisan.gov.in/', 'PM-KISAN', 'CSC', 'e-KYC']);
});

test('a changed number, a lost term or a banned phrase cannot ship', () => {
  assert.equal(problem('Pays ₹6,000.', '₹6,000 দেয়।', 'bn'), null);
  assert.equal(problem('Pays ₹6,000.', '৬ হাজার টাকা দেয়।', 'bn'), 'numbers differ');
  assert.equal(problem('Ask the CSC.', 'সিএসসি-তে জিজ্ঞাসা করুন।', 'bn'), 'lost CSC');
  assert.match(problem('You may be eligible.', 'you are eligible', 'bn'), /banned/);
});

test('only new, changed or missing fields are translated', () => {
  const wanted = sources([scheme], documents);
  const file = {
    schemes: { x: { name: 'PM-X', summary: 'পুরনো ₹5,000', rules: { age: '18 থেকে 40' }, gone: 'x' } },
    documents: {},
    source_hash: {
      'schemes.x.name': hashOf('PM-X'),
      'schemes.x.summary': hashOf('Pays ₹5,000 a year.'),
      'schemes.x.rules.age': hashOf('Age 18 to 40'),
    },
  };
  const { fresh, todo, unsafe } = plan(file, wanted, 'bn');
  assert.deepEqual(todo, ['schemes.x.summary', 'documents.aadhaar.name', 'documents.aadhaar.how_to']);
  assert.deepEqual(fresh.schemes, { x: { name: 'PM-X', rules: { age: '18 থেকে 40' } } });
  assert.equal(fresh.source_hash['schemes.x.summary'], undefined);
  assert.deepEqual(unsafe, []);
});

test('a current field that fails a check is not kept', () => {
  const wanted = new Map([['schemes.x.summary', 'Pays ₹6,000 a year.']]);
  const file = { schemes: { x: { summary: '৭,০০০' } }, documents: {}, source_hash: { 'schemes.x.summary': hashOf('Pays ₹6,000 a year.') } };
  const { todo, unsafe } = plan(file, wanted, 'bn');
  assert.deepEqual(todo, ['schemes.x.summary']);
  assert.equal(unsafe.length, 1);
});

test('drafts wait for review, documents do not', () => {
  const unpublished = new Set(['draft-one']);
  assert.equal(dueNow('schemes.draft-one.name', unpublished), false);
  assert.equal(dueNow('schemes.pm-kisan.name', unpublished), true);
  assert.equal(dueNow('documents.aadhaar.name', unpublished), true);
});
