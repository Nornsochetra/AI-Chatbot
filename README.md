# AI Chatbot (R&D)

A personal research and development project: an AI customer-support chatbot with live agent handover, built for a website and a mobile app (banking customer-support use case).

Customers chat through a custom widget. A Spring Boot backend sits between the widget and the external services. It masks sensitive input, forwards questions to Botpress (knowledge base + LLM), handles voice through Gemini, hands chats over to a live agent, and stores chat events, handovers, ratings and files in PostgreSQL.

```
 Website (floating widget)        Mobile app (webview)
            \                          /
             \______ Next.js widget ___/
                          |
                Spring Boot backend (BFF)
   ┌───────────┬──────────┼───────────┬─────────────┐
 Session     Guardrails  Bot engine  Speech        Handover
 (memory)    (masking)   (Botpress)  (Gemini)      (Botpress Desk)
                          |
     PostgreSQL: chat events, handovers, ratings, attachments, audit
```

## Features

| Feature | Status |
|---|---|
| Text chat through Botpress (Khmer and English) | Done |
| Input masking (OTP, PIN, CVV, passwords, card / account numbers, phone numbers) | Done |
| Live agent handover in **Botpress Desk** (default); customer files stay on our server and open through a secure link | Done |
| Waiting-for-agent messages: welcome message, "please wait" banner, "agents are busy" reminder | Done |
| Customer can cancel the request while waiting, or end the live chat (with confirmation) | Done |
| Auto-route to an agent: urgent messages (fraud, lost / stolen card, hacked), asking for a person in words, two failed bot answers in a row | Done |
| Messages follow the detected language (Khmer / English); goodbye message after a live chat | Done |
| Chat history retention (deleted after N days) | Done |
| Real-time updates (Server-Sent Events) and typing indicators on both sides | Done |
| Post-chat rating (agent and AI chats) | Done |
| Voice input and read-aloud (Gemini) | Done, live-tested |
| File upload in live agent chats (JPG / PNG / PDF up to 5 MB; agent opens a file by its name, deletes with confirmation; retention) | Done |
| File upload in the AI chat: text read on our server (OCR), masked, shown to the customer, only the masked text goes to the bot; the agent gets the file after handover | Done; OCR off until the language files are added |
| Virus scan for uploads (ClamAV) | Built, off until ClamAV is installed |
| Audit log | Started (file views and deletes are recorded) |
| Admin portal (dashboard, audit screen, admin login) | Not started |

## Project structure

```
ai-chatbot/
 ├─ backend/      Spring Boot 3 (Java 21, Gradle Kotlin DSL), PostgreSQL + Flyway
 ├─ widget/       Next.js 16 chat widget (React 19, TypeScript)
 ├─ docs/         Design notes: technology, flows, Botpress, handover, rating, voice, attachments, widget
 ├─ tools/        voice_live_check.py: live Gemini voice check against a running backend
 └─ knowledge.txt Sample knowledge base content (Khmer) for Botpress
```

## Prerequisites

- Java 21
- PostgreSQL (native install, no Docker), with a database named `chatbot_dev_db`
- Node.js (for the widget)
- A Botpress bot and its Chat API URL
- Optional: a Gemini API key for voice
- Optional: ClamAV for the upload virus scan
- Optional: `eng.traineddata` and `khm.traineddata` in `backend/tessdata/`, to read files sent to the AI chat (Tesseract comes with the backend; nothing to install, see [docs/attachments-ai-chat.md](docs/attachments-ai-chat.md))

## Getting started

### 1. Backend

```powershell
cd backend
copy .env.example .env      # then fill in the values
.\gradlew.bat bootRun
```

Flyway creates the tables on first start. The backend runs on `http://localhost:8080`.

`backend/.env` settings (see [backend/.env.example](backend/.env.example)):

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection |
| `BOTPRESS_BASE_URL` | `https://chat.botpress.cloud/<id>` |
| `CORS_ALLOWED_ORIGINS` | Widget origin, default `http://localhost:3000` |
| `GEMINI_API_KEY` | Voice; empty turns voice off (the endpoints answer 503) |
| `HANDOVER_AUTO_CLOSE`, `HANDOVER_INACTIVITY_TIMEOUT` | Auto-close live chats after customer inactivity (off by default: only the agent closes a chat) |
| `HANDOVER_WAITING_REMINDER` | While no agent has accepted, remind the customer this often (default `60s`, `0` = off) |
| `AUTO_ROUTE` | BRD auto-route to an agent (default `true`) |
| `CHAT_RETENTION_DAYS` | Chat history is deleted after this many days (default 90, `0` = kept) |
| `AGENT_MAX_CHATS` | Live chats one agent may have at once (default 2, `0` = no limit); each chat has one agent |
| `AGENT_TAKEOVER` | Botpress Desk agents may take over chats the customer did not escalate (default `false`) |
| `PUBLIC_BASE_URL` | How agents reach this backend (default `http://localhost:8080`); Desk mode posts file links to it |
| `ATTACHMENT_RETENTION_DAYS` | Uploaded files are deleted after this many days (default 30) |
| `ATTACHMENT_MAX_PER_SESSION` | Files a customer can send to the AI chat per chat (default 5) |
| `OCR_ENABLED`, `OCR_ENGINE`, `OCR_TESSDATA_DIR`, `OCR_TESSERACT_PATH`, `OCR_LANGUAGES`, `OCR_TIMEOUT` | Reading files sent to the AI chat with Tesseract (off until the language files are in place) |
| `CLAMAV_ENABLED`, `CLAMAV_HOST`, `CLAMAV_PORT` | ClamAV virus scan (off until installed) |
| `AGENT_USERNAME`, `AGENT_PASSWORD` | Agent login for the secure file and message pages; an empty password is generated and printed in the log |

