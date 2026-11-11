-- When the customer last got a "still waiting" reminder (while no agent has accepted the chat yet).
ALTER TABLE handover ADD COLUMN last_reminder_at TIMESTAMPTZ;
