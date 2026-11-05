# Operations

How to run JanSathi on a server. **Status (2026-11-05):** what's here was tested on a developer machine with Docker and PostgreSQL 17. No real server, domain or provider account exists yet. §12 says plainly what has never run live.

## 1. What runs

| Service | Image | Notes |
|---|---|---|
| `app` | built from `Dockerfile` | PWA and API on port 8080, bound to `127.0.0.1` on the host |
| `db` | `postgres:17-alpine` | Volume `db-data`; never published to the host |
| `caddy` | `caddy:2-alpine` | Profile `tls`: ports 80 and 443, certificates from Let's Encrypt |

One `app` instance is the supported setup. Rate limits are held per process, so a second instance would allow double. The jobs (reminders, retention) are safe to run twice: reminders claim each send in the database, and retention steps can be repeated.

## 2. Deploy on a VPS

Needs: a small Linux VPS (2 GB RAM is enough without the ML service), Docker with the compose plugin, a domain with an A record pointing at the server, and ports 80 and 443 open. Close everything else, SSH apart.

```bash
git clone <repo> /opt/jansathi && cd /opt/jansathi
cp .env.example .env && chmod 600 .env
# Edit .env: POSTGRES_PASSWORD, POSTGRES_APP_PASSWORD, JANSATHI_SECRET, JANSATHI_DOMAIN,
# JANSATHI_PUBLIC_URL, the SMS settings, and JANSATHI_PRODUCTION=true.
openssl rand -base64 48     # for JANSATHI_SECRET; a different one for each password
docker compose --profile tls up -d --build
docker compose logs -f app  # one JSON object per line
curl https://your.domain/actuator/health
```

With `JANSATHI_PRODUCTION=true` the app **refuses to start** if the SMS provider is `dev`, `JANSATHI_SECRET` is under 32 characters, or drafts are on. If it exits at once, the last log line says why. A missing value in compose counts as `true`, so only `JANSATHI_PRODUCTION=false` in `.env` turns the check off. Use that on your own machine only.

**Upgrades:** `git pull && docker compose --profile tls up -d --build`. Take a backup first. Flyway migrates at start, as the database owner.

**First staff accounts:**
1. Set `JANSATHI_ADMIN_TOKEN` to a long random value and restart.
2. Open `https://your.domain/#/admin`, choose "Use the bootstrap token", and create an admin under Staff. Note the set-up code: it's shown once and lasts 48 hours.
3. Sign out. That person chooses "I have a set-up code", sets a password and adds the key to an authenticator app.
4. Remove `JANSATHI_ADMIN_TOKEN` and restart. The admin API stays on while an active staff account exists.

Admins manage staff, helpers and the audit log. Reviewers work the queues and read the status page. Publishing is never done here: only `tools/publish.mjs` publishes, after two independent reviews (docs/REVIEW.md).

## 3. The database roles

| Role | Used by | Can do |
|---|---|---|
| `jansathi` | Flyway, backups | Owns the tables. In the Docker image it's also a superuser, so its password is the key to everything: keep it out of the app's reach |
| `jansathi_app` | The API | Read and write, but no `UPDATE`, `TRUNCATE` or schema changes on `audit_log` and `admin_audit`; no access to `flyway_schema_history`; owns nothing |

Triggers (`db/vendor/postgresql/V4_1__audit_insert_only.sql`) refuse `UPDATE`, `TRUNCATE` and `DELETE` on both audit tables for every role, the owner included. A row can go in only two ways: the cascade from deleting the person, or being older than a year. A superuser can still disable triggers, which is why the API never connects as one.

`deploy/postgres/10-app-role.sh` creates `jansathi_app` when the volume is first created. **On an existing database**, as the owner:

```sql
CREATE ROLE jansathi_app LOGIN PASSWORD '...';
GRANT USAGE ON SCHEMA public TO jansathi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jansathi_app;
REVOKE ALL ON flyway_schema_history FROM jansathi_app;
REVOKE UPDATE ON audit_log, admin_audit FROM jansathi_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jansathi_app;
```