Real environment variables override `.env`. Never commit `.env` or API keys.

### 2. Widget

```powershell
cd widget
npm install
npm run dev
```

Open `http://localhost:3000/` for the demo page with the floating chat button, or `http://localhost:3000/chat` for the full-screen chat used by the mobile webview.

Optional `widget/.env.local` (see [widget/.env.example](widget/.env.example)): `NEXT_PUBLIC_API_BASE` and `NEXT_PUBLIC_AI_RATING_DELAY_SECONDS`.

### 3. Live agents (Botpress Desk)

Agents work in Botpress Desk (`desk.botpress.cloud`). When a customer asks for an agent, the chat appears there; the agent joins, replies and closes it in Desk, and the customer sees it in the widget. When a customer sends a file, Desk shows a link to `http://localhost:8080/agent/file.html?...`; the agent opens it with `AGENT_USERNAME` / `AGENT_PASSWORD`. Details: [docs/agent-inbox-switch.md](docs/agent-inbox-switch.md).

## Useful URLs

| URL | What |
|---|---|
| `http://localhost:8080/swagger-ui.html` | API docs (Swagger UI) |
| `http://localhost:8080/actuator/health` | Health check |
| `http://localhost:8080/agent/file.html` | Secure file page (Desk links to it) |
| `http://localhost:3000/` | Widget demo page |

## API overview

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/chat/messages` | Send a customer message (optionally with `attachmentId`), get the bot reply |
| `POST /api/v1/chat/handover` | Ask for a live agent (returns the queue position) |
| `POST /api/v1/chat/sessions/{sessionId}/handover/close` | The customer cancels the request or ends the live chat |
| `GET /api/v1/chat/config` | Widget settings (which agent inbox is used) |
| `GET /api/v1/chat/sessions/{sessionId}/events` | Live updates (Server-Sent Events) |
| `GET /api/v1/chat/sessions/{sessionId}/updates` | Live updates (polling fallback) |
| `POST /api/v1/chat/sessions/{sessionId}/rating` | Submit a rating |
| `POST /api/v1/chat/attachments` | Send a file from the widget: AI chat draft with the masked text the bot will read, or straight to the agent in a live chat |
| `DELETE /api/v1/chat/sessions/{sessionId}/attachments/{fileId}` | Remove an AI chat file before sending it |
| `POST /api/v1/chat/sessions/{sessionId}/attachments` | Upload a file during a live agent chat (waiting or active) |
| `POST /api/v1/chat/speech/transcriptions` | Speech-to-text (multipart audio) |
| `POST /api/v1/chat/sessions/{sessionId}/replies/{replyId}/speech` | Read a bot reply aloud (WAV) |
| `/api/v1/agent/handovers/{id}/attachments/{fileId}` | Agent file view and delete (secure file page) |
| `GET /api/v1/agent/handovers/{id}/messages/{messageId}` | A live chat message with the personal data Botpress does not get (secure message page) |

Full details are in Swagger UI.

## Tests

```powershell
cd backend
.\gradlew.bat test

cd ..\widget
npm test
npm run typecheck
```

## Documentation

- [docs/technology.md](docs/technology.md): stack, architecture, backend structure
- [docs/flow.md](docs/flow.md): chat, voice, handover, rating flows
- [docs/botpress-integration.md](docs/botpress-integration.md): how the backend calls Botpress
- [docs/botpress-desk.md](docs/botpress-desk.md): Botpress Desk evaluation and the decision to use it
- [docs/agent-inbox-switch.md](docs/agent-inbox-switch.md): live agents in Botpress Desk, including how customer files stay private
- [docs/handover.md](docs/handover.md): live agent handover, real-time updates, waiting messages
- [docs/rating.md](docs/rating.md): post-chat rating
- [docs/voice.md](docs/voice.md): Gemini voice
- [docs/attachments-simple.md](docs/attachments-simple.md): file upload (implemented); [docs/attachments.md](docs/attachments.md): OCR + masking alternative (reference)
- [docs/attachments-ai-chat.md](docs/attachments-ai-chat.md): attachments during the AI chat with OCR (implemented, BRD update)
- [docs/widget.md](docs/widget.md): widget and embedding

## Disclaimer

This is a research and development project, not a production system. It is not affiliated with any company.
