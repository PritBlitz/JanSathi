// Decides, from text read off a photo, whether it looks like the document asked for and whether it
// is readable (brief §6.4). This never checks that a document is genuine, and no text read from
// the photo is shown, stored or sent anywhere: only the verdict leaves this function.

// Words printed on each kind of document, in English and Hindi. Lower case. Documents with no
// reliable printed words (a passport photo, for example) are left out: the check is not offered.
export const DOCUMENT_WORDS = {
  aadhaar: ['aadhaar', 'unique identification', 'आधार', 'भारतीय विशिष्ट पहचान'],
  bank_account: ['passbook', 'ifsc', 'account no', 'savings account', 'पासबुक', 'बैंक'],
  ration_card: ['ration card', 'food and civil', 'public distribution', 'राशन कार्ड', 'खाद्य'],
  age_proof: ['date of birth', 'birth certificate', 'dob', 'जन्म तिथि', 'जन्म प्रमाण'],
  income_certificate: ['income certificate', 'annual income', 'आय प्रमाण', 'वार्षिक आय'],
  caste_certificate: ['caste certificate', 'scheduled caste', 'scheduled tribe', 'other backward', 'जाति प्रमाण', 'अनुसूचित'],
  disability_certificate: ['disability', 'divyang', 'udid', 'दिव्यांग', 'विकलांग', 'निःशक्तता'],
  land_records: ['khasra', 'khatauni', 'jamabandi', 'land record', 'record of rights', 'खसरा', 'खतौनी', 'जमाबंदी'],
  spouse_death_certificate: ['death certificate', 'मृत्यु प्रमाण'],
  death_certificate: ['death certificate', 'मृत्यु प्रमाण'],
  birth_certificate_child: ['birth certificate', 'जन्म प्रमाण'],
  mcp_card: ['mother and child', 'mcp card', 'immunization', 'जच्चा', 'मातृ एवं शिशु', 'टीकाकरण'],
  marksheet: ['marksheet', 'mark sheet', 'statement of marks', 'marks obtained', 'अंक पत्र', 'अंकपत्र', 'प्राप्तांक'],
  fee_receipt: ['fee receipt', 'tuition fee', 'receipt no', 'शुल्क रसीद', 'फीस'],
  sowing_certificate: ['sowing', 'बुवाई'],
  job_card: ['job card', 'mgnrega', 'nrega', 'gramin rozgar', 'जॉब कार्ड', 'रोजगार गारंटी'],
};

export const MIN_CONFIDENCE = 45; // Tesseract's mean word confidence, 0-100
export const MIN_WORDS = 5;

export const canCheck = (code) => Boolean(DOCUMENT_WORDS[code]);

/**
 * @returns {'not_readable' | 'looks_right' | 'cannot_tell'} never a statement that the document is valid.
 */
export function judgeDocument(code, text, confidence) {
  const clean = String(text || '').toLowerCase().replace(/\s+/g, ' ');
  const words = clean.split(' ').filter((w) => w.length > 1).length;
  if (words < MIN_WORDS || confidence < MIN_CONFIDENCE) return 'not_readable';
  return (DOCUMENT_WORDS[code] || []).some((w) => clean.includes(w)) ? 'looks_right' : 'cannot_tell';
}