A later migration that creates a table inherits the default privileges. It must not grant `UPDATE` on the audit tables.

## 4. Backups

The database is the only state; certificates in `caddy-data` can be issued again.

```bash
# Daily, from cron on the host. Keep at least 7 daily and 4 weekly copies, off the server.
docker compose exec -T db pg_dump -U jansathi -Fc jansathi | age -r <public key> > /backup/jansathi-$(date +%F).dump.age
```

- Encrypt before the copy leaves the server. Personal columns are already encrypted, but phone hashes, audit rows and `last4` are not.
- **`JANSATHI_SECRET` is not in the backup and must not be stored with it.** A backup without the secret can't be read. Keep it in a password manager that two people can reach.
- Backups hold deleted accounts until they expire, so keep them no longer than 30 days. Say so in the privacy notice.
- **Restore test, every quarter:** restore to a scratch container, start the app against it with the real secret, and sign in. A backup never restored isn't a backup.

```bash
age -d -i key.txt jansathi-2026-11-05.dump.age | docker compose exec -T db pg_restore -U jansathi -d jansathi --clean --if-exists
```

The audit triggers fire during `--clean` only on row changes, and `pg_restore` drops and recreates the tables, so a restore works. Restore into an empty database if in doubt.

## 5. Providers

Each is off until its settings are complete, and none has been run against the real service.

### SMS (sign-in codes, and SMS reminders)

In India every commercial SMS must match a template registered on a DLT portal, under a registered sender header and principal entity. Register before choosing `msg91` or `twilio`; approval takes days.

Register these exact words (`{#var#}` is the DLT variable):

| Use | Template |
|---|---|
| Sign-in | `Your JanSathi sign-in code is {#var#}. It works for 5 minutes. Do not share it with anyone.` |
| Checklist | `JanSathi: your checklist still has documents to collect. Open the app to see them.` |
| Deadline | `JanSathi: a scheme on your checklist has a last date of {#var#} on its official source. Open the app to check it.` |

- **MSG91:** create a flow for each template, tie it to the DLT template and sender there, and name its variables `code` (sign-in) and `date` (deadline). Set `JANSATHI_SMS_MSG91_AUTH_KEY` and the three `*_TEMPLATE_ID` values to the MSG91 template ids. The code posts to `POST /api/v5/flow`.
- **Twilio:** complete Twilio's India sender registration, giving your entity and template ids there, and create a Messaging Service. Twilio takes no template id per message, so the words in `SmsText.java` must equal the registered template exactly. Set the three `JANSATHI_SMS_TWILIO_*` values, and any non-blank value in the two reminder template ids to turn SMS reminders on.

Reminders by SMS or WhatsApp go only to the number the person signed in with, and it's stored encrypted only while they keep the channel on.

### Telegram

1. Create a bot with BotFather. Set `JANSATHI_TELEGRAM_BOT_TOKEN`, `JANSATHI_TELEGRAM_BOT_USERNAME` (no @) and a long random `JANSATHI_TELEGRAM_WEBHOOK_SECRET` (letters, digits, `_`, `-`).
2. Point Telegram at the app, once:

```bash
curl -s "https://api.telegram.org/bot$TOKEN/setWebhook" \
  -d "url=https://your.domain/api/v1/reminders/telegram/webhook" \
  -d "secret_token=$SECRET" -d 'allowed_updates=["message","my_chat_member"]'
```

### WhatsApp Cloud API

Create a Meta business app with a WhatsApp number. Get a system user token, and have two templates approved under the Utility category: one with no variables for the checklist, one with a single body variable for the date. Use the same words as the SMS templates. Set the five `JANSATHI_WHATSAPP_*` values; `LANGUAGES` lists the codes the templates are approved in, others get `en`. `apiVersion` defaults to `v23.0`: check Meta's current version when you set this up.

