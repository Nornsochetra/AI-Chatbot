# AI Chatbot: Switchable Agent Inbox

Status: **implemented and tested end to end on 2026-11-11** (steps 0–3 of section 7). Background: the Botpress Desk evaluation in [botpress-desk.md](botpress-desk.md).

**Update (2026-11-11): Botpress Desk is now the default, and our console is switched off.** The console code is kept, not removed. With the default `botpress-desk`:

- the console page (`/agent`, `/agent/index.html`) answers 404 (`ConsolePageFilter`);
- the console API (`AgentHandoverController`: queue, accept, reply, typing, close, live stream) is not loaded, so it answers 404 and is not listed in Swagger;
- `BuiltInHelpdesk` is not loaded; the Desk classes are.

Still on in Desk mode: the agent sign-in, the secure file page `/agent/file.html` and its file endpoints (`AgentAttachmentController`), which Desk messages link to. To bring the console back, set `AGENT_INBOX=console` and restart.

## 1. Goal

Let a deployment choose where live agents work, with one setting:

- **`botpress-desk`** (default): Botpress Desk (`desk.botpress.cloud`).
- **`console`** (kept, switched off): our own agent console at `/agent` ([handover.md](handover.md)).

The backend already has the switch point. Handover code talks to the agent inbox only through the `HelpdeskPort` interface (`ticketOpened`, `customerMessage`, `ticketClosed`). Today its only implementation is `BuiltInHelpdesk`. Desk mode adds a second implementation, picked by the setting.

## 2. The setting

```yaml
app:
  handover:
    inbox: ${AGENT_INBOX:botpress-desk}   # botpress-desk | console
```

The default is `botpress-desk`. To use our console instead, put `AGENT_INBOX=console` in `backend/.env` and restart the backend.

Desk mode also needs `PUBLIC_BASE_URL`: the address agents use to reach this backend (default `http://localhost:8080`). File links posted to Desk point there.

## 3. Main idea: our backend stays in charge in both modes

Our handover table stays the source of truth (WAITING → ACTIVE → CLOSED). Only the place where agents work changes.

