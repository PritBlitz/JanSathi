-- Reminder channels, per-person staff accounts, the admin audit, grievances, reports about helpers,
-- anonymous outcome counts and the columns the retention jobs need.
-- Portable SQL: PostgreSQL in production, H2 in PostgreSQL mode locally and in tests. What only
-- PostgreSQL can do (insert-only triggers, role grants) is in db/vendor/postgresql.

-- last_seen_at: updated at most daily, drives "helper idle 90 days" and "account inactive 12 months".
-- on_device_only: this person said they are under 18, so nothing of theirs is kept here (PRIVACY §6).
ALTER TABLE app_user ADD COLUMN last_seen_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN deactivated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN deactivated_reason VARCHAR(20);
ALTER TABLE app_user ADD COLUMN on_device_only BOOLEAN NOT NULL DEFAULT FALSE;

-- Reminder channels other than Web Push (which keeps push_subscription). The address (Telegram chat
-- id or the person's own mobile number) is encrypted; address_hash finds the row for Telegram /stop.
CREATE TABLE reminder_channel (
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    channel         VARCHAR(16)  NOT NULL,
    address_enc     TEXT         NOT NULL,
    address_hash    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at    TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (user_id, channel),
    CONSTRAINT reminder_channel_name CHECK (channel IN ('telegram', 'sms', 'whatsapp'))
);
CREATE INDEX reminder_channel_address ON reminder_channel (channel, address_hash);

-- One-time start tokens for the Telegram deep link. Only a hash is kept.
CREATE TABLE telegram_link (
    token_hash      VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- What was already sent, so a deadline reminds once per channel. The key names a scheme, so the row
-- goes with the account and after a year.
CREATE TABLE reminder_sent (
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    channel         VARCHAR(16)  NOT NULL,
    dedupe_key      VARCHAR(160) NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, channel, dedupe_key)
);

-- Wrong share codes per helper, kept here so a restart does not reset the limit.
CREATE TABLE share_code_failure (
    id              VARCHAR(36)  PRIMARY KEY,
    helper_id       VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    failed_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX share_code_failure_helper ON share_code_failure (helper_id, failed_at);

-- People who work on the admin screens. Separate from app_user: no phone, and never touched by the
-- retention of citizens' accounts. Password (PBKDF2) plus an authenticator code (TOTP).
CREATE TABLE staff_account (
    id              VARCHAR(36)  PRIMARY KEY,
    username        VARCHAR(60)  NOT NULL UNIQUE,
    display_name    VARCHAR(80)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(200),
    totp_secret_enc TEXT,
    totp_confirmed  BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_last_step  BIGINT       NOT NULL DEFAULT 0,
    setup_hash      VARCHAR(64),
    setup_expires_at TIMESTAMP WITH TIME ZONE,
    failed_logins   INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP WITH TIME ZONE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(80)  NOT NULL,
    last_login_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT staff_role CHECK (role IN ('admin', 'reviewer'))
);

CREATE TABLE staff_session (
    token_hash      VARCHAR(64)  PRIMARY KEY,
    staff_id        VARCHAR(36)  NOT NULL REFERENCES staff_account (id) ON DELETE CASCADE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Every admin API call: who, what, on which record, and the answer. Never the content.
CREATE TABLE admin_audit (
    id              VARCHAR(36)  PRIMARY KEY,
    actor_id        VARCHAR(36)  NOT NULL,
    actor_label     VARCHAR(120) NOT NULL,
    action          VARCHAR(120) NOT NULL,
    target          VARCHAR(80),
    status          INTEGER      NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX admin_audit_created ON admin_audit (created_at);

-- Grievance redressal (DPDP; PRIVACY §6). Not linked to an account. The message and the optional
-- contact are encrypted; the row goes one year after it is closed.
CREATE TABLE grievance (
    id              VARCHAR(36)  PRIMARY KEY,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    category        VARCHAR(24)  NOT NULL,
    message_enc     TEXT         NOT NULL,
    contact_enc     TEXT,
    lang            VARCHAR(5),
    status          VARCHAR(20)  NOT NULL DEFAULT 'open',
    response        VARCHAR(1000),
    resolved_at     TIMESTAMP WITH TIME ZONE,
    resolved_by     VARCHAR(120),
    CONSTRAINT grievance_status CHECK (status IN ('open', 'resolved', 'rejected'))
);
CREATE INDEX grievance_status_received ON grievance (status, received_at);

-- A person's report about a helper. Who reported is not kept, so moderators judge the report alone
-- and the report outlives the reporter's account.
CREATE TABLE helper_report (
    id              VARCHAR(36)  PRIMARY KEY,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    helper_id       VARCHAR(36)  REFERENCES app_user (id) ON DELETE SET NULL,
    helper_label    VARCHAR(220) NOT NULL,
    reason          VARCHAR(24)  NOT NULL,
    note_enc        TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'open',
    resolution      VARCHAR(1000),
    resolved_at     TIMESTAMP WITH TIME ZONE,
    resolved_by     VARCHAR(120),
    CONSTRAINT helper_report_status CHECK (status IN ('open', 'resolved', 'rejected'))
);
CREATE INDEX helper_report_status_received ON helper_report (status, received_at);

-- Anonymous outcomes (brief §6.7): counters only. There is no row per person to re-identify, and no
-- time, address or identity. reason_code is '' for approved.
CREATE TABLE outcome_count (
    scheme          VARCHAR(120) NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    reason_code     VARCHAR(24)  NOT NULL,
    hits            BIGINT       NOT NULL,
    PRIMARY KEY (scheme, status, reason_code),
    CONSTRAINT outcome_status CHECK (status IN ('approved', 'rejected'))
);