### Web Push

`npx web-push generate-vapid-keys`, then the three `JANSATHI_PUSH_*` values. Changing the keys ends every subscription.

### Error reports

`JANSATHI_SENTRY_DSN`: any Sentry-compatible service (Sentry, GlitchTip). What's sent: exception types, messages with digit runs, e-mail addresses and bearer tokens removed, stack frames, the route pattern and the environment. Never request bodies, headers, users or addresses. Record the service's data terms before turning it on.

## 6. Rotating `JANSATHI_SECRET`

Rotate on suspicion of a leak, when someone with access leaves, and once a year.

What the secret protects: the encrypted columns (AES-256-GCM) and the keyed hashes of phone numbers, share codes and one-time codes.

1. **Back up**, and keep the old secret safe.
2. Set `JANSATHI_SECRET_PREVIOUS` to the old value and `JANSATHI_SECRET` to a new one. Restart. Reads work with either key; writes use the new one.
3. Sign in as an admin and call the re-encryption (safe to repeat):
   ```bash
   curl -X POST -H "Authorization: Bearer $STAFF_TOKEN" https://your.domain/api/v1/admin/maintenance/re-encrypt
   ```
4. **Keep `JANSATHI_SECRET_PREVIOUS` set.** Phone hashes can't be redone in bulk because the number isn't stored: each moves to the new key at that person's next sign-in. Remove the old value after 12 months, when anyone who hasn't signed in has been deleted anyway. Removing it sooner gives those people a new, empty account.
5. Check the logs for `Stored data cannot be decrypted`.

Side effects: share codes not yet used stop working (people make a new one), and one-time codes in flight fail (ask for a new one). After a suspected **leak**, also treat the old key as compromised for anything in old backups.

A new encrypted column must be added to the list in `KeyRotation.java`.

Other secrets: `POSTGRES_*` (change with `ALTER ROLE`, then `.env`, then restart), `JANSATHI_METRICS_TOKEN`, the provider keys and the Telegram webhook secret (change, restart, and run `setWebhook` again) can change at any time.

## 7. Monitoring

- **Health:** `GET /actuator/health`, public. Point an uptime monitor at it.
- **Metrics:** `GET /actuator/prometheus` with `Authorization: Bearer $JANSATHI_METRICS_TOKEN`; without the token setting it answers 404. Caddy doesn't expose it, so scrape `app:8080` from the Docker network. Worth an alert: `http_server_requests_seconds_count{status=~"5.."}`, `jansathi_rate_limited_total`, `jansathi_reminders_sent_total`, JVM memory and `hikaricp_connections_pending`.
- **Logs:** compose sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, one JSON object per line. They hold no request bodies, and every string passes through `LogScrubber`, which removes digit runs, e-mail addresses and bearer tokens. Rotate them in Docker (`max-size`, `max-file`).

## 8. Rate limits

Per client address, per minute: staff sign-in 10, sign-in 20, `/ask` 20, Saathi 40, reports and grievances 5, outcomes 10, `/match` 60, everything else under `/api` 300. Answers are `429` with `Retry-After`. Addresses are held in memory only, never stored or logged.

The app believes `X-Forwarded-For` only from `JANSATHI_TRUSTED_PROXIES`. Compose sets Docker's private range, for Caddy. That's safe only while port 8080 isn't reachable from outside: **don't change `JANSATHI_BIND` to a public address.** With a CDN in front of Caddy, configure Caddy's `trusted_proxies` as well, or every client looks like the CDN.

Tomcat's own forwarded-header handling is off (`forward-headers-strategy: none`) so there's one rule, not two.

## 9. Retention jobs

Nightly at 03:30 IST (`Retention.java`), and hourly for one-time codes, sessions and link tokens:

