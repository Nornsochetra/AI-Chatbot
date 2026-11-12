# AI Chatbot: Live Agent Handover

Status: R&D, built and verified end to end on 2026-11-10. See [technology.md](technology.md), [flow.md](flow.md), [widget.md](widget.md) and [botpress-integration.md](botpress-integration.md).

## 1. Goal

Requirements section III.G: a customer can reach a live agent from the chatbot (button or prompt), stays in the same chat window, and the bot stays silent while the agent is active. The live chat ends when the agent closes it, and the customer goes back to the bot.

**Update (2026-11-12): the customer can end the chat too.** One header button changes with the state: "Talk to an agent" (AI chat) → "Cancel request" (waiting, no confirmation) → "End chat" (with an agent, after an in-widget confirmation). Backend: `POST /api/v1/chat/sessions/{sessionId}/handover/close`, close reason `CUSTOMER_ENDED`, system code `CUSTOMER_ENDED` (followed by the agent rating card) or `REQUEST_CANCELLED` (no rating card). In Botpress Desk the agent gets a line saying the customer ended the chat or cancelled; see [agent-inbox-switch.md](agent-inbox-switch.md) for the Desk ticket that stays open.

**Decision (2026-11-10): only the agent closes a live chat.** The requirements (section II.B, "Live Agent Conversation Session Timeout & AI Fallback") ask for an automatic close after 2 minutes of customer inactivity. That is built but **off by default**; set `HANDOVER_AUTO_CLOSE=true` to switch it on if the bank confirms the requirements rule.

**Update (2026-11-12): BRD auto-route.** The chat goes to the live agent queue without the button when (`AUTO_ROUTE=true`, default):

| Trigger (BRD wording) | Detected by | Agent sees |
|---|---|---|
| "detects urgent support need" | Words for fraud, scams, lost / stolen cards, hacked accounts, unauthorized transactions, missing money (English and Khmer) | `[Live agent requested · URGENT] … routed automatically: the customer's message looks urgent …` |
| "detects that a human agent is required" | The customer asks for a person in words ("talk to a real person", "live agent", "និយាយជាមួយបុគ្គលិក"). "agent" alone is not enough: it also means a bank agent location | `… routed automatically: the customer asked for a person in their message` |
| "cannot answer" / "repeated failed attempts" | Two bot turns in a row where the bot gave no answer (our fallback reply) | `… routed automatically: the bot could not answer twice in a row` |

The urgent or asking message never goes to the bot; the agent gets it (secrets masked) when they join. The widget switches to waiting at once (`handoverStatus: WAITING` in the chat response). Limit: "cannot answer" only counts turns where Botpress sent no answer; a polite "I don't have that information" from the bot is an answer to us. Reasons are stored as `AUTO_URGENT`, `AUTO_ASKED_FOR_AGENT`, `AUTO_BOT_FAILED`.

**Update (2026-11-12): assignment rules (Botpress Desk).** Desk lets any agent join any conversation, so the backend decides which agent actions reach the customer. An agent's join or reply is passed on only when:

1. **The customer asked for an agent** and has not cancelled or ended (otherwise an agent could pull a customer back in after a cancel). `AGENT_TAKEOVER=true` brings back agents joining chats nobody asked for.
2. **No other agent has the chat:** one agent per chat.
3. **The agent has fewer than `AGENT_MAX_CHATS` live chats** (default 2, `0` = no limit). Chat customers reply slowly, so one agent can usually handle 2–3 at once; the limit is a setting so the call center can tune it.

Otherwise the customer sees nothing and the agent gets one "[Not connected]" note in Desk saying why (nobody asked / Sokha is already helping / you are at the limit). An agent at the limit can reply in the chat once one of their chats ends; the reply then connects them. When an agent joins a chat nobody asked for, Botpress pauses the bot until the ticket is closed; meanwhile the customer gets "The assistant will answer again in a moment" and can still tap "Talk to an agent", which the agent in the chat then picks up.

