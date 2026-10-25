# Privacy and security

> **Status:** planned design. The current prototype (`frontend/`) stores only the chosen language in `localStorage` and sends no personal data anywhere.
>
> This document is an engineering design, not legal advice. Have it reviewed by someone qualified in Indian data-protection law before launch.

## 1. Principles

1. **Collect the minimum.** Ask only what a rule needs, and ask it as a band (age band, income band) rather than an exact value.
2. **On the device by default.** The profile, checklist and saved schemes live in the browser (IndexedDB). Matching can run on the device. Nothing is sent to the server unless a feature needs it and the user has agreed.
3. **Anonymous by default.** No account is needed to discover schemes, ask questions or build a checklist.
4. **Deletable.** One "Delete everything" control wipes local data and any synced data.
5. **No silent sharing.** No third-party analytics, ad trackers or social pixels. Errors go to Sentry with personal data scrubbed.

## 2. What we never store

| Never stored | How we avoid it |
|---|---|
| Aadhaar numbers | Checklist records only "have / don't have". Inputs and assistant questions are scanned for 12-digit Aadhaar patterns, which are stripped before logging or sending to any provider. |
| Bank account numbers, IFSC, card numbers, UPI IDs | Same pattern stripping. No field ever asks for them. |
| Uploaded document images (by default) | OCR uploads are processed in memory and deleted immediately after the check. Keeping a copy needs a separate, explicit opt-in, and it expires. |
| Raw voice recordings | Server speech-to-text (when used) transcribes and discards the audio. |
| Exact income, exact date of birth, full address, name | Not asked. Bands and district are enough for every MVP rule. |
| Precise location | "Nearby help" uses a district or a one-time location lookup that stays on the device. |

## 3. Data inventory

| Data | Where | Why | Retention |
|---|---|---|---|
| Language choice | `localStorage` | Remember the UI language | Until cleared |
| Profile (coarse bands), household members | IndexedDB, encrypted with a device key (WebCrypto) | Matching | Until the user deletes it |
| Profile sync (opt-in) | `user_profile` (Postgres, RLS) | Use across devices, volunteer help, reminders | Deleted after 12 months of inactivity, or on request |
| Checklist status | IndexedDB; synced only if opted in | Document checklist | As above |
| Reminder subscription (push endpoint / Telegram chat id) | `reminder`, encrypted column | Send reminders | Deleted when unsubscribed or when the scheme's window closes |
| Application tracking | `application` | User's own tracking | As profile |
| Assistant questions | Hash + retrieved ids + answer in logs | Debugging and quality | 30 days; raw text only with opt-in |
| Outcome data (Phase 6) | Aggregated, anonymised counts (k ≥ 10) | Common rejection reasons | Aggregates only |
| Volunteer sessions and audit log | `assist_session`, `audit_log` | Accountability | 1 year, then deleted |

## 4. Volunteer consent sessions (Phase 5)

The volunteer portal lets a verified volunteer, NGO worker or CSC operator help a user without getting standing access to their data.

### 4.1 Volunteers

- Verified by a partner organisation lead, who vouches for them and records `verified_by`.
- Roles: `volunteer`, `ngo_lead`, `csc_operator`. Roles limit which scopes they can ever receive.
- Sign in with a strong method (passkey or OTP + device binding). Accounts are deactivated after 90 days of inactivity.

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

- Append-only (database permissions allow insert only; no update or delete except retention expiry).
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

A full threat model (STRIDE) is to be written before Phase 5 starts.

## 5. Security controls

- HTTPS everywhere, HSTS, strict Content-Security-Policy (no inline scripts once a build step exists), `Referrer-Policy: strict-origin-when-cross-origin`.
- Postgres row-level security on every user-linked table; the API uses scoped database roles.
- Secrets in the host's secret store, never in the repo. Provider API keys are server-side only.
- Encryption at rest (managed by the database host) plus column encryption for channel identifiers and synced profile blobs.
- Rate limiting on `/ask`, `/match`, session-code redemption and reports.
- Dependency scanning and secret scanning in CI.
- Logs and error reports are scrubbed of personal-number patterns and profile values.
- LLM providers receive no profile data except what's needed to explain a rule trace, and never identifiers. Prefer providers that don't train on API data, and record each provider's data terms in the provider config.

## 6. DPDP Act 2023 alignment

The Digital Personal Data Protection Act, 2023 applies to JanSathi as a *Data Fiduciary*. Check the current status and timelines of the DPDP Rules before launch.

| Requirement (summary) | How JanSathi meets it |
|---|---|
| Lawful purpose and consent | Plain-language consent notice per purpose (matching sync, reminders, volunteer help), in the user's chosen language, with a read-aloud option. The Act allows notices in English or any language in the Eighth Schedule of the Constitution; all 10 of our languages qualify. |
| Purpose limitation and data minimisation | Bands instead of exact values; data used only for the purpose consented to |
| Withdraw consent as easily as giving it | One tap to stop syncing, unsubscribe or end a volunteer session |
| Rights to access, correction and erasure | Profile screen shows everything held; edit in place; "Delete everything" |
| Grievance redressal | In-app grievance form and a named contact; response within the period set by the Rules |
| Children's data (under 18) | Many students are 16–17. Without a verifiable parental-consent flow, the app works for them in on-device-only mode (no sync, no reminders, no volunteer sessions). No tracking or targeting of children. |
| Security safeguards and breach notice | §5 controls; a breach response runbook that notifies the Data Protection Board and affected users |
| Retention limits | §3 retention periods; automatic deletion jobs |

## 7. Reporting a security issue

Until a security contact is set up, report vulnerabilities privately to the maintainer rather than in a public issue.
