-- One live-agent ticket per handover. A session can have several over time, at most one open.
CREATE TABLE handover (
    id                        UUID PRIMARY KEY,
    session_id                VARCHAR(64)  NOT NULL,
    channel                   VARCHAR(32)  NOT NULL,
    status                    VARCHAR(16)  NOT NULL,   -- WAITING, ACTIVE, CLOSED
    reason                    VARCHAR(32)  NOT NULL,   -- USER_REQUEST, BOT_SUGGESTED
    close_reason              VARCHAR(32),             -- AGENT_CLOSED, CUSTOMER_INACTIVE
    agent_name                VARCHAR(100),
    created_at                TIMESTAMPTZ  NOT NULL,
    accepted_at               TIMESTAMPTZ,
    closed_at                 TIMESTAMPTZ,
    last_customer_activity_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_handover_status ON handover (status, created_at);
CREATE INDEX idx_handover_session ON handover (session_id, created_at);

-- Messages during a handover (customer, agent, system events). The id is the polling cursor.
CREATE TABLE handover_message (
    id          BIGSERIAL PRIMARY KEY,
    handover_id UUID         NOT NULL REFERENCES handover (id),
    session_id  VARCHAR(64)  NOT NULL,
    sender      VARCHAR(16)  NOT NULL,   -- CUSTOMER, AGENT, SYSTEM
    text        TEXT         NOT NULL,   -- for SYSTEM: an event code (WAITING, AGENT_JOINED, ...)
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_handover_message_session ON handover_message (session_id, id);
CREATE INDEX idx_handover_message_handover ON handover_message (handover_id, id);
