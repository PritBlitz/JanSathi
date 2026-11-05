# Privacy and security

> **Status (updated 2026-11-04):** built: the PWA keeps the household profile, family members (names are whatever the user types, e.g. "Mother"), checklist, language, theme and motion setting in `localStorage` only, and "Delete my data" removes all of it. Matching runs on the device. The API is stateless for profiles: `POST /match` rejects fields not in `fields.json` and nothing is stored or logged; `POST /ask` removes runs of 9+ digits (Aadhaar, bank, phone) before retrieval and before any model call, sends the model only the question and official passages, and never logs questions; `POST /reports` stores scheme, reason, a note with digit runs removed, language and bundle version (no IP, no profile) in the `report` table. The admin API (`/api/v1/admin/**`) needs a per-person staff account, or the bootstrap token while the first accounts are made. Responses carry a Content-Security-Policy, HSTS, `nosniff`, `strict-origin-when-cross-origin`, `DENY` framing and a Permissions-Policy that denies the camera and allows the microphone and location only on our own pages. There is no row-level security: where the sections below describe it, that is the design, not what exists.

> **Accounts, the AI guide and helpers (built 2026-10-26):**
> - **Sign-in** is optional (questionnaire and checklist work without it) and uses a mobile number and a 6-digit one-time code (5 minutes, 5 attempts, 3 codes per number per 15 minutes, 10 per address per hour held in memory). The number is **never stored**: only an HMAC of it (to find the account) and its last 4 digits (so the person recognises it). Sessions are random 256-bit tokens; only their SHA-256 is stored; they last 30 days.
> - **Encryption at rest:** the household (answers, family names, checklist), Saathi conversations and helper notes are encrypted with AES-256-GCM. Keys derive from `JANSATHI_SECRET`, which the database never sees.
> - **Saathi (the AI guide)** gets the person's coarse answers only through its tools, never raw identifiers; runs of 9+ digits are removed from messages before they are saved or sent to the model, and replies are checked for promises and digit runs before anyone sees them.
> - **Helpers** (NGO/CSC staff) are registered by the admin-token holder. They see a case only after the person shares it: a 6-character code (stored as an HMAC) they read out, or an "ask for a helper" request whose queue entry shows only the language. The person chooses what is visible (answers and results, checklist, conversation), for how long (2 hours to 7 days), and can stop it any time. A share belongs to the first helper who opens it. Helpers may enter at most 10 wrong codes per hour. Every helper action (joined, viewed, note, asked the AI, left) is in the person's **audit log**, shown in the app as "who looked at your information", and a view lists the sensitive answers seen by field name, never value. The phone number is never shown to helpers.
> - **Masking (built 2026-11-04):** income, caste, religion, disability and pregnancy answers are hidden from helpers unless the person also grants the `sensitive` scope (`CaseMasking`). Results and Saathi for helpers work from the masked copy, so those rules read "need more information". The conversation needs `sensitive` too, because it is free text. Answer history never goes to a helper.
> - **Reminders (built 2026-11-04, off unless VAPID keys are set):** Web Push for unfinished checklists, once a week. Pushes are empty, so the push service gets no personal data; the endpoint is encrypted and the browser's keys are not kept. One browser per person; removed on turning off, on 404/410 from the push service, and with the account.
> - **Application tracking (built 2026-11-04):** status, an optional reason (digit runs removed) and a date per scheme, in the encrypted household when signed in. Helpers see it only with the `checklist` scope.
> - **Reminder channels (built 2026-11-05, each off until configured):** Web Push, Telegram, SMS and WhatsApp behind one `ReminderChannel` interface; the person chooses. A message never names a scheme, because it crosses third parties and shows on lock screens: it says a checklist is unfinished, or gives a date, and the app shows the rest with the source. A date goes out only if its source is verified and unchanged. The Telegram chat id is encrypted, with a keyed hash to find it for `/stop`; the bot reads only `/start` and `/stop`. For SMS and WhatsApp this is **the one case where a phone number is stored**: encrypted, only the number the person signed in with, only while the channel is on.
> - **Staff accounts (built 2026-11-05):** roles `admin` and `reviewer`; password (PBKDF2) plus an authenticator code; 5 wrong tries lock for 15 minutes; a code works once; sessions last 12 hours. Every admin API call, reads included, is in the insert-only `admin_audit`: who, route, record id and status, never content.
> - **Grievances and reports about helpers (built 2026-11-05):** `POST /grievances` needs no account and links to none. Message and optional contact are encrypted; a contact is a mobile number or e-mail address, nothing else. A person can report any helper who joined one of their cases, even after it ended; the report names the helper, not the person.
> - **Outcome insights (built 2026-11-05):** opt-in on the device, then a counter goes up by one. No row per report, so no identity, address or time exists to protect. Only rejections are shown, and only groups of at least 10. A reason below 10 is left out even inside a larger total.
> - **Under 18 (built 2026-11-05):** see §6.
> - **Deletion:** "Delete my account" removes the account and, by cascade, the household, conversation, shares, notes and audit log.
> - **Differences from §4 below:** helpers are registered by an admin, with no partner-lead verification yet; codes last as long as the share instead of 10 minutes single use (but only the first helper can use one); helpers can read and add notes but can't update a checklist; no separate logged unmasking step, because `sensitive` is granted up front. The threat model is in §4.6, with what is still open.
>
> This document is an engineering design, not legal advice. Have it reviewed by someone qualified in Indian data-protection law before launch.

