# AI Chatbot: Flows

Status: R&D draft. See [technology.md](technology.md) for the stack.

## 1. Text chat

```
Widget → Spring Boot BFF
   ├─ Input guardrail: mask OTP / PIN / CVV / card numbers
   ├─ Session state HUMAN_ACTIVE? → send to agent, skip Botpress
   ▼
Botpress Chat API: post message (user + conversation IDs from the session store)
   ▼
Botpress: Knowledge Base search → LLM → reply (same language)
   ▼
Spring: output check → log event to Postgres → stream to widget (SSE)
```

- Current R&D implementation (verified): the reply is returned in the JSON response of `POST /api/v1/chat/messages`, not streamed yet. Our backend reads the bot reply by polling the Botpress messages list. SSE streaming and the output check are planned. Details: [botpress-integration.md](botpress-integration.md).
- Context for follow-ups is kept by Botpress, per channel conversation.
- If Botpress is slow or down, the circuit breaker returns a fallback message and offers the live agent.
- The bot must not give customer-specific financial data or personalized eligibility, rate or approval claims. This is configured in Botpress instructions and checked on output.

## 2. Voice input (speech-to-text)

```
User taps mic → widget records audio → Spring BFF
   ▼
Gemini API: audio → text (Khmer or English)
   ▼
Text continues through Flow 1 (guardrail → Botpress → reply)
```

- Typing stays available if voice fails.
- Masking runs on the transcript, because spoken digits can still contain card numbers.
- Raw audio is not stored.
- Current R&D implementation: the transcript goes into the customer's input box; they check it and press Send, so it follows Flow 1 exactly. Gemini is asked to write numbers as digits so masking catches them. Details: [voice.md](voice.md).

## 3. Voice output (read-aloud)

```
User taps speaker on a bot reply
   ▼
Spring BFF → Gemini TTS (reply text → audio)
   ▼
Widget plays audio; the text stays visible
```

- Audio is generated only when the user taps, to limit cost and latency.
- If TTS fails, the user keeps reading the text.
- Current R&D implementation: the widget sends only the session id and the reply id; the backend reads aloud that session's stored bot reply (markdown removed), so arbitrary text cannot be synthesized. Details: [voice.md](voice.md).

## 4. Live agent handover

```
Trigger: user clicks the button | Botpress signals "needs human"
         (cannot answer, repeated failure, urgent, explicit request)
   ▼
Spring sets session HUMAN_ACTIVE (stops forwarding to Botpress)
   ▼
HelpdeskPort.createConversation (transcript + channel + attachments)
   ▼
Agent ⇄ Spring relay ⇄ widget (customer stays in the widget)
   ▼
Agent closes the chat → rating prompt immediately
2 min customer inactivity (JobRunr) → close ticket → BOT_ACTIVE → resume message
```

- Agents verify the customer through the normal support process before discussing account-specific information.
- Agents may collect personal data. The AI chatbot must never collect it.
- The agent warns the customer before closing, so the rating prompt is expected.
- Current R&D implementation (verified): the built-in agent console at `/agent`, a `WAITING → ACTIVE → CLOSED` handover in the database, the widget polling every 2.5 s, and only the agent closes the chat by default (the required 2-minute auto-close is built but off; `HANDOVER_AUTO_CLOSE=true` switches it on, checked every 15 s instead of JobRunr). Messages to the agent are masked for secrets only, so phone numbers and IDs stay visible. Rating is not built yet. Details: [handover.md](handover.md).

## 5. Post-chat rating

| Case | When the prompt is sent |
|---|---|
| Agent-handled chat | Immediately when the agent or user ends the chat |
| AI-handled chat | After a scheduled delay |

Current R&D implementation (verified): the widget shows a 1–5 star card with an optional comment. Agent chats get it as soon as the "agent ended the chat" event arrives. AI-only chats get it after 60 seconds of quiet following a bot answer (`NEXT_PUBLIC_AI_RATING_DELAY_SECONDS`), at most once per session. Comments are masked. Details: [rating.md](rating.md).

## 6. Attachments

```
User taps attachment → camera / photo / file
   ▼
Check type and size → ClamAV scan → store (FileStoragePort)
   ▼
Shown in chat; agent views it through an authenticated, audited link
```

- Unsupported types or sizes are rejected with a friendly message, and the user can keep typing.
- Files are renamed to random IDs and never served directly.
- Files are deleted after the retention period.

## 7. Knowledge content

```
They provide FAQ / product / service content
   → loaded into the Botpress Knowledge Base (manually, or by us through the Botpress API)
   → the bot answers only from that content
```

We do not manage chunking, embeddings or a KB approval portal.

## 8. Admin portal

```
Admin logs in (2FA, IP whitelist)
   ├─ Dashboard: usage, topics, unanswered questions, handovers,
   │             language split, voice usage, channels, ratings
   ├─ Reports: filter and export (their template layout)
   └─ Audit log: logins, transcript access, attachment access, config changes
```

Data comes from our Postgres events, optionally enriched from Botpress analytics.

## 9. Session states

```
        ┌─────────── user clicks button / Botpress "needs human" ───────────┐
        ▼                                                                    │
  BOT_ACTIVE ───────────────────────────────► HUMAN_ACTIVE
        ▲                                          │
        └── agent closes / 2 min inactivity ◄──────┘
```

## 10. Out of scope (from the requirements)

- Account balance, transaction history, card status and loan balance inquiries.
- Fund transfer or payment execution.
- OTP / PIN / password / CVV handling.
- Personalized financial advice and customer-specific product recommendation.
- Direct core banking integration, complaint module.
- Facebook Messenger and Telegram chatbot (Messenger is inbox-only).
- Cross-channel conversation history.
