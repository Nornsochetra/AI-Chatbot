-- Files customers upload during a live agent chat (docs/attachments-simple.md): stored as uploaded, agent-only.
CREATE TABLE attachment (
    id            UUID PRIMARY KEY,
    handover_id   UUID         NOT NULL REFERENCES handover (id),
    session_id    VARCHAR(64)  NOT NULL,
    file_name     VARCHAR(120) NOT NULL,   -- shown as a label only, never used as a path
    content_type  VARCHAR(40)  NOT NULL,   -- detected from the file content
    size_bytes    INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ,
    deleted_by    VARCHAR(100),
    delete_reason VARCHAR(20)              -- AGENT, RETENTION
);

CREATE INDEX idx_attachment_handover ON attachment (handover_id);
CREATE INDEX idx_attachment_created ON attachment (created_at) WHERE deleted_at IS NULL;

-- The bytes live apart from the metadata, so chat lists never load files. Deleting a file deletes this row.
CREATE TABLE attachment_data (
    attachment_id UUID  PRIMARY KEY REFERENCES attachment (id),
    data          BYTEA NOT NULL
);

-- Chat messages can point at an attachment (the upload itself, and the "file removed" event).
ALTER TABLE handover_message ADD COLUMN attachment_id UUID REFERENCES attachment (id);

-- Who did what (start of the admin portal audit log).
CREATE TABLE audit_event (
    id          BIGSERIAL    PRIMARY KEY,
    actor       VARCHAR(100) NOT NULL,     -- agent login, or "system"
    action      VARCHAR(40)  NOT NULL,     -- ATTACHMENT_VIEWED, ATTACHMENT_DELETED, ...
    target_type VARCHAR(30),
    target_id   VARCHAR(64),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_event_created ON audit_event (created_at);
CREATE INDEX idx_audit_event_target ON audit_event (target_type, target_id);