| | `console` (today) | `botpress-desk` |
|---|---|---|
| Where agents work | Our `/agent` page | Botpress Desk |
| Customer taps "Talk to an agent" | Handover WAITING | Same, and a "[Live agent requested]" line is posted to the conversation (the backend cannot flag the Desk ticket itself, see section 6) |
| Waiting message, queue position, reminders | Yes | Yes (unchanged, from our table) |
| Customer messages during the chat | Saved in our table | Saved in our table **and** posted to the Botpress chat so the Desk agent sees them. Messages written while waiting are sent in one block when the agent joins (before that, the bot would answer each one) |
| Agent replies | Typed in our console | Typed in Desk; the backend listens to the Botpress chat and passes them to the widget as agent bubbles |
| Agent joins | Accept button | Desk "agent assigned" event marks it ACTIVE and shows "*agent* joined" (the agent's first name from Desk). An agent can also join a chat nobody escalated: the handover then starts as ACTIVE with reason `AGENT_INITIATED` |
| Chat ends | Close button | Ticket closed in Desk; the bot answers again afterwards |
| Rating after the chat | Yes | Yes (ours, unchanged). Desk's own rating request is ignored |
| What agents see of customer text | Secrets-only masking | **Full masking**, because the text passes through Botpress, and the AI must not receive personal data |
| File upload | Yes | Yes, as **"link, not image"** (section 5): the file stays on our server; Desk only gets a text notice with a link to our own file page |
| Typing indicators | Yes | Off at first |
| `/agent` console page | On | Read-only (banner "Botpress Desk mode"; accept, reply and close answer 409); the file page from section 5 stays on |

## 4. What was built

**Backend** (Desk classes only load when `app.handover.inbox=botpress-desk`):

| Piece | What it does |
|---|---|
| `BotpressDeskHelpdesk` (`handover/service`) | The second `HelpdeskPort`. Posts to the session's Botpress conversation: the "[Live agent requested]" line, customer messages (fully masked), the messages written while waiting (one block when the agent joins), file notices, and a "[Chat ended]" line when our side closes the chat (customer inactivity) |
| `BotpressDeskListener` | Keeps the Botpress live stream (`GET /conversations/{id}/listen`) open for **every** chat session, because a Desk agent can join any bot chat. One virtual thread per session; stops once the session has expired and no handover is open |
| `BotpressDeskEvents` | Turns stream events into handover changes: agent assigned → `accept` (or `startByAgent`), agent reply → `agentMessage`, ticket closed → `close` |
| `BotpressMessages` (`chat/service`) | Tells bot answers, Desk agent replies and Desk notices apart (section 6). `BotpressBotEngine` now ignores agent replies and notices when it waits for a bot answer |
| `BotConversations` (`chat/service`) | Opens a session's Botpress conversation on first use and publishes `BotConversationOpenedEvent`, which starts the listener |
| `ChatService` | If an agent takes the chat over while we wait for the bot, the customer gets no "Sorry, I can't answer" fallback; the message is recorded in the handover |
| `HandoverService` | New `startByAgent` (handover straight to ACTIVE, reason `AGENT_INITIATED`) and `recordCustomerMessage` (record without forwarding) |
| `GET /api/v1/chat/config` | `{ agentInbox, agentMayJoinAnytime }` for the widget and the console |
| `AgentHandoverController` | Accept, reply and close answer 409 in Desk mode |
| `/agent/file.html` | The file page of section 5 |

**Widget:** reads `/api/v1/chat/config`. When `agentMayJoinAnytime` is true, the live stream stays open while the bot is answering too, so an agent who joins on their own appears straight away.

## 5. Customer files in Desk mode: "link, not image"

Customers may send personal images (ID card, screenshots with account details). **The image never goes into Botpress.** It stays on our server, and Desk only receives a text line with a link to our own secure file page.

### Why not send the image to Botpress

- **Botpress Desk runs AI on the conversation.** In the Desk test it wrote an AI summary of the ticket by itself. An ID or card photo in the chat could be read by AI, which the requirements forbid.
- **The image would be stored in Botpress Cloud** (outside the country). We could not delete it after the retention period, and we could not audit who viewed it.

### Flow

```
Customer (widget)        Our backend                         Botpress Desk           Agent
      | upload photo ───►| type / size check, virus scan,     |                       |
      |                  | save in OUR database               |                       |
      |                  | post a TEXT message only ─────────►| "📎 Customer sent a   |
      |                  |                                    |  file (image, 240 KB). |
      |                  |                                    |  Open: https://…/f/x7k"|
      |                  |                                    |                  click ──► our file page
      |                  |◄──────────── agent signs in (our login / SSO), sees the image
      |                  | audit: who viewed, when            |                       |
      |◄── "removed" ────| agent clicks Delete on our page    |                       |
```

1. **The customer uploads in our widget,** exactly as today ([attachments-simple.md](attachments-simple.md)): JPG / PNG / PDF up to 5 MB, content-type check, virus scan, saved in our database. Uploads stay allowed only while an agent has the chat, so the bot is already paused when the notice is posted.
2. **The backend posts only a text line** to the Botpress conversation, as a customer message:
   `📎 Customer sent a file (image, 240 KB). Open it securely (agent sign-in required): <PUBLIC_BASE_URL>/agent/file.html?f=<handover id>.<file id>`
   (One parameter without `&`: Desk shows an `&` in a link as `&amp;`, which broke the first two-parameter links. The page still opens those older links.)
   - **No original file name.** A name like `ID_<customer name>.jpg` would leak personal data. Only the type and size are sent.
   - **The link holds random IDs,** not the file.
3. **The agent clicks the link in Desk** and our own file page opens:
   - **It requires the agent to sign in** with our agent login (later: SSO, so it is one click). A leaked link alone shows nothing.
   - **Optional:** reachable only from the internal network, so the link is useless outside even with a login.
   - **It shows the image or PDF with a Delete button** (with the same confirmation pop-up as the console).
4. **Everything else in today's file feature keeps working:**
   - the audit log records who viewed or deleted the file, and when;
   - the customer sees "Removed by the agent";
   - automatic deletion after the retention period (`ATTACHMENT_RETENTION_DAYS`, default 30);
   - a removed or expired file shows "File removed" on the page.
5. **What Botpress and its AI see:** only "Customer sent a file (image, 240 KB)" and a link they cannot open.

### Options considered

| Option | Verdict |
|---|---|
| **Link to our login-protected file page** | **Recommended.** Personal data stays with us; audit, delete and retention unchanged |
| Signed link that works without login, expires after ~15 min | Not recommended. Anyone with the link can open it inside that window, and the link stays in Botpress |
| Send the image itself through the Chat API (image payload) | Rejected. Personal data reaches the AI platform and its cloud; no delete or audit on our side |
| Turn file upload off in Desk mode | Safe fallback, but agents cannot verify ID or see screenshots |
| Run the OCR + masking flow ([attachments.md](attachments.md)) before forwarding | Possible later on top of the recommended option; not needed if the image never leaves our server |

### What it uses

- The file page `/agent/file.html` calls the existing agent file endpoints (`GET` / `DELETE /api/v1/agent/handovers/{id}/attachments/{fileId}`) with the agent login, so audit, delete and retention are unchanged. The login is kept for the browser tab only.
- Widget: 📎 works as in console mode (only while an agent has the chat).

### Questions for Compliance / Security

1. May agents ask customers for ID photos at all? (Already open in [attachments-simple.md](attachments-simple.md).)
2. Do agents work on the internal network, so the file page can be internal-only?
3. Is there SSO for agents, or do Desk agents get a separate login for the file page?
4. Is a text notice with a link (no personal data) acceptable in Botpress Cloud?

## 6. Botpress behaviour (step 0, verified 2026-11-11)

A probe conversation (ticket `tkt_ek6zuy9tar`) recorded the raw live stream while an agent joined, replied and closed in Desk.

| Question | Result |
|---|---|
| 1. Which field tells a Desk agent from the bot? | Both post with the **bot's user id**. Bot answers carry `metadata.streamId`; agent replies carry `metadata.adminName` (agent's full name) |
| 2. Does the live stream send agent messages and join / close events? | **Yes**, all as `message_created` with `metadata.hitl.kind`: `ticket_opened`, `agent_assigned` (with `agentName`), `csat_request` (Desk's rating question, a `choice` message), `ticket_closed`. Also `typing_started` / `typing_stopped`, `message_stream_delta` and `data: ping` every ~30 s |
| 3. Can the backend flag a ticket? | **Not through the Chat API.** Desk opens the ticket when an agent joins. Workaround: the "[Live agent requested]" line, which agents see in the live chat list. Proper fix later: set up escalation in Botpress Studio |
| 4. Does the bot answer again after the close? | **Yes.** Desk posts "*agent* closed the conversation. I will continue assisting you." and the next customer message got a bot answer |
| 5. Is a link in a message clickable in Desk? | Yes: the file link opened our file page |

## 7. Steps and rough effort

| Step | What | Status |
|---|---|---|
| 0 | Test the unknowns in section 6 | Done |
| 1 | Setting, `BotpressDeskHelpdesk`, listener, text chat both ways, no bot fallback while an agent has the chat | Done |
| 2 | Join and close detection, agent-initiated takeover, widget config | Done |
| 3 | File page and "link, not image" notices (section 5) | Done |
| 4 | Optional: typing indicators in Desk mode | Later |

### End-to-end test (2026-11-11, backend on port 8081 with `AGENT_INBOX=botpress-desk`)

| Check | Result |
|---|---|
| `GET /api/v1/chat/config` | ✅ `BOTPRESS_DESK`, `agentMayJoinAnytime: true` |
| Chat A: bot answer, then "Talk to an agent" | ✅ WAITING, queue #1, waiting message and reminders; "[Live agent requested]" line in Desk |
| Message while waiting ("my phone is 012 345 678") | ✅ Not sent to Botpress until the agent joined, then sent in one block with the phone number masked |
| Agent joins chat A in Desk | ✅ Widget: "*agent* joined" with the agent's first name |
| Customer uploads an image | ✅ Desk shows only "📎 Customer sent a file (image, 70 B)" and the link, no file name |
| Agent opens the link | ✅ Our file page asked for the agent login and showed the image; audit `ATTACHMENT_VIEWED` |
| Agent deletes the file on our page | ✅ Widget: "file removed"; audit `ATTACHMENT_DELETED` |
| Agent reply in Desk | ✅ Shown in the widget as an agent message |
| Agent closes the ticket | ✅ Widget: agent left + rating card; the next customer message got a bot answer |
| Chat B: agent joins a chat nobody escalated | ✅ Handover opened as ACTIVE (`AGENT_INITIATED`); join, reply and close all reached the widget; bot answers again after the close |
| Backend log | ✅ No errors |

Unit tests: `BotpressDeskHelpdeskTest`, `BotpressDeskEventsTest` (event shapes copied from the recorded stream), `BotpressMessagesTest`, plus new `HandoverServiceTest` cases. Backend 132 tests, widget 35, all passing.

## 8. Trade-offs to know up front

- **Desk agents see less text:** phone numbers and IDs are masked, because everything posted to Botpress is fully masked.
- **Files need a second click and a sign-in** on our file page instead of showing inline in Desk.
- **Desk shows every bot chat to agents,** not only chats where the customer asked for one.
- **Two modes to test and maintain** instead of one.

## 9. Known limits and follow-ups

- **Desk sends formatted text as markdown.** Bold text typed in Desk reached the widget as `**Hi from Desk**`, because agent bubbles are shown as plain text on purpose. Follow-up: strip or safely render markdown in Desk agent messages.
- **Events sent while a stream reconnects are missed.** An agent reply still counts as joining, but a missed close leaves the handover open until the next close.
- **Sessions are in memory.** After a backend restart, open Desk chats lose their Botpress link (same limit as the bot chat today).
- **One stream per chat session** in Desk mode. Fine for R&D volume; at scale, check Botpress limits or use a Botpress webhook / integration instead.
- **If our side closes the chat** (auto-close, off by default), the Desk ticket stays open and the bot stays silent until the agent closes it; the agent gets a "[Chat ended]" line asking them to.
- **The `/agent` console on an older backend** does not know the `AGENT_INITIATED` reason; restart every backend on the same database after upgrading.
- **The bot's own text** (name, greeting, knowledge) comes from Botpress Studio, not from this repo.
- **Agents sign in again to open each file in a new tab.** The file page cannot reuse the Desk login (Desk does not share its login with outside pages). This is intended: the link alone must never open a customer file. Planned: one sign-in per shift through the admin portal, see [flow.md section 8](flow.md#8-admin-portal).