## 1. Principles

1. **Collect the minimum.** Ask only what a rule needs, and ask it as a band (age band, income band) rather than an exact value.
2. **On the device by default.** The profile, checklist and application tracking live in the browser (`localStorage`, not encrypted: anyone using the same browser profile can read them). Matching can run on the device. Nothing is sent to the server unless a feature needs it and the user has agreed.
3. **Anonymous by default.** No account is needed to discover schemes, ask questions or build a checklist.
4. **Deletable.** One "Delete everything" control wipes local data and any synced data.
5. **No silent sharing.** No third-party analytics, ad trackers or social pixels. Errors go to Sentry with personal data scrubbed.

## 2. What we never store

| Never stored | How we avoid it |
|---|---|
| Aadhaar numbers | Checklist records only "have / don't have". Inputs and assistant questions are scanned for 12-digit Aadhaar patterns, which are stripped before logging or sending to any provider. |
| Bank account numbers, IFSC, card numbers, UPI IDs | Same pattern stripping. No field ever asks for them. |
| Uploaded document images | The document check runs in the browser. The file never leaves the device and is held only in memory for the check. There is no server upload route and no option to keep a copy. |
| Raw voice recordings | Server speech-to-text (when used) transcribes and discards the audio. |
| Exact income, exact date of birth, full address, name | Not asked. Bands and district are enough for every MVP rule. |
| Precise location | "Nearby help" uses a district or a one-time location lookup that stays on the device. |

## 3. Data inventory

| Data | Where | Why | Retention |
|---|---|---|---|
| Language choice | `localStorage` | Remember the UI language | Until cleared |
| Profile (coarse bands), household members | `localStorage` | Matching | Until the user deletes it |
| Household sync (after sign-in) | `user_data`, AES-256-GCM | Use across devices, Saathi, helpers, reminders | Until the account is deleted, or 12 months after the last visit (nightly job) |
| Checklist status | `localStorage`; in the household when signed in | Document checklist | As above |
| Reminder subscription (push endpoint) | `push_subscription`, AES-256-GCM | Send reminders | Until turned off, dropped by the push service, or the account is deleted |
| Telegram chat id; the person's own number for SMS or WhatsApp | `reminder_channel`, AES-256-GCM | Send reminders on the chosen channel | Until turned off (`/stop` or in the app), the bot is blocked, or the account is deleted |
| Which reminders went out (names a scheme) | `reminder_sent` | Not repeating a reminder | With the account, or after a year |
| Grievances (message, optional contact) | `grievance`, AES-256-GCM | Redress | A year after closing. Not linked to an account, so "Delete my account" can't find them: ask through a grievance |
| Reports about helpers | `helper_report`, note AES-256-GCM | Moderation | A year after closing. Doesn't say who reported |
| Staff accounts, staff actions | `staff_account`, `admin_audit` | Accountability | Account until removed; actions for a year |
| Application tracking | `localStorage`; in the household when signed in | User's own tracking | As above |
| Assistant questions | Not logged | | |
| Outcome data | `outcome_count`: a counter per scheme, status and reason; shown at k ≥ 10 | Common rejection reasons | Counters only, kept |
| Helper shares, notes and audit log | `case_share`, `case_note`, `audit_log` | Accountability | Until the account is deleted, or a year (nightly job) |

## 4. Volunteer consent sessions (Phase 5)

The volunteer portal lets a verified volunteer, NGO worker or CSC operator help a user without getting standing access to their data.

### 4.1 Volunteers

- Verified by a partner organisation lead, who vouches for them and records `verified_by`.
- Roles: `volunteer`, `ngo_lead`, `csc_operator`. Roles limit which scopes they can ever receive.
- Sign in with a strong method (passkey or OTP + device binding). Helpers idle for 90 days are deactivated by the nightly job (built); their open cases end, and each person sees that in their audit log.

