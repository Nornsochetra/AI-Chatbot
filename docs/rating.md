# AI Chatbot: Post-Chat Rating

Status: R&D, built and verified end to end on 2026-11-10. See [handover.md](handover.md), [widget.md](widget.md) and [technology.md](technology.md).

## 1. Goal

Requirement "Post-Chat Service Rating" (section II.B): prompt the customer to rate their support experience, for chats handled by the AI and by a live agent.

| Chat handled by | When the rating is asked (requirement) | How it is built |
|---|---|---|
| Live agent | Immediately after the agent (or the customer) ends the chat | The widget shows the rating card as soon as it receives the "agent ended the chat" event |
| AI only | After a scheduled delay | The widget shows the rating card after the customer has been quiet for **60 seconds** after a bot answer (configurable) |

The requirements also say agents must tell the customer about the rating before ending the session: the agent console shows that reminder under the reply box.

## 2. What the customer sees

```
┌──────────────────────────────────────────┐
│ How was your chat with our agent?        │   (AI: "How helpful was the AI assistant?")
│ ★ ★ ★ ★ ☆                                │   tap a star (1–5)
│ [ Tell us more (optional)            ]   │   comment box appears after a star is chosen
│                  Not now   [Send rating] │
└──────────────────────────────────────────┘
          → "Thank you for your feedback!"
```

- Khmer and English (follows the language toggle).
- "Not now" hides the card; it is not shown again in that session.
- If sending fails, the card shows an error and the customer can press "Send rating" again.
- Accessible: the stars are a radio group with labels ("Rate 4 out of 5").

## 3. Rules

| Rule | How |
|---|---|
| One rating per finished agent chat | Unique per handover in the database; a second one → HTTP 409 |
| One AI rating per session | Unique per session in the database; a second one → HTTP 409 |
| The AI card is shown at most once per session, and not after an agent chat | The widget remembers that a rating card was shown (`ratingAsked`) |
| An agent chat can only be rated after it is closed | AGENT rating while the chat is open, or without any agent chat → 409 |
| An AI rating needs a bot conversation | AI rating for a session that never talked to the bot → 409 |
| Comments are masked like bot messages | Full masking (OTP, PIN, password, card / account numbers, phone numbers) before storing, because staff read them later |
| Score 1–5, comment up to 500 characters | Validated by the backend (400) and the widget |
| Double-click / two tabs | A duplicate at the same moment hits the unique index → 409; the widget treats 409 as "already rated" and shows the thank-you |

## 4. API

| Method | Path | Body | Result |
|---|---|---|---|
| `POST` | `/api/v1/chat/sessions/{sessionId}/rating` | `{ "type": "AGENT" \| "AI", "score": 1-5, "comment"?: "..." }` | `{ id, type, score, comment, createdAt }` (`comment` is the stored, masked text) |

`AGENT` rates the session's most recently closed agent chat; the widget does not need to know the handover id.

Agent console: `GET /api/v1/agent/handovers/{id}` now includes `rating: { score, comment, createdAt }` (null until the customer rates).

Errors: 400 invalid score / type / comment, 409 already rated or nothing to rate.

## 5. Data

Flyway `V3__rating.sql`, table `rating`:

| Column | Meaning |
|---|---|
| `id` | UUID |
| `session_id`, `channel` | Which chat, website or mobile app |
| `type` | `AI` or `AGENT` |
| `handover_id` | The rated agent chat (AGENT only) |
| `score` | 1–5 (checked by the database) |
| `comment` | Masked text, or empty |
| `created_at` | When |

Unique indexes: one rating per `handover_id`; one `AI` rating per `session_id`. Ready for the dashboard (average score by type, channel, agent, period).

## 6. Agent console

- Under the reply box: "Before closing, tell the customer a short rating request will follow."
- After closing, the selected chat shows "Waiting for the customer's rating…", then "Customer rating: ★★★★★ 5/5 — "comment"" (refreshes every 2 s while the chat stays selected).

## 7. Configuration

| Setting | Where | Default | Meaning |
|---|---|---|---|
| `NEXT_PUBLIC_AI_RATING_DELAY_SECONDS` | `widget/.env.local` | `60` | Quiet time after a bot answer before the AI rating card appears. The requirements do not give a number; still to be decided. |

The delay restarts whenever the conversation moves (new message or answer).

## 8. How to try it

1. Start the backend and the widget (see [technology.md](technology.md), "Local setup").
2. **AI rating:** open `http://localhost:3000/`, ask the bot one question, then wait 60 seconds without typing → the rating card appears. Choose stars, add a comment, press "Send rating" → "Thank you for your feedback!". For a faster test, set `NEXT_PUBLIC_AI_RATING_DELAY_SECONDS=10` in `widget/.env.local` and restart the widget.
3. **Agent rating:** in a new chat (or a new browser tab), tap "Talk to an agent". In the agent console, accept, reply, then "Close chat" → the customer immediately sees "The agent ended the chat…" and the rating card. Rate it → the agent console shows the stars and the comment.

## 9. Verification (2026-11-10)

Unit tests: backend 65 (48 masking, 10 handover, 7 rating rules), widget 24 (reducer incl. rating card states and the "once per session" rule, API client incl. 409 → "already rated").

End-to-end against the real backend, Postgres and Botpress (17 checks, all passed):

| Check | Result |
|---|---|
| AI rating accepted after a bot chat; a second one → 409 | ✅ |
| AGENT rating without an agent chat → 409; while the agent chat is open → 409 | ✅ |
| Customer receives `AGENT_LEFT` after the agent closes (widget shows the card) | ✅ |
| AGENT rating accepted; a second one for the same chat → 409 | ✅ |
| Comment "Great help! My OTP is 123456, call me 012 345 678" stored as "Great help! My OTP is [REDACTED], call me [REDACTED]" | ✅ |
| Agent console: no rating before, the rating after | ✅ |
| Score 0 / 6, missing type, unknown type, comment over 500 → 400 | ✅ |
| AI rating for a session that never chatted → 409 | ✅ |

Not checked yet: the rating card clicked through in a browser (the browser tool is disconnected; the card logic is covered by unit tests, the API by the end-to-end run).

## 10. Open items

1. **AI rating delay:** confirm the delay (60 s is an R&D default), and whether the AI rating should also be asked when the customer closes the widget.
2. **Rating for chats that end without the widget open:** if the customer already left when the agent closes, no rating is collected. A follow-up link (e.g. by SMS) would need customer contact data, which the AI must not collect.
3. **Dashboard:** average score, response rate and comments by type, channel, agent and period (admin portal).
4. **Low-score alerts:** e.g. notify a supervisor for 1–2 star agent ratings (later, with real agent accounts).
