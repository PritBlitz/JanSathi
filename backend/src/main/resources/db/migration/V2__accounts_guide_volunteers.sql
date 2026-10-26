-- Accounts (phone + OTP), the AI guide's memory, and the volunteer portal (brief §6.6).
-- Privacy (docs/PRIVACY_AND_SECURITY.md): the phone number is never stored, only a keyed hash
-- (for lookup) and its last 4 digits (so a person can recognise their account). Household answers,
-- guide messages and helper notes are encrypted (AES-GCM) with a key the database never sees.
-- Portable SQL: PostgreSQL in production, H2 in PostgreSQL mode locally and in tests.

CREATE TABLE app_user (
    id              VARCHAR(36)  PRIMARY KEY,
    phone_hash      VARCHAR(64)  NOT NULL UNIQUE,
    phone_last4     VARCHAR(4)   NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'user',
    display_name    VARCHAR(80),
    organisation    VARCHAR(120),
    lang            VARCHAR(5),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT app_user_role CHECK (role IN ('user', 'volunteer'))
);

CREATE TABLE otp_challenge (
    id              VARCHAR(36)  PRIMARY KEY,
    phone_hash      VARCHAR(64)  NOT NULL,
    code_hash       VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    used            BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX otp_phone_created ON otp_challenge (phone_hash, created_at);

CREATE TABLE auth_session (
    token_hash      VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The household (members, answers, checklist) as encrypted JSON: what the PWA keeps on the device.
CREATE TABLE user_data (
    user_id         VARCHAR(36)  PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    payload_enc     TEXT         NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE guide_message (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role            VARCHAR(16)  NOT NULL,
    content_enc     TEXT         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT guide_message_role CHECK (role IN ('user', 'guide'))
);
CREATE INDEX guide_message_user ON guide_message (user_id, created_at);

-- A user's consent for a helper to see their case: by short code, or by asking for help.
CREATE TABLE case_share (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash       VARCHAR(64),
    scopes          VARCHAR(100) NOT NULL,
    help_request    BOOLEAN      NOT NULL DEFAULT FALSE,
    lang            VARCHAR(5),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    volunteer_id    VARCHAR(36)  REFERENCES app_user (id) ON DELETE SET NULL,
    claimed_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX case_share_code ON case_share (code_hash);
CREATE INDEX case_share_open ON case_share (help_request, volunteer_id, expires_at);

CREATE TABLE case_note (
    id              VARCHAR(36)  PRIMARY KEY,
    share_id        VARCHAR(36)  NOT NULL REFERENCES case_share (id) ON DELETE CASCADE,
    volunteer_id    VARCHAR(36)  REFERENCES app_user (id) ON DELETE SET NULL,
    content_enc     TEXT         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Who saw or changed what in a user's case, readable by that user (brief §6.6).
CREATE TABLE audit_log (
    id              VARCHAR(36)  PRIMARY KEY,
    subject_id      VARCHAR(36)  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    actor_id        VARCHAR(36),
    actor_label     VARCHAR(120) NOT NULL,
    action          VARCHAR(40)  NOT NULL,
    detail          VARCHAR(200),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX audit_subject ON audit_log (subject_id, created_at);