- **Agents are recognised by their Desk name** (events carry no agent id): the full name, lower-cased (`handover.agent_key`, Flyway V8). Two agents with the same full name count as one; give each a unique name in Desk.
- The BRD does not fix these rules; it lists "queue assignment, transfer, and closure behavior" as feasibility question 12 for the call center. Desk's own assignment features (claim, auto-assign, per-agent capacity), transfer and supervisors were not checked yet; a supervisor who types in a chat would be refused like any other second agent.
- The hidden console keeps its old behaviour (no limits).

## 2. Key decision: the agent side is ours, not Botpress

```
Customer (widget)
      │
Our backend ── BOT_ACTIVE ───► Botpress (AI answers)
      │
      └──── HUMAN_ACTIVE ───► Agent inbox (agent answers)
                                ├─ A. built-in agent console (/agent)   ← R&D, this document
                                ├─ B. Chatwoot                         (later option)
                                └─ C. the bank's own helpdesk           (if they have one)
```

- Botpress only does AI answers. During a handover our backend stops sending messages to Botpress.
- Botpress has its own helpdesk (Botpress Desk), but using it would move the required rules (timeout, rating, audit, choice of inbox) out of our control.
- The inbox sits behind `HelpdeskPort`, so the built-in console can later be replaced by Chatwoot or the bank's helpdesk without changing the widget or the chat flow.

## 3. States

```
            customer taps "Talk to an agent"            agent clicks Accept
 BOT ───────────────────────────────────────► WAITING ─────────────────────► ACTIVE
  ▲          (or accepts the prompt after                                        │
  │           the bot could not answer)                                          │
  │                                                                              │
  └──────────── CLOSED ◄─── agent clicks Close ─────────────────────────────────┤
                  ▲                                                              │
                  └──── no customer message for 2 minutes ───────────────────────┘
                        (only if HANDOVER_AUTO_CLOSE=true; off by default)
```

| Status | Bot | Customer messages go to | Customer sees |
|---|---|---|---|
| (none) | answers | Botpress | AI assistant |
| `WAITING` | silent | the queue (agent sees them on accept) | "Thank you! I'm connecting you to a live agent…" bubble, waiting banner with the queue position, and a "still busy" reminder every 60 s |
| `ACTIVE` | silent | the agent | "An agent joined the chat", green "Live agent" header |
| `CLOSED` | answers again | Botpress | "The agent ended the chat…" or "…ended because there was no reply for 2 minutes…" |

The open handover in the database (`WAITING` or `ACTIVE`) is the single source of truth for "a human is handling this chat". It survives a backend restart.

## 4. Rules

| Rule | How |
|---|---|
| Bot silent while an agent is waiting or active (requirement) | `ChatService` checks for an open handover before calling Botpress |
| One open handover per session | Asking again returns the open one |
| Agent must accept before replying | Reply on a `WAITING` handover → HTTP 409 |
| Only one agent can accept | Second accept → HTTP 409 |
| 2-minute inactivity close (requirement, **on by default**) | `HANDOVER_AUTO_CLOSE=false` leaves closing to the agent. When on, a check runs every 15 s and closes `ACTIVE` handovers with no customer message for `app.handover.inactivity-timeout` (default `2m`). The timer starts when the agent accepts, and every customer message resets it. Waiting in the queue does not count. The console then also shows "auto-close in …". |
| Customer is never left without an answer while waiting | Right away: a `WAITING` chat bubble. Banner: "You're number {n} in the queue". Every `app.handover.waiting-reminder` (default `60s`) without an agent: a `WAITING_REMINDER` bubble ("Our agents are still busy… an agent will be with you soon"). A check runs every 10 s; reminders stop when an agent accepts. See section 14. |
| "Are you still connected?" message (BRD message customization) | With auto-close on: after `app.handover.still-there-after` (default `1m`) without a customer message, a `STILL_CONNECTED` bubble asks the customer to reply, without the agent's name. Asked once per silence (a check every 10 s; `handover.last_reminder_at` keeps when); a customer message resets it. `0` = off |
| AI resumption message (requirement) | System event `INACTIVITY_CLOSED` (only with auto-close on), shown by the widget in the customer's language with how to reconnect |
| Agents may collect personal data, the AI may not (requirement) | Messages to the bot: full masking. Messages to an agent: **secrets only** (OTP, PIN, password, CVV, card numbers); phone numbers and IDs stay visible so the agent can verify or call back |
| Agent sees the conversation context (requirement) | The console shows the bot conversation before the handover (masked text) |
| Agent verifies the customer before account topics (requirement) | Reminder shown under the agent's reply box; done through the normal SOP |