### 4.2 Starting a session

```
User's phone                              Volunteer's device
────────────                              ──────────────────
1. Taps "Get help from a volunteer"
2. Chooses what they may see/do:
   ☑ See my profile answers
   ☑ Update my checklist
   ☐ See my applications
   and how long (30 / 60 / 120 min)
3. App shows a 6-character code  ───────►  4. Volunteer enters code
   (valid 10 minutes, single use)          5. Server checks: code hash, not expired,
                                              role allows the scopes
6. User sees "Ravi (Seva NGO) is now   ◄──── 6. Session active
   helping you" + Stop button
```

- The code is stored only as a hash and is single-use. After 5 failed attempts from a device, it is locked out for a while.
- Consent is specific (named scopes), informed (shown in the user's language and read aloud), time-limited (`expires_at`) and revocable at any time from the user's phone.

### 4.3 During a session

- The volunteer sees only fields in scope. Sensitive fields (social category, disability details, income band) are **masked** unless the scope explicitly includes them, and unmasking is logged.
- Volunteers can't export, print or bulk-view. Each session shows one user.
- Every view and change is written to `audit_log`.

### 4.4 Audit log

- Append-only, enforced in PostgreSQL (built): triggers refuse update, truncate and delete for every role, and the API's role has no update or truncate. Delete works only through the cascade from erasing the person, or for rows over a year old.
- Records actor, action (`view` / `update`), field name and time. **Never the value.**
- The user can see their own audit log in the app, in their language: *"Ravi viewed your income band at 3:42 pm."*
- Partner leads can see aggregate activity for their volunteers, not user data.

### 4.5 Threat model (summary)

| Threat | Mitigation |
|---|---|
| Volunteer looks up people without consent | No search; access only via a user-generated code |
| Code shoulder-surfed or shared | Short expiry, single use, user sees who joined and can stop it |
| Volunteer collects data outside the app | Masking, no export; partner agreements; users can report a volunteer |
| Stolen volunteer account | Strong sign-in, device binding, sessions still need a user's code |
| Insider at JanSathi | RLS, least-privilege DB roles, admin access logged |
| Paid middlemen posing as volunteers | Verification through partners; the app states that help is **free** and never asks for payment |

The full threat model is in §4.6.

### 4.6 STRIDE threat model

Written 2026-11-04 from the code as built. Scope: the helper portal (`volunteer/`, `/api/v1/me/shares`), sign-in, and the document check that runs in the browser. **Status** says what exists; "open" means a real gap to close before a pilot, not a plan already under way.

**Assets:** a person's answers (some sensitive), family names, checklist and application tracking, Saathi conversation, helper notes, the audit log, share codes, session tokens, `JANSATHI_SECRET`, the admin token.

**Trust boundaries:** the person's device → API; helper's device → API; API → database; API → LLM provider; API → push services; browser → OpenStreetMap and Overpass (nearby help).

**Spoofing**

| Threat | Mitigation | Status |
|---|---|---|
| Someone signs in as a person or helper | One-time code to the phone: 5 minutes, 5 attempts, 3 per number per 15 minutes, 10 per address per hour | Built. MSG91 and Twilio senders exist, tested only against local stand-ins. `dev` is refused at a production start |
| A stolen session token | 256-bit random, only its SHA-256 stored, 30 days | Built. Open: no device binding, no list of active sessions for the person to end |
| A paid middleman poses as a helper | Helpers are registered by a named admin; the app says help is free; a person can report a helper, and an admin can end their role and cases at once | Built. Open: no partner-lead verification |
| A share code is guessed | 31^6 codes, stored as HMAC, 10 wrong codes per helper per hour, counted in the database | Built |
| A share code is overheard | The code only works for the first helper, and the person sees who joined and can stop it | Built. Open: the code lasts as long as the share, not 10 minutes |

**Tampering**

| Threat | Mitigation | Status |
|---|---|---|
| A helper changes a person's answers or rules results | Helpers have read-only access plus notes; Saathi runs read-only for helpers; results always come from the rules engine | Built |
| Forged fields in a household | `Households.validate` keeps only known fields, schemes and statuses; application reasons lose digit runs | Built |
| Saved data altered in the database | AES-256-GCM on household, conversation, notes: tampering fails decryption | Built |
| Prompt injection through a note or conversation reaches a helper's Saathi | Notes and conversation are not given to the helper's AI; tools are read-only; replies pass the copy check | Built. A model can still word things badly; it can't change a match |
| A crafted image attacks the document check | The image is decoded by the browser and the OCR worker, in the person's own tab, with no access to other people's data | Accepted: the blast radius is the person's own tab |

**Repudiation**

| Threat | Mitigation | Status |
|---|---|---|
| A helper denies having looked | Every join, view, note, AI question and exit is in the audit log, shown to the person; sensitive answers seen are listed by field name, never value | Built |
| The audit log is edited | Triggers refuse changes for every role, and the API's role has no update or truncate and owns nothing | Built, tested on PostgreSQL 17. A database superuser can still disable triggers, so the API never connects as one. Open: nothing detects a superuser doing so |
| An admin denies an action | Every admin API call is in `admin_audit` under their own account | Built. The bootstrap token shows as such, not as a person: remove it after set-up |
| Deleting an account removes the audit log, so a helper's record goes too | This follows the person's right to erasure | Accepted. A partner's dispute needs its own record |

**Information disclosure**

| Threat | Mitigation | Status |
|---|---|---|
| A helper sees more than needed | Scopes: `profile`, `sensitive`, `checklist`, `conversation`. Without `sensitive`, nine fields are removed before anything else runs (`CaseMasking`), so results, rule traces and Saathi for helpers see the masked copy. Answer history never goes to a helper | Built |
| Results give away a hidden answer | The engine runs on the masked answers, so those rules are "need more information". `masked` always lists every sensitive field, so it doesn't reveal which were answered | Built |
| The conversation holds sensitive answers in free text | A helper sees it only with both `conversation` and `sensitive` | Built |
| The checklist implies something sensitive (an SC scholarship, a caste certificate) | None in code. The sharing screen must say that a checklist can show this | **Open.** Residual risk accepted for now; the person chooses the scope |
| Other answers imply something (occupation, ration card type) | Only direct disclosures are masked | Accepted; the field list is in one place to widen |
| A helper copies what they see | No export in the app, and no bulk view | Partly: nothing stops a screenshot or a notebook. Partner agreements are needed |
| A helper browses for people | No search; only a code or the anonymous queue, which shows just the language | Built |
| Aadhaar or bank numbers reach the server in text | Runs of 9+ digits are removed from questions, Saathi messages, notes and application reasons | Built. It can't catch digits written as words |
| An uploaded document leaves the device | The check runs in the browser; the file is never sent, is held only in memory, and is gone when the page moves on. No server endpoint accepts documents | Built by the frontend; the API has no upload route |
| OCR text is kept | Only the result (right type or not, readable or not) is shown; the text is not stored or sent | Same |
| The LLM provider sees personal data | Coarse answers through tools only, digit runs removed; no phone or identifiers. Saathi does send answers to the provider, so the provider's data terms matter | Partly: record the chosen provider's terms before launch |
| The push service learns about a person | Pushes are empty; the endpoint is encrypted at rest; the browser's keys are not kept | Built |
| Nearby help leaks location | The location is used in the browser, rounded before any map or Overpass request, and never sent to our server | Built by the frontend |
| The database leaks | Phone as HMAC, personal data encrypted, keys outside the database | Built. Whoever holds `JANSATHI_SECRET` and the database can read everything |

**Denial of service**

| Threat | Mitigation | Status |
|---|---|---|
| Sign-in codes used to run up SMS cost or flood a phone | Limits per number and address | Built |
| Saathi used to run up model cost | Per-user rate limit, 30 messages per call | Built |
| `/ask`, `/match` and reports flooded | Per-address limits in the app, using only `X-Forwarded-For` from our own proxy | Built. Held in memory, so per instance, and reset by a restart. Many people behind one address share a limit |
| Anonymous outcomes skew the counts | Rate limit and fixed lists | Accepted: with no identity there can be no one-report-per-person rule. The numbers are labelled as what people reported, and never reach the rules engine |
| The push job is made to call an internal address (SSRF) | Only known browser push hosts over https on the default port are accepted, and redirects are not followed | Built |

**Elevation of privilege**

| Threat | Mitigation | Status |
|---|---|---|
| A person reaches the helper portal | `requireVolunteer` on every helper call | Built, tested |
| A helper reads a case that isn't theirs | Every read goes through `access()`: their id, not revoked, not expired | Built, tested |
| A helper keeps access after it ends | Checked on each request; revocation is immediate | Built, tested |
| The admin token leaks | Per-person accounts replace it: it's only for creating the first ones, then removed. Each use is logged and audited | Built. While set it acts as an admin, so remove it |
| A staff password is stolen | A code from an authenticator app is needed too; 5 wrong tries lock the account; a code works once; a reviewer can't manage people | Built |
| The `dev` SMS sender reaches production | `ProductionGuard` stops the start. Compose turns the check on unless told otherwise | Built, tested |
| A forged `X-Forwarded-For` dodges limits | Believed only from `JANSATHI_TRUSTED_PROXIES`, read from the right | Built, tested. Depends on port 8080 staying off the public network |
| The Telegram webhook is forged | A secret on every update, compared in constant time; one-time start tokens, stored as hashes, 15 minutes | Built, tested with simulated updates |
| Reminders are sent to someone else's phone | Only the number the person signed in with is accepted | Built, tested |

**Before a pilot, close:** a first real run of each provider (docs/OPERATIONS.md §12), partner verification of helpers, and a list of active sessions a person can end. Then have this document reviewed by someone qualified.

## 5. Security controls

- HTTPS everywhere, HSTS, Content-Security-Policy with no inline scripts (built; `style-src` allows inline styles), `Referrer-Policy: strict-origin-when-cross-origin`, which OpenStreetMap's tile policy needs and which sends only our origin.
- The API uses its own database role, which owns nothing (built). Planned: Postgres row-level security on every user-linked table.
- Secrets in the host's secret store, never in the repo. Provider API keys are server-side only.
- Encryption at rest (managed by the database host) plus column encryption for channel identifiers and synced profile blobs.
- Rate limiting on `/ask`, `/match`, sign-in, session-code redemption, reports and grievances (built; docs/OPERATIONS.md §8).
- Dependency scanning and secret scanning in CI.
- Logs and error reports are scrubbed of personal-number patterns, e-mail addresses and tokens; profile values are never logged (built).
- LLM providers receive no profile data except what's needed to explain a rule trace, and never identifiers. Prefer providers that don't train on API data, and record each provider's data terms in the provider config.

## 6. DPDP Act 2023 alignment

The Digital Personal Data Protection Act, 2023 applies to JanSathi as a *Data Fiduciary*. Check the current status and timelines of the DPDP Rules before launch.

| Requirement (summary) | How JanSathi meets it |
|---|---|
| Lawful purpose and consent | Plain-language consent notice per purpose (matching sync, reminders, volunteer help), in the user's chosen language, with a read-aloud option. The Act allows notices in English or any language in the Eighth Schedule of the Constitution; all 10 of our languages qualify. |
| Purpose limitation and data minimisation | Bands instead of exact values; data used only for the purpose consented to |
| Withdraw consent as easily as giving it | One tap to stop syncing, unsubscribe or end a volunteer session |
| Rights to access, correction and erasure | Profile screen shows everything held; edit in place; "Delete everything" |
| Grievance redressal | `POST /api/v1/grievances`, an admin queue, and the answer readable with the reference (built). Planned: the form in the app and a named contact; response within the period set by the Rules |
| Children's data (under 18) | Many students are 16–17. Without a verifiable parental-consent flow, the app works for them in on-device-only mode (no sync, no reminders, no volunteer sessions). No tracking or targeting of children. **Built on the server, see below.** |
| Security safeguards and breach notice | §5 controls; the breach runbook in docs/OPERATIONS.md §11 |
| Retention limits | §3 retention periods; nightly deletion jobs (built) |

**The under-18 rule as built.** When the account holder's age (the member `me`, or else the first) is the band `under_18` or a number below 18, `PUT /me/household` answers 403 with `code: on_device_only`. The server then deletes what it holds (household, conversation, reminder channels), ends every share, and sets `app_user.on_device_only`. While set: nothing can be saved, sharing and reminders answer 403, Saathi answers 403, and the job sends nothing. Questionnaire, matching, `/ask` and the checklist on the device keep working. `GET /me/restrictions` tells the app. Answering with an adult age lifts it.

Limits to be honest about:
- **The age is self-declared.** Nothing verifies it, and the rule can't catch someone who gives another age. A child can lift it by changing the answer. This is not "verifiable" anything; it's the on-device fallback the table describes, and it needs legal review.
- **Before the age is answered the server can't know.** An account with no age is treated as an adult until it says otherwise.
- **There is no guardian flow.** A guardian can't consent for a child here.
- **A parent's own account with a child as a family member is allowed**: the parent is the account holder.
- The account itself stays (phone hash, last 4 digits, the flag), so the rule holds at the next sign-in. "Delete my account" removes it.

Retention and the Rules: the DPDP Rules, 2025 are reported to require keeping certain logs for at least a year. Our audit logs are deleted a year after writing, and earlier when a person erases their account. A qualified person should check that against the notified text.

## 7. Reporting a security issue

Until a security contact is set up, report vulnerabilities privately to the maintainer rather than in a public issue.
