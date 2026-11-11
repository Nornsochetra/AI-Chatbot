# AI Chatbot: Technology

Status: R&D draft.

## 1. Scope

| They provide / own | We build |
|---|---|
| Knowledge content (FAQ, product, service) | AI chatbot widget (website and mobile webview) and the backend behind it |
| Public static website | Live agent handover |
| Mobile banking app | Admin portal: audit log and dashboard / reports only |

Facebook Messenger is inbox-only in the requirements (no AI). It is not part of the R&D build.

## 2. Architecture overview

```
 Website (floating widget)        Mobile App (webview)
            \                          /
             \________ WAF / API ______/
                          |
                Spring Boot BFF (middle layer)
   ┌───────────┬──────────┼───────────┬─────────────┐
 Session     Guardrails  Bot engine  Speech        Handover
 (store)     (mask I/O)  (Botpress)  (Gemini)      (HelpdeskPort)
                             |                          |
                       Botpress Cloud            Agent inbox
                  (Knowledge Base + LLM)    (their tool or Chatwoot)

 Postgres: audit log, chat events, ratings     Metabase: dashboard / reports
```

Botpress does the RAG, embedding, LLM and conversation memory. Our Spring Boot app is a secure middle layer. It never talks to an LLM directly.

The customer always uses **our own widget**, never the Botpress Webchat. Masking, Gemini voice, handover, attachments and audit only work when every message goes through our backend.

How the backend calls Botpress, and the evaluation results: [botpress-integration.md](botpress-integration.md).

## 3. Technology choices

| Area | Technology | Notes |
|---|---|---|
| Bot brain | **Botpress** Viber agent (Knowledge Base, LLM, conversation memory), called through the Chat API | Replaces our own RAG and embeddings |
| Voice | **Gemini API** (speech-to-text, text-to-speech) | Called by our backend. Botpress only sees text. |
| Backend | Java 21, Spring Boot 3.x, Gradle (Kotlin DSL) | `RestClient` / `WebClient` for Botpress and Gemini |
| Widget | **Next.js 16** (React 19, TypeScript), own UI | Script tag for the website, hosted page (`/chat`) for the mobile webview |
| Session mapping | In-memory store (R&D). Redis or Postgres if sessions must survive restarts or scale out | Our session ↔ Botpress user and conversation, plus state `BOT_ACTIVE` / `HUMAN_ACTIVE` |
| Database | PostgreSQL | Audit log, chat events, ratings, report data |
| Realtime | Server-Sent Events for live agent chats (messages, joined / left, typing), polling as fallback | Built; see [handover.md](handover.md) section 13. Bot answers still come back in the request's response |
| Files | Server disk behind `FileStoragePort` | MinIO later if multiple instances |
| Malware scan | ClamAV | Required for uploads |
| Live agent | Their existing inbox via API, else Chatwoot | Behind `HelpdeskPort` |
| Admin portal | React, Spring Security (2FA, IP whitelist) | |
| Dashboard / reports | Metabase on Postgres | Export to their template |
| Background jobs | JobRunr | 2-minute timeout, delayed rating, exports |
| Resilience | Resilience4j, Bucket4j | Botpress or Gemini down, rate limiting |
| Local R&D setup | Native installs, no Docker. PostgreSQL installed locally. | ClamAV (native Windows installer) added when attachments are built |
| Deploy / monitoring | To be decided with the infrastructure team. Prometheus and Grafana for monitoring. | |
| Testing | JUnit, Testcontainers, golden Q&A set | |

Not used: Spring AI, pgvector, own embeddings or ingestion, a direct LLM API, Azure Speech.

## 4. Interfaces (swap points)