## 5. API

### Customer (public, used by the widget)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/chat/handover` | `{ sessionId?, channel, reason? }` → `{ sessionId, state, handoverStatus, queuePosition }`. `reason`: `USER_REQUEST` (button) or `BOT_SUGGESTED` (prompt after a failed answer). Works before any message (creates the session). |
| `GET` | `/api/v1/chat/sessions/{sessionId}/events?after={cursor}` | **Real-time stream** (Server-Sent Events): `update` events (same body as `/updates`, event id = cursor) and `typing` events (`{ "who": "AGENT", "name": "<agent username>" }`). Resumes from `Last-Event-ID` on reconnect. |
| `POST` | `/api/v1/chat/sessions/{sessionId}/typing` | The customer is typing; pushed to the agent. 204; ignored when no agent is chatting. |
| `GET` | `/api/v1/chat/sessions/{sessionId}/updates?after={cursor}` | `{ state, handoverStatus, queuePosition, messages[], cursor }`. Agent messages and system events after the cursor. **Fallback** when the stream is not available. |
| `POST` | `/api/v1/chat/messages` | Unchanged. During a handover the reply is `null`, `state` is `HUMAN_ACTIVE`, and the message goes to the agent. |

System events (`sender: SYSTEM`, `text` is a code): `WAITING`, `WAITING_REMINDER`, `AGENT_JOINED`, `AGENT_LEFT`, `STILL_CONNECTED`, `INACTIVITY_CLOSED`. Agent messages and `AGENT_JOINED` / `AGENT_LEFT` / `INACTIVITY_CLOSED` carry `senderName`: **the agent's login username** (shown to the customer as "sokha joined the chat", "sokha is typing…", and on the agent's bubbles).

### Agent (requires the `AGENT` role, HTTP Basic)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/agent/handovers` | Open handovers (waiting and active), oldest first |
| `GET` | `/api/v1/agent/handovers/{id}` | Handover + bot transcript before it + live messages |
| `GET` | `/api/v1/agent/handovers/{id}/events?after={id}` | **Real-time stream**: `update` events (`{ handover, messages[], cursor }`) and `typing` events (`{ "who": "CUSTOMER" }`) |
| `POST` | `/api/v1/agent/handovers/{id}/typing` | The agent is typing; the customer sees "<username> is typing…". 204; 409 unless the chat is active |
| `GET` | `/api/v1/agent/handovers/{id}/messages?after={id}` | New live messages (catch-up after a reconnect) |
| `POST` | `/api/v1/agent/handovers/{id}/accept` | Take a waiting chat |
| `POST` | `/api/v1/agent/handovers/{id}/messages` | `{ text }` reply to the customer |
| `POST` | `/api/v1/agent/handovers/{id}/close` | End the live chat |

Errors: 400 invalid input, 401 not signed in (no `WWW-Authenticate` header, so browsers do not show their own login box), 404 unknown handover, 409 wrong state.

## 6. Data

Flyway `V2__handover.sql`:

- `handover`: one row per ticket: session, channel, status, reason, close reason, agent name, created / accepted / closed times, last customer activity.
- `handover_message`: customer, agent and system messages during the handover. The `BIGSERIAL` id is the cursor (stream event id / polling position). Customer text is stored after secrets-only masking. `sender_name` (Flyway `V4`) holds the agent's login username on agent messages and agent events, so the history keeps who answered.

