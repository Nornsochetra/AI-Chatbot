-- Files sent while chatting with the AI (docs/attachments-ai-chat.md).
-- Such a file belongs to the chat session first; handover_id is filled in when the chat goes to an agent.
ALTER TABLE attachment ALTER COLUMN handover_id DROP NOT NULL;

-- When the file went into the conversation. Null = a draft the customer has not sent yet (erased after an hour).
ALTER TABLE attachment ADD COLUMN sent_at TIMESTAMPTZ;
UPDATE attachment SET sent_at = created_at;

-- Text read from the file for the bot (OCR / PDF text). Null status = sent in a live agent chat, never read.
ALTER TABLE attachment ADD COLUMN read_status VARCHAR(20);              -- READ, NO_TEXT, FAILED, SKIPPED
ALTER TABLE attachment ADD COLUMN read_text   TEXT;                     -- after masking; the raw text is never stored
ALTER TABLE attachment ADD COLUMN read_masked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_attachment_session ON attachment (session_id);

-- A bot turn can be about a file.
ALTER TABLE chat_event ADD COLUMN attachment_id UUID REFERENCES attachment (id);
