-- Who the agent is, to apply the assignment rules (one agent per chat, at most N chats per agent).
-- agent_name stays the first name shown to the customer; agent_key is the agent's full name as Botpress Desk
-- sends it, lower-cased (Desk events carry no agent id), or the console login.
ALTER TABLE handover ADD COLUMN agent_key VARCHAR(200);

CREATE INDEX idx_handover_status_agent ON handover (status, agent_key);
