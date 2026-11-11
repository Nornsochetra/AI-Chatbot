CREATE TABLE chat_event (
    id                 UUID PRIMARY KEY,
    session_id         VARCHAR(64)  NOT NULL,
    channel            VARCHAR(32)  NOT NULL,
    user_text          TEXT         NOT NULL,
    bot_reply          TEXT,
    input_masked       BOOLEAN      NOT NULL DEFAULT FALSE,
    handover_suggested BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_chat_event_session ON chat_event (session_id, created_at);
CREATE INDEX idx_chat_event_created ON chat_event (created_at);