| What | When it goes |
|---|---|
| Account, with household, conversation, shares, notes, reminder channels, audit log | 12 months after the last visit |
| Helper role | 90 days idle; their open cases end, and each person sees that in their audit log |
| `audit_log`, `admin_audit` rows | A year (and a day) after writing |
| Ended case shares and their notes | A year after they expired |
| Closed grievances and helper reports | A year after closing |
| `reminder_sent` | After a year |

Nobody is warned before an inactive account is deleted, since no phone number is stored to warn them with. Reports of outdated information hold no personal data and are kept. Open grievances are never deleted by the job: close them.

## 10. Testing on PostgreSQL

`npm run test:backend` uses H2 and skips `PostgresTest` unless told where a database is. CI runs it on every push. By hand:

```bash
docker run -d --name pgtest -e POSTGRES_DB=jansathi -e POSTGRES_USER=jansathi -e POSTGRES_PASSWORD=jansathi -p 55432:5432 postgres:17-alpine
docker exec -e POSTGRES_APP_PASSWORD=app -e POSTGRES_USER=jansathi -e POSTGRES_DB=jansathi -i pgtest sh -s < deploy/postgres/10-app-role.sh
cd backend && JANSATHI_TEST_PG_URL=jdbc:postgresql://localhost:55432/jansathi ./gradlew test --tests '*PostgresTest'
docker rm -f pgtest
```

## 11. Breach runbook (DPDP Act 2023)

Have a qualified person check this against the notified DPDP Rules, 2025 before launch, and again when their obligations start. As read on 2026-11-05 from secondary summaries, not the gazette text: on a personal data breach the Board is told without delay, a detailed report follows within 72 hours (the Board can extend this), and affected people are told without delay in plain language. Commentaries disagree on some details, so go by the notified text. There's no form yet that we could confirm; check the Board's site.

A breach is any unauthorised processing, disclosure, loss of access or loss of integrity of personal data, however small. There's no size threshold, so **when in doubt, it's a breach.**

**Name now, before launch:** an incident lead, a deputy, and the grievance contact. Keep a dated log of every step below.

**First hour: contain**
1. Start the log: who noticed, when, what.
2. Stop it. Take the affected part offline if needed; `docker compose stop app` is acceptable. End sessions if accounts are involved: `DELETE FROM auth_session; DELETE FROM staff_session;`.
3. Preserve evidence: `docker compose logs app > incident.log`, a database dump, and copies of `admin_audit`. Don't rebuild the server yet.

**Same day: assess, and tell the Board**
4. Work out what was reached. A database leak **without** `JANSATHI_SECRET` exposes phone hashes, last 4 digits, audit rows and helper labels, not answers. **With** the secret it exposes everything, and an offline attack can recover phone numbers from their hashes.
5. Send the Board the first notice: nature, extent, timing, location, likely impact. Don't wait for certainty.
6. Rotate whatever may be known (§6): the secret, database passwords, provider keys, the Telegram webhook secret, and staff passwords (Staff → New set-up code).

**Within 72 hours**
7. Send the Board the detailed report: what happened, facts and causes, what was done, who was affected, and what was told to them.
8. Tell each affected person, in their language: what happened, when, what it means for them, what we did, what they can do, and how to reach us. Telegram, SMS and WhatsApp reach those who turned them on; we hold no phone number for others (only a hash), so publish a notice in the app and on the site as well. Never ask people to confirm details by message.

**After**
9. Fix the cause, and add a test or alert that would have caught it.
10. Write up what happened and update `PRIVACY_AND_SECURITY.md`.
11. Tell partner organisations if helpers were involved.

## 12. Never run live

These are built and tested against local stand-ins only. No credentials exist here. Each needs a first real test before people depend on it.

