-- Web Push reminders (brief 6.5). One browser per person. Pushes carry no payload, so the browser's
-- encryption keys are not kept. The endpoint is encrypted; deleting the account removes the row.
CREATE TABLE push_subscription (
    user_id         VARCHAR(36)  PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    endpoint_enc    TEXT         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at    TIMESTAMP WITH TIME ZONE
);