| Interface | Default adapter | Purpose | Status |
|---|---|---|---|
| `BotEnginePort` | `BotpressBotEngine` | Send a user message, get the bot reply | Done, verified against Botpress |
| `SpeechPort` | `GeminiSpeechAdapter` (Interactions API, `gemini-3.8-flash` / `gemini-3.8-flash-tts`) | Transcribe audio, synthesize audio | Built, live test pending |
| `HelpdeskPort` | `BuiltInHelpdesk` (built-in agent console), later their inbox or Chatwoot | Tell the inbox about opened / closed tickets and customer messages | Done (built-in) |
| `FileStoragePort` | Disk adapter | Store and fetch attachments | Planned |

## 5. Responsibility split

| Requirement | Owner |
|---|---|
| KB-only answers, context retention, Khmer / English replies | Botpress |
| Speech-to-text, text-to-speech | Gemini, called by us |
| Masking OTP / PIN / CVV / card numbers | Us (before data leaves the bank) |
| Handover, timeout, rating | Us |
| Attachments and malware scan | Us |
| Audit, dashboard, reports | Us |

## 6. Risks and items to verify

1. **Khmer quality.** Botpress answers: first tests pass (see [botpress-integration.md](botpress-integration.md)), wider test with real content still needed. Gemini speech-to-text and text-to-speech: not tested yet.
2. **Data leaving Cambodia.** Botpress Cloud and the Gemini API are hosted abroad. Security and Compliance must approve.
3. **Botpress API route.** The Webchat client ID works with the Chat API but this is not officially documented, and the ID is public. Ask Botpress whether it is supported for production and how to use a private key.
4. **Gemini voice:** models and API checked in the docs (Interactions API, `gemini-3.8-flash`, `gemini-3.8-flash-tts`, Khmer listed for TTS). Still open: live test, Khmer quality, latency and pricing. See [voice.md](voice.md).
5. **Botpress handover signal:** how it tells us "needs human" (custom event, webhook or tag).
6. **Botpress limits:** message rate, pricing model, Knowledge Base file types and size.
7. **Agent inbox:** name, and whether it has an API for a custom chat channel.
8. **Mobile integration method:** Webview or SDK, to be confirmed by the Technical team.
9. **Audio privacy:** do not store raw recordings.

## 7. Backend structure

Single Spring Boot app in `backend/`. Each feature (for example `chat`) is its own top-level package, next to **config** and **shared** (reusable components). There is no extra `feature/` folder.

```
backend/
 ├─ build.gradle.kts              Gradle Kotlin DSL
 ├─ settings.gradle.kts
 ├─ gradlew, gradlew.bat           Gradle wrapper (gradlew.bat patched to work in paths with "&", e.g. C:\R&D)
 ├─ .env                          local settings and secrets, not committed
 ├─ .env.example                  template for .env
 └─ src/main/java/com/kbprasac/chatbot/
     ├─ ChatbotApplication.java
     ├─ <feature>/                chat, guardrail, speech, handover, attachment, rating, audit, dashboard
     │   ├─ controller/           REST endpoints
     │   ├─ service/              business logic, ports (e.g. BotEnginePort) and their adapters
     │   ├─ payload/
     │   │   ├─ request/          request DTOs (records)
     │   │   └─ response/         response DTOs (records)
     │   └─ mapper/               entity ↔ payload mapping, reused across the feature
     ├─ config/                   OpenAPI, Security (CORS, auth rules), properties, HTTP clients
     └─ shared/                   everything reused across features
         ├─ entity/               JPA entities
         ├─ enums/
         ├─ repository/           Spring Data repositories
         └─ exception/            global exception handler
 src/main/resources/
     ├─ application.yml
     └─ db/migration/             Flyway scripts
```

Rules:
- A feature never reaches into another feature's `controller` or `payload`. Shared types go to `shared/`.
- Controllers talk to services only. Services use repositories and mappers.
- External systems (Botpress, Gemini, agent inbox, file storage) are reached through a port interface in the feature's `service` package.

Current state of the scaffold:

