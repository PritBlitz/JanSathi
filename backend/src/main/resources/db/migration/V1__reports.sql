-- "Report outdated info" moderation queue (brief §6.3). No personal data: no IP, no profile;
-- long digit runs are removed from the note before insert.
-- Portable SQL: runs on PostgreSQL (production) and H2 in PostgreSQL mode (local, tests).
CREATE TABLE report (
    id              VARCHAR(36)  PRIMARY KEY,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    scheme          VARCHAR(120) NOT NULL,
    reason          VARCHAR(20)  NOT NULL,
    note            VARCHAR(1000) NOT NULL DEFAULT '',
    lang            VARCHAR(5),
    bundle_version  VARCHAR(80)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'open',
    resolved_at     TIMESTAMP WITH TIME ZONE,
    resolution      VARCHAR(1000),
    CONSTRAINT report_reason CHECK (reason IN ('outdated', 'wrong', 'broken_link', 'other')),
    CONSTRAINT report_status CHECK (status IN ('open', 'resolved', 'rejected'))
);

CREATE INDEX report_status_received ON report (status, received_at);
