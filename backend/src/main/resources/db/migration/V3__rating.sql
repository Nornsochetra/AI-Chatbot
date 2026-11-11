-- Post-chat rating (BRD "Post-Chat Service Rating"): one per finished agent chat, one per session for the AI.
CREATE TABLE rating (
    id          UUID PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    channel     VARCHAR(32)  NOT NULL,
    type        VARCHAR(16)  NOT NULL,   -- AI, AGENT
    handover_id UUID         REFERENCES handover (id),  -- set for AGENT ratings
    score       SMALLINT     NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment     TEXT,                    -- masked like bot messages
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_rating_handover ON rating (handover_id) WHERE handover_id IS NOT NULL;
CREATE UNIQUE INDEX uq_rating_ai_session ON rating (session_id) WHERE type = 'AI';
CREATE INDEX idx_rating_created ON rating (created_at);