| Feature | Status |
|---|---|
| `chat` | Done and verified end to end with Botpress: `POST /api/v1/chat/messages`, Botpress adapter, in-memory session store, event logging to Postgres |
| `guardrail` | Input masking, own code (Java regex, no library), runs before Botpress and before logging. Covers: secrets after English or Khmer keywords (OTP, PIN, CVV, password, លេខសម្ងាត់, ពាក្យសម្ងាត់, លេខកូដ), including text passwords ("password is hunter2", only when the word has a digit or symbol) and spelled-out numbers ("pin is four eight two one", Khmer number words); account / card / ID / passport numbers after a keyword; Cambodian phone numbers; any 13+ digit number; ASCII and Khmer digits. Keywords are kept so the bot understands the context. 43 unit tests. Not covered: plain-word passwords ("password is sunshine"), short numbers without a keyword (needs the bank's account number format). Output check on bot replies: not built |
| `handover` | Done and verified end to end: customer and agent APIs, `WAITING → ACTIVE → CLOSED`, bot silent during a handover, closed by the agent (2-minute auto-close available but off by default), secrets-only masking for agent messages, built-in agent console at `/agent`. See [handover.md](handover.md) |
| `rating` | Done and verified end to end: AI and agent ratings (1–5 + comment), agent chats asked immediately after close, AI chats after a delay (60 s default), once per chat, masked comments, rating shown in the agent console. See [rating.md](rating.md) |
| `speech` | Built: mic → Gemini speech-to-text → transcript in the input box; read-aloud of stored bot replies with Gemini TTS; voice off without `GEMINI_API_KEY`. Verified without a key; live Gemini test pending. See [voice.md](voice.md) |
| `attachment` | Done and verified end to end: customer uploads (JPG / PNG / PDF, 5 MB) only during a live agent chat, type from content, ClamAV scan (off until installed), stored as uploaded, agent View / Delete through a login-checked link, 30-day retention. See [attachments-simple.md](attachments-simple.md) (OCR + masking alternative: [attachments.md](attachments.md)) |
| `audit` | Started: `audit_event` table and `AuditService` (file viewed / deleted / expired / virus rejected) |
| `dashboard` | Not started |

Other top-level folders:
- `widget/`: Next.js chat widget (website iframe and mobile page `/chat`). See [widget.md](widget.md).
- `admin-portal/`: React admin app (planned).

## 8. Local setup

1. Install Java 21 and PostgreSQL (native, no Docker). Create the database (default `chatbot_dev_db`).
2. In `backend/`, copy `.env.example` to `.env` and fill in the database password and `BOTPRESS_BASE_URL`. Spring Boot loads `.env` automatically; real environment variables override it.
3. Run `.\gradlew.bat bootRun`. Flyway creates the tables on first start.
4. Open Swagger UI at `http://localhost:8080/swagger-ui.html` and call `POST /api/v1/chat/messages` with `{"channel":"WEBSITE","text":"..."}`. Reuse the returned `sessionId` for follow-up messages.
5. Widget: install Node.js, then `cd widget`, `npm install`, `npm run dev`, and open `http://localhost:3000/`.
6. Agent console: open `http://localhost:8080/agent` and sign in with `AGENT_USERNAME` / `AGENT_PASSWORD` from `.env`.

Note: Flyway logs a warning that PostgreSQL 18 is newer than it officially supports. It works; upgrade Flyway later.

## 9. Timeline (rough)

| Weeks | Focus |
|---|---|
| 1–3 | Scaffold, Botpress and Gemini connection, early Khmer check, compliance and inbox questions in parallel |
| 3–6 | Core chat path, guardrails, session mapping |
| 7–10 | Widget and handover, timeout, rating |
| 11–13 | Voice and attachments |
| 14–16 | Admin portal |
| 17–19 | Hardening, UAT, pilot |

About 3.5–4 months. Step 1 (separate validation phase) was skipped. Its checks are folded into the first weeks.
