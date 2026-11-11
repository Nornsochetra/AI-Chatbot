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
 (memory)    (masking)   (Botpress)  (Gemini)      (agent console)
                          |
     PostgreSQL: chat events, handovers, ratings, attachments, audit
```

## Features

| Feature | Status |
|---|---|
| Text chat through Botpress (Khmer and English) | Done |
| Input masking (OTP, PIN, CVV, passwords, card / account numbers, phone numbers) | Done |
| Live agent handover with a built-in agent console at `/agent` | Done |
| Waiting-for-agent messages: welcome message, live queue position, "agents are busy" reminder | Done |
| Real-time updates (Server-Sent Events) and typing indicators on both sides | Done |
| Post-chat rating (agent and AI chats) | Done |
| Voice input and read-aloud (Gemini) | Done, live-tested |
| File upload in live agent chats (JPG / PNG / PDF up to 5 MB; agent opens a file by its name, deletes with confirmation; retention) | Done |
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
| `ATTACHMENT_RETENTION_DAYS` | Uploaded files are deleted after this many days (default 30) |
| `CLAMAV_ENABLED`, `CLAMAV_HOST`, `CLAMAV_PORT` | ClamAV virus scan (off until installed) |
| `AGENT_USERNAME`, `AGENT_PASSWORD` | Agent console login; an empty password is generated and printed in the log |

Real environment variables override `.env`. Never commit `.env` or API keys.

### 2. Widget

```powershell
cd widget
npm install
npm run dev
```

Open `http://localhost:3000/` for the demo page with the floating chat button, or `http://localhost:3000/chat` for the full-screen chat used by the mobile webview.

Optional `widget/.env.local` (see [widget/.env.example](widget/.env.example)): `NEXT_PUBLIC_API_BASE` and `NEXT_PUBLIC_AI_RATING_DELAY_SECONDS`.

### 3. Agent console

Open `http://localhost:8080/agent` and sign in with `AGENT_USERNAME` / `AGENT_PASSWORD`. The agent's login username is the name the customer sees.

## Useful URLs

| URL | What |
|---|---|
| `http://localhost:8080/swagger-ui.html` | API docs (Swagger UI) |
| `http://localhost:8080/actuator/health` | Health check |
| `http://localhost:8080/agent` | Built-in agent console |
| `http://localhost:3000/` | Widget demo page |

## API overview

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/chat/messages` | Send a customer message, get the bot reply |
| `POST /api/v1/chat/handover` | Ask for a live agent (returns the queue position) |
| `GET /api/v1/chat/sessions/{sessionId}/events` | Live updates (Server-Sent Events) |
| `GET /api/v1/chat/sessions/{sessionId}/updates` | Live updates (polling fallback) |
| `POST /api/v1/chat/sessions/{sessionId}/rating` | Submit a rating |
| `POST /api/v1/chat/sessions/{sessionId}/attachments` | Upload a file during a live agent chat |
| `POST /api/v1/chat/speech/transcriptions` | Speech-to-text (multipart audio) |
| `POST /api/v1/chat/sessions/{sessionId}/replies/{replyId}/speech` | Read a bot reply aloud (WAV) |
| `/api/v1/agent/handovers/...` | Agent console API (queue, accept, messages, typing, files, close) |

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
- [docs/handover.md](docs/handover.md): live agent handover, real-time updates, waiting messages
- [docs/rating.md](docs/rating.md): post-chat rating
- [docs/voice.md](docs/voice.md): Gemini voice
- [docs/attachments-simple.md](docs/attachments-simple.md): file upload (implemented); [docs/attachments.md](docs/attachments.md): OCR + masking alternative (reference)
- [docs/widget.md](docs/widget.md): widget and embedding

## Disclaimer

This is a research and development project, not a production system. It is not affiliated with any company.
