// Unit tests for the copy lint (tools/lint-copy.mjs). Run with: node --test tools/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { lintText } from './lint-copy.mjs';

const banned = [
  ['en', 'You are eligible for this pension'],
  ['en', "you're eligible"],
  ['en', 'You will get ₹6,000'],
  ['en', 'Approval is guaranteed'],
  ['hi', 'आप पात्र हैं'],
  ['hi', 'आपको मिलेगा ₹6,000'],
  ['hi', 'सौ प्रतिशत गारंटी'],
  ['bn', 'আপনি যোগ্য।'],
  ['pa', 'ਤੁਸੀਂ ਯੋਗ ਹੋ।'],
  ['te', 'మీరు అర్హులు'],
  ['ml', 'നിങ്ങൾ അർഹനാണ്'],
];
const allowed = [
  ['en', 'You may be eligible'],
  ['en', 'Viksit Bharat – Guarantee for Rozgar and Ajeevika Mission (Gramin) Act'],
  ['en', 'Gramin Rozgar Guarantee Card'],
  ['hi', 'आप पात्र हो सकते हैं'],
  ['hi', 'ग्रामीण रोज़गार गारंटी कार्ड'],
  ['hi', 'ग्रामीण रोज़गार गारंटी कार्ड'],
  ['bn', 'আপনি যোগ্য হতে পারেন'],
  ['bn', 'গ্রামীণ রোজগার গ্যারান্টি কার্ড'],
  ['pa', 'ਤੁਸੀਂ ਯੋਗ ਹੋ ਸਕਦੇ ਹੋ'],
  ['pa', 'ਗ੍ਰਾਮੀਣ ਰੋਜ਼ਗਾਰ ਗਾਰੰਟੀ ਕਾਰਡ'],
  ['te', 'గ్రామీణ రోజ్‌గార్ గ్యారంటీ కార్డు'],
  ['ta', 'கிராமீண் ரோஜ்கார் கேரண்டி அட்டை'],
];

for (const [lang, text] of banned) {
  test(`blocks (${lang}): ${text}`, () => assert.notDeepEqual(lintText(text, [lang]), []));
}
for (const [lang, text] of allowed) {
  test(`allows (${lang}): ${text}`, () => assert.deepEqual(lintText(text, ['en', lang]), []));
}
