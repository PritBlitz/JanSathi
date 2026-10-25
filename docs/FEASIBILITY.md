# Feasibility assessment

> **Date:** 2026-10-25. **Scope:** whether JanSathi as described in [PROJECT_BRIEF.md](PROJECT_BRIEF.md) can be built and run, based on what this repo already contains. Figures come from the repo unless marked otherwise.

## Verdict

**Feasible as an MVP, with one hard constraint: content review, not code.** The engineering risks are low and mostly retired: the rules engine runs on the device and the server with identical results, the PWA works offline, and the API is built. What decides whether JanSathi can launch and stay trustworthy is a steady supply of human reviewers who verify each scheme against its official source, plus native speakers who review the translations. Without them, the app correctly stays in preview mode forever.

## 1. Technical feasibility: high

| Piece | State (2026-10-25) | Evidence |
|---|---|---|
| Three-state rules engine | Built twice (JS on the device, Java on the server), identical results | 233 shared fixtures pass in both: `npm test`, `npm run test:backend` |
| Frontend | React 19 + Vite PWA, 10 UI languages, voice in and out, offline service worker | `npm run smoke` drives Chrome through every flow in 10 locales |
| API | Spring Boot 4.1: schemes, match, rule bundle (ETag), checklist, search, assistant with an optional LLM and answer validation, reports with moderation, OpenAPI; PostgreSQL via Flyway; one Docker image | `backend/src/test/java/org/jansathi/`, `docker compose up` |
| Knowledge base | 28 central schemes, 42 profile fields, 17 document types, each rule tied to a source clause | `npm run check` |
| Source re-check | `ingest/recheck.py` hashes official pages and flags changes | not yet scheduled |

Nothing in the core product needs research or unproven technology. Eligibility is deterministic and testable. The LLM is optional: the product is useful with the rules engine and sourced pages alone; with no model configured, `/ask` answers with cited passages and no generated text, and with one, every answer is checked for citations and banned promises before anyone sees it. Retrieval passes its evaluation set (70 questions in English and Hindi), but that set is small and was used to tune retrieval, so it needs held-out questions.

## 2. Content feasibility: the real constraint

- **Verification is manual by design.** 0 of 28 schemes are published, because only a human reviewer may set `verified_at` (two approvals, see [REVIEW.md](REVIEW.md)). At an estimated 1–2 hours per scheme (reading guidelines, checking every rule and its clause), the 28 drafts are roughly one to two person-weeks of reviewer time. *Estimate, not measured.*
- **Schemes change often.** In the month before this assessment alone, the repo recorded the NSAP rename and its new disability criteria, MGNREGA's replacement by VB-G RAM G, and expired guideline periods for three schemes (see [HANDOVER.md](../HANDOVER.md)). Re-verification is ongoing work, not a one-off cost. The re-check job finds changes; a person must still read them.
- **State schemes multiply the work.** The MVP needs one state plus central schemes. Each additional state is a new body of guidelines, often only in the state language. Scale states one at a time, with reviewers who read that language.
- **Some official hosts block automated fetching** (pib.gov.in, labour.gov.in, and others listed in the handover). Those sources need manual fetch and hashing. This is workable but slows re-checks.

## 3. Language and voice: feasible, with known gaps

- **UI text:** 10 locales exist with identical keys, but 9 are machine-written and 30 newer keys are English placeholders (`frontend/public/locales/review-status.json`). Native-speaker review is required before launch in each language.
- **Scheme content:** English and Hindi today. Other languages fall back to English with a visible note. IndicTrans2 plus human review is planned (Phase 6).
- **Speech:** the browser Web Speech API is free, but voice coverage for Indian languages varies by device and is weak on desktop. The app detects a missing voice and says so instead of reading in the wrong language. Reliable coverage needs the planned server fallback (IndicWhisper, Indic-TTS), which needs a machine with at least 4 GB RAM, the main running cost in the plan.

## 4. Users and devices: feasible, watch the budget

- The audience uses low-end Android phones on slow networks. The rules engine and rule bundle run offline, so matching never waits on the network.
- **React costs bytes.** The maintainer chose React (2026-10-25). The first-load JavaScript is about 81 KB gzipped, of which React and React DOM are about 58 KB, against the 60 KB budget in [DESIGN.md §10](../DESIGN.md#10-performance-budget). Lighthouse on the mobile profile still scores performance 97 (LCP 1.7 s, TBT 170 ms), because the brand word paints before the app and pages load lazily. If a real low-end phone shows a problem, `preact/compat` can replace React at build time (an alias in `vite.config.js`, no code changes) and bring the total to roughly 40 KB. *Size of the Preact build is an estimate.*

## 5. Cost: low

Everything in the MVP runs on free tiers or a small server: static PWA hosting, a 512 MB–1 GB container for the API, Postgres when it is added, GitHub Actions for re-checks. LLM and server-side speech are optional and can stay off. Free-tier limits change, which is why every provider sits behind an interface ([ARCHITECTURE.md §6](ARCHITECTURE.md#6-provider-interfaces)).

## 6. Legal, trust and positioning

- **Not a government service.** No emblem, no ministry branding, and the disclaimer on every page. This avoids misleading users, and the restricted use of state emblems.
- **Personal data:** the profile stays on the device, the API rejects unknown fields, and reports strip long digit runs (Aadhaar, bank, phone). Align the volunteer portal (Phase 5) with the DPDP Act 2023 before building it.
- **Overlap with the official portal.** The Government of India runs myScheme (myscheme.gov.in), a national scheme discovery portal. JanSathi must be clearly different, not a copy: voice-first use for low-literacy users, regional languages, the rule-by-rule "why", offline use, document checklists and assisted help. Using myScheme data needs permission; the brief already says to prefer official open data where permitted.

## 7. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| No reviewers, so nothing is ever published | High | High | Recruit 2–3 reviewers (law or social-work students, an NGO partner) before more engineering; preview mode keeps the app honest meanwhile |
| A rule is wrong and someone is wrongly told a scheme may not fit | Medium | High | "Not eligible" is never shown for `uncertain` rules; every result shows its trace and source; "report outdated info" |
| Source changes go unnoticed | Medium | High | Schedule `recheck.py` daily in CI; `reverify_days` per scheme forces re-review |
| Translations mislead | Medium | Medium | Native review gate per locale; placeholders tracked in `review-status.json` |
| Browser voice missing for a language | High on desktop | Medium | Clear notice, typing fallback, server speech in Phase 6 |
| JS budget exceeded on low-end phones | Medium | Medium | Measure with Lighthouse; Preact alias if needed |

## 8. Recommendation

Go ahead, in this order:

1. **People before features:** find the reviewers and one partner NGO or CSC.
2. Publish the first verified schemes so the app leaves preview mode.
3. Choose the MVP state and add its schemes with a reviewer who reads its language.
4. Schedule the re-check job and wire `npm run check`, the backend tests and the smoke test into CI.
5. Only then: the LLM explanation step, reminders and the volunteer portal.
