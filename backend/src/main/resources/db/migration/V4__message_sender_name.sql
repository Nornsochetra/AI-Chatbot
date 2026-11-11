-- Name shown to the customer for agent messages and "agent joined / left" events: the agent's login username.
ALTER TABLE handover_message ADD COLUMN sender_name VARCHAR(100);