| What | Tested how | What could still differ |
|---|---|---|
| MSG91 | Local fake HTTP server | Field names and the flow API as MSG91 runs today; the `code` and `date` variable names; DLT rejection |
| Twilio | Local fake HTTP server | India registration; carriers rejecting text that differs from the template |
| Telegram | Fake server and simulated webhook updates | `setWebhook` itself; real update shapes |
| WhatsApp Cloud API | Local fake HTTP server | Template approval; the API version; error handling beyond success or failure |
| Web Push | Signature checked locally; local fake push service | Real browser push services |
| Sentry | Payload inspected by test; not delivered to a real service | Whether a real server accepts the envelope |
| Caddy, TLS, Let's Encrypt | `Caddyfile` written, never served | Everything, including the HSTS and forwarded-header path end to end |
| Backup and restore commands, key rotation on real data | Rotation unit-tested on H2; the commands were not run | Timing and mistakes |

## 13. Settings

Set in `.env` (see `.env.example`).

| Variable | What it does |
|---|---|
| `JANSATHI_PRODUCTION` | `true`: refuse to start when unsafe (§2). Compose defaults to `true` |
| `POSTGRES_PASSWORD` | Password of the owner role, used by Flyway |
| `POSTGRES_APP_PASSWORD` | Password of `jansathi_app`, used by the API. Read when the volume is created |
| `JANSATHI_SECRET` | Key for encryption at rest and keyed hashes. 32+ characters. Never just change it (§6) |
| `JANSATHI_SECRET_PREVIOUS` | Old secret during a rotation |
| `JANSATHI_ADMIN_TOKEN` | Bootstrap for the admin API. Remove after creating staff (§2) |
| `JANSATHI_DOMAIN` | Domain for Caddy |
| `JANSATHI_PUBLIC_URL` | App address for links in reminders, no trailing slash |
| `JANSATHI_BIND` | Host address for port 8080. Keep `127.0.0.1` |
| `JANSATHI_TRUSTED_PROXIES` | Addresses or ranges whose `X-Forwarded-For` is believed (§8) |
| `JANSATHI_RATE_LIMIT_ENABLED` | `false` turns the limits off |
| `JANSATHI_SMS_PROVIDER` | `dev`, `msg91` or `twilio` |
| `JANSATHI_SMS_OTP_TEMPLATE_ID` | MSG91 template for sign-in codes (required for MSG91) |
| `JANSATHI_SMS_CHECKLIST_TEMPLATE_ID`, `JANSATHI_SMS_DEADLINE_TEMPLATE_ID` | Both set: SMS reminders are offered |
| `JANSATHI_SMS_MSG91_AUTH_KEY` | MSG91 account key |
| `JANSATHI_SMS_TWILIO_ACCOUNT_SID`, `_AUTH_TOKEN`, `_MESSAGING_SERVICE_SID` | Twilio account and Messaging Service |
| `JANSATHI_PUSH_PUBLIC_KEY`, `_PRIVATE_KEY`, `_SUBJECT` | Web Push (VAPID) |
| `JANSATHI_TELEGRAM_BOT_TOKEN`, `_BOT_USERNAME`, `_WEBHOOK_SECRET` | All three: Telegram is offered |
| `JANSATHI_WHATSAPP_ACCESS_TOKEN`, `_PHONE_NUMBER_ID`, `_CHECKLIST_TEMPLATE`, `_DEADLINE_TEMPLATE` | All four: WhatsApp is offered |
| `JANSATHI_WHATSAPP_LANGUAGES` | Comma-separated codes the templates are approved in |
| `JANSATHI_METRICS_TOKEN` | Bearer token for `/actuator/prometheus`; blank turns it off |
| `JANSATHI_SENTRY_DSN` | Error reports; blank turns them off |
| `JANSATHI_ENVIRONMENT` | Label on error reports |
| `JANSATHI_LLM_*` | The AI (docs/ARCHITECTURE.md §6) |
| `JANSATHI_EMBED_*` | The ML service for semantic retrieval (ml/README.md) |
| `JANSATHI_BUNDLE_DIR`, `JANSATHI_INCLUDE_DRAFTS` | Local development and staging only |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` for JSON logs; compose sets it |
| `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_USER`, `_PASSWORD` | Database connection, for the API and for migrations; compose sets them |