The bot conversation stays in `chat_event` (full masking).

## 7. Built-in agent console (R&D)

- URL: `http://localhost:8080/agent` (served by the backend from `src/main/resources/static/agent/index.html`; plain HTML / JavaScript, no build step).
- Sign in with `AGENT_USERNAME` / `AGENT_PASSWORD` from `backend/.env`. If no password is set, one is generated at startup and printed in the log.
- Left: open chats (waiting / active, channel, reason, waiting time), refreshed every 3 s.
- Right: the bot conversation before the handover, the live chat, **Accept**, reply box (Enter to send), **Close chat**, and an idle timer ("Customer idle 0m 45s"; with auto-close on also "· auto-close in 1m 15s", from `autoCloseAt` in the API).
- All customer text is shown with `textContent` (never as HTML).
- The selected chat is live: customer messages appear instantly, "Customer is typing…" shows under the conversation, and the customer sees "<your username> is typing…" while you type. The stream is read with `fetch` (the browser's `EventSource` cannot send the login header); if it drops, the console catches up and reconnects after 3 s.

Limitations (R&D only):
- One shared agent account, no agent roles, supervisors, queue assignment or transfer.
- Basic auth credentials are kept in the browser tab's `sessionStorage` until sign-out.
- No notifications or sound for new chats; the agent watches the queue.

## 8. Widget changes

- "Talk to an agent" (header) and the prompt after a failed bot answer now start a real handover.
- Waiting banner, green "Live agent" header ("You're chatting with sokha"), agent bubbles labeled with the agent's login username (plain text), and the system events in Khmer / English ("sokha joined the chat").
- **"sokha is typing…"** with animated dots while the agent types (hidden 5 s after the last typing event or when the message arrives). The customer's own typing is sent to the agent (at most every 2 s).
- "Talk to an agent" and "New chat" are hidden while waiting or with an agent (a new chat would leave the open handover behind).
- **Real-time:** while waiting or with an agent, the widget keeps one `EventSource` connection open; messages arrive instantly. If the stream is not possible (old browser, blocking proxy), it falls back to polling `/updates` every 2.5 s. Repeats from stream + poll are dropped by the cursor.
- Mode, cursor and messages are kept in `sessionStorage`, so a page refresh continues the live chat.

## 9. Configuration

| Setting | Default | Meaning |
|---|---|---|
| `app.handover.auto-close-enabled` / `HANDOVER_AUTO_CLOSE` | `true` | Close a live chat automatically when the customer is silent (requirement). Off: only the agent closes it |
| `app.handover.still-there-after` / `HANDOVER_STILL_THERE_AFTER` | `1m` | With auto-close on: ask a silent customer "are you still connected?" after this long. `0`, or not shorter than the inactivity timeout = off |
| `app.agent.allowed-ips` / `AGENT_ALLOWED_IPS` | (empty = anywhere) | Agent pages and APIs only from these IPs / CIDR ranges, comma-separated (BRD internal access security). Others get 403 before the login. Behind a proxy, set `server.forward-headers-strategy` so the client address is used |
| `app.handover.inactivity-timeout` / `HANDOVER_INACTIVITY_TIMEOUT` | `2m` | How long without a customer message before the automatic close (only used when auto-close is on) |
| `app.handover.waiting-reminder` / `HANDOVER_WAITING_REMINDER` | `60s` | While no agent has accepted, send the customer a "still busy" message this often. `0` = off |
| `app.agent.username` / `AGENT_USERNAME` | `agent` | Console login |
| `app.agent.password` / `AGENT_PASSWORD` | (empty → generated) | Console login |

The widget text says "2 minutes"; change it in `widget/src/i18n` if the timeout changes.

## 10. How to try it

1. Start the backend (`cd backend`, `.\gradlew.bat bootRun`) and the widget (`cd widget`, `npm run dev`).
2. Customer: open `http://localhost:3000/`, open the chat, ask the bot something, then tap **Talk to an agent**.
3. Agent: open `http://localhost:8080/agent` in another browser window, sign in, click the waiting chat, **Accept**, and reply.
4. Customer: the agent's reply appears within a few seconds. Write back.
5. Agent: **Close chat** → the customer sees "The agent ended the chat…" and the bot answers again.
6. Silence: start another handover, accept it, and let the customer stay silent. The chat stays open (the console shows the idle time) until the agent clicks **Close chat**.
7. Optional, automatic close: set `HANDOVER_AUTO_CLOSE=true` in `backend/.env`, restart, and repeat step 6 → after 2 minutes the chat closes by itself.

## 11. Verification (2026-11-10)

Unit tests: backend 58 (48 masking incl. secrets-only level, 10 handover rules incl. "auto-close off leaves silent chats open"), widget 18 (reducer incl. live-agent states, API client).

End-to-end against the real backend, Postgres and Botpress (28 checks, all passed):

| Check | Result |
|---|---|
| Agent console page is served | ✅ |
| Bot answers before the handover | ✅ |
| Handover request → `WAITING`; asking again returns the same handover | ✅ |
| Customer message while waiting → no bot reply, goes to the agent | ✅ |
| Customer sees the `WAITING` event | ✅ |
| Agent API: 401 without login (no browser popup header), 401 wrong password | ✅ |
| Queue lists the chat; agent sees the bot transcript | ✅ |
| Agent sees "Call me at 012 345 678. My OTP is [REDACTED]" (phone kept, OTP masked) | ✅ |
| Reply before accept → 409; accept → `ACTIVE`; accept twice → 409; empty reply → 400 | ✅ |
| Customer receives `AGENT_JOINED` and the agent's Khmer / English message | ✅ |
| Polling with the cursor returns nothing twice | ✅ |
| Agent close → customer sees `AGENT_LEFT`, back to the bot, bot answers again | ✅ |
| Unknown handover → 404; malformed id → 400; handover without a session creates one | ✅ |
| Auto-close on: inactivity close (30 s in the test) → `INACTIVITY_CLOSED` after ~35 s, not before 30 s | ✅ |
| Auto-close off (default): accepted chat silent for 40 s with a 20 s timeout → still `ACTIVE`, `autoCloseAt` null; agent Close → `AGENT_LEFT`, back to the bot | ✅ |

Not checked yet: the widget and the console side by side in a browser (the browser tool disconnected during this round; the widget logic is covered by unit tests and the API by the end-to-end run).

## 12. Open items

0. ~~Decide the close rule~~: the BRD's 2-minute automatic close is the default (2026-11-12), with "are you still connected?" at 1 minute.
1. **Which inbox for production:** the bank's helpdesk (name and API still unknown), Chatwoot, or a hardened version of the built-in console.
2. **Real agent accounts:** per-agent logins or SSO with 2FA (required for internal access security), supervisors, assignment and transfer.
3. **Auto-routing without a click:** the requirements also want automatic handover on repeated failures or urgent requests. Today the customer confirms the prompt. A fully automatic route needs the Botpress "needs human" signal (open question in [botpress-integration.md](botpress-integration.md)).
4. ~~Post-chat rating~~: done, see [rating.md](rating.md).
5. ~~Faster delivery~~: done with Server-Sent Events (see section 13).
6. **Several backend instances:** the inactivity check would run on each instance, and live pushes only reach screens connected to the instance where the change happened. Fine for one instance; for several, use a lock (ShedLock) for the check and a shared channel (e.g. Redis pub/sub) for the pushes.
7. **Real display names:** the customer sees the agent's login username. With real agent accounts, use a proper display name (e.g. first name only) instead of the login.

## 13. Real-time push and typing (added 2026-11-10)

How it works:

```
Agent replies / accepts / closes, customer writes
   → HandoverService saves the message and announces the change
   → after the database commit, LiveUpdateService pushes it to every open screen of that chat
        widget (topic "session:<id>")        agent console (topic "handover:<id>")
Typing: POST …/typing → pushed straight to the other side (not stored)
```

- Each connection remembers the last message id it received, so a push sends only what is new and never the same message twice.
- On (re)connect the first event is a catch-up from the given cursor / `Last-Event-ID`.
- A keep-alive comment every 20 s keeps idle connections open through proxies; connections end after 30 min and the browser reconnects by itself.
- Pushes happen only after the database commit, so a pushed message is always really saved.
- R&D limit: connections are held in memory by one backend instance (see open item 6).

Verified end to end against the real backend (16 checks, all passed): both streams open with the current state; CORS for the widget; 401 for the agent stream without login; "agent joined" pushed with the agent's login name in 117 ms; agent typing → customer in 85 ms; customer typing → agent in 32 ms; agent reply → customer in 82 ms; customer message → agent in 30 ms; event ids = cursor; reconnect with `Last-Event-ID` gets only newer messages; "agent left" + back to the bot pushed in 75 ms; typing rules (204 ignored without an agent, 409 for a closed chat); no message pushed twice. Unit tests: backend 81 (handover 13, incl. agent name on messages and change announcements), widget 29 (incl. repeat dropping and agent name).

Not checked yet: the widget and console side by side in a browser (browser tool disconnected).

## 14. Messages while waiting for an agent (added 2026-11-11)

Before this, the customer only saw a short grey line "Connecting you to a live agent…" and then silence until an agent accepted. Now:

| When | Customer sees (Khmer / English) |
|---|---|
| Right away | A chat bubble (like a bot reply): "Thank you! I'm connecting you to a live agent. An agent will join shortly — you can keep typing, and the agent will see your messages." |
| While waiting | Banner: "Please wait, an agent will join shortly. You can keep typing; the agent will see your messages." (Until 2026-11-12 it showed "You're number {n} in the queue"; removed: the BRD only asks for a handover state, and the number counted waiting requests, not free agents, so it misled. The API still returns `queuePosition`.) |
| Every 60 s with no agent | A chat bubble: "Our agents are still busy helping other customers. Thank you for your patience — an agent will be with you soon." |
| Agent accepts | Reminders stop; "sokha joined the chat." |

How it works:

- `WAITING` and `WAITING_REMINDER` are system codes; the widget shows them as bot-style bubbles (not grey status lines) and never with an agent name.
- **Queue position** = number of `WAITING` handovers created earlier + 1. Returned by `POST /handover`, `/updates` and the stream; `null` once an agent has the chat. Not shown in the widget any more (see above).
- **"New chat" cancels an open request or live chat** (2026-11-12). Before, it only cleared the widget, and the request stayed waiting on the server forever, holding a place and counting towards an agent's limit.
- **Reminder:** `HandoverService.remindWaiting()` runs every 10 s and adds `WAITING_REMINDER` to each `WAITING` handover whose last reminder (or creation) is older than `app.handover.waiting-reminder`. `handover.last_reminder_at` (Flyway `V6`) keeps the time of the last one, so a restart does not repeat it early. With the 10 s check, a reminder can arrive up to 10 s after the interval.
- The agent console shows the reminder in the chat history as "Customer still waiting — reminder sent".
- When a customer leaves the queue (an agent accepts, or a waiting chat is closed), `QueueChangedEvent` pushes the new position to every customer still waiting, so the banner number drops right away.

Verification (2026-11-11), against the real backend with `HANDOVER_WAITING_REMINDER=5s` (14 checks, all passed): handover response has the queue position; a second customer is one place behind; `WAITING` arrives at once without an agent name; no reminder before the interval; a reminder arrives while still waiting, without a name; after the agent accepts customer A, A has no position and customer B moves up one place; A gets no more reminders while B keeps getting them; the agent sees the reminder in the history. Plus a stream check: customer B's open stream received position 2, then 1 the moment customer A was accepted. Unit tests: 8 new backend (reminder timing, repeat, off with `0`, queue position, when the queue moves), 2 new widget (queue position, reminder message). Totals: backend 110, widget 35.
