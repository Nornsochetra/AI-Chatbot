# AI Chatbot: Botpress Desk Evaluation

Status: tested 2026-11-11. **Decision (updated the same day): Botpress Desk is the default agent inbox. Our own console is kept in the code but switched off (`AGENT_INBOX=console` brings it back), see [agent-inbox-switch.md](agent-inbox-switch.md).** Sections 5–7 below record the original evaluation.

## 1. Question

Botpress has its own agent inbox, **Botpress Desk** (`desk.botpress.cloud`). Could it replace the agent console we built (see [handover.md](handover.md))? Two things to find out:

1. Do chats that come through our backend (Botpress Chat API, not the Botpress Webchat) show up in Desk?
2. Can an agent reply from Desk and the customer see it in **our** widget?

## 2. Answer

| Question | Result |
|---|---|
| Do our chats show up in Desk? | **Yes.** Every Chat API conversation gets a Desk ticket (`properties.ticketId`) and is listed live in Desk under "Frontline Bots" |
| Does a Desk reply reach our widget? | **No, not with the current backend.** The reply stays in Botpress; our widget never shows it |

## 3. What we tested

### Test 1: conversation created directly through the Chat API

- Customer "DESK TEST customer", ticket `tkt_h2qo6ryrvz`.
- The conversation got a ticket ID right away, without anyone asking for an agent.
- When the customer asked for a human, the bot refused ("I'm an AI assistant and can't transfer…"), because no escalation is set up in the bot.

### Test 2: our widget + an agent joining in Desk

1. A customer chatted in our widget (`http://localhost:3000`). The chat appeared at the top of Desk (ticket `tkt_bxrs45iijf`, channel "Webchat", source "Hitl").
2. In Desk, the agent clicked **Join Conversation**. The customer had **not** asked for an agent.
3. The customer sent "widget desk test". The bot stayed silent (an agent had taken over).
4. After 20 seconds our widget showed the fallback: "Sorry, I can't answer right now. Would you like to talk to a live agent?"
5. The agent's reply "Hello from Desk" (sent after those 20 seconds) was marked "Delivered" in Desk but **never appeared in the widget**.

Also seen in Desk: an AI-written summary of the issue appeared in the ticket thread, and Desk logged its own events (ticket opened, priority changed, agent assigned).

## 4. Why the Desk reply did not show

Our backend reads Botpress like this (`BotpressBotEngine.ask`):

1. Post the customer's message to Botpress.
2. Check the conversation for a reply every 0.7 s, for at most 20 s (`reply-timeout-ms`).
3. Return any new message that is not from the customer. No reply in time → the widget shows the fallback.

So:

- The backend only listens **during those 20 seconds after a customer message**. A Desk reply sent later is never picked up.
- It cannot tell a bot message from an agent message. A Desk reply that happens to arrive inside the window would be shown as a **bot** bubble.
- Once an agent joins in Desk, the bot stops answering, so every customer message ends in the fallback unless the agent replies within 20 s.

## 5. Botpress Desk vs our console

| | Botpress Desk | Our agent console (`/agent`) |
|---|---|---|
| Which chats agents see | **All** bot chats, live; an agent can take over any of them | Only chats where the customer asked for an agent |
| Customer told before an agent joins | No | Yes: "connecting you to a live agent", queue position, reminders |
| What agents see of customer text | Fully masked text (our backend masks everything before Botpress) | Secrets-only masking: phone numbers and IDs stay visible so the agent can verify or call back |
| Replies in our widget | Not working (see section 4) | Real time, as agent bubbles with the agent's username, with typing indicators |
| File upload, ratings, audit, "only the agent closes" rule | Desk's own features; ours would not apply | Built and tested |
| Ticket features (priority, SLA, assignment, teams, AI summary, help center) | Yes | No (R&D console) |
| Work to maintain | Botpress hosts it | We host and maintain it |

## 6. What it would take to use Desk

If Desk is reconsidered later, the backend needs a bridge:

1. **Keep listening** to each open Botpress conversation (Botpress Chat API live stream, `GET /conversations/{id}/listen`) instead of only polling for 20 s after a customer message.
2. **Tell agent messages from bot messages** (not verified yet: check which field marks a Desk agent message).
3. Forward agent messages to the widget through our existing real-time stream, as agent bubbles with the agent's name.
4. Handle "agent joined" / "agent left" so the widget can switch to agent mode and back, and stop showing the bot fallback while an agent has the chat.
5. Decide what happens to our handover rules, waiting messages, ratings, file upload and audit, which live in our console today.
6. Accept that Desk agents only see fully masked text, or change what we send to Botpress (not recommended: the AI must not receive personal data).

## 7. Decision

Keep our own console for the R&D build:

- Everything the requirements ask for (handover rules, waiting messages, ratings, file upload, audit, masking levels) is built and tested there.
- Using Desk would mean building the bridge in section 6 and redoing most of those features on top of Desk.

Revisit Desk only if maintaining our own agent inbox becomes the bigger cost.

**Update (same day):** the bridge in section 6 was built, so a deployment can choose Desk by setting `AGENT_INBOX=botpress-desk`. Points 2 and 4 of section 6 and the open questions in section 9 were answered by a recorded live stream. See [agent-inbox-switch.md](agent-inbox-switch.md).

## 8. Things to watch while Desk stays unused

- **In console mode, do not join chats in Desk.** Joining silences the bot, and our widget then shows the fallback to the customer for every message. (In Desk mode this is handled.)
- Desk lists every bot conversation. Only give Desk access to people who may see all chats (the text is masked, but it is still customer conversation).
- Close the test tickets `tkt_h2qo6ryrvz` and `tkt_bxrs45iijf` in Desk.
- The Botpress bot name, description and greeting still come from the onboarding template; change them in Botpress Studio.

## 9. Not verified

- Whether the Chat API returns a Desk agent's message when it is read later (the test reply arrived outside our 20 s window). Check by replying on `tkt_h2qo6ryrvz` and reading `GET /conversations/{id}/messages` with that customer's user key.
- Which message field identifies a Desk agent versus the bot.
- Whether the bot resumes answering after the agent leaves or closes the ticket in Desk.
