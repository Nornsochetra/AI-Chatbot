# AI Chatbot: Botpress Integration

Status: R&D, verified end to end on 2026-11-10. See [technology.md](technology.md) for the stack and [flow.md](flow.md) for the flows.

## 1. Summary

- The bot is a Botpress agent built with the **Viber** builder (the "vibe" assistant in the Botpress dashboard).
- Our backend calls it through the **Botpress Chat API** (`https://chat.botpress.cloud/<id>`).
- Viber agents do not offer the separate **Chat** integration. Instead, the **Webchat client ID** works as the Chat API ID. This route works but is **not officially documented** (see section 6).
- Answers come only from the uploaded knowledge file. Web search and the website crawl are turned off.

## 2. Botpress setup (what we did)

1. **Created the agent** through the onboarding wizard. In step 2 a website URL was entered, so Botpress crawled a public website into the Knowledge Base. In step 4 "Nothing yet" was chosen for the helpdesk.
2. **Removed the crawl.** Knowledge → Websites: deleted all pages. The tab now shows "No pages yet".
3. **Turned off web search.** Knowledge → Web search: "Enable web search" switched off.
4. **Checked the other sources.** Knowledge → Tables and FAQs are empty.
5. **Uploaded test content.** Knowledge → Files: `knowledge.txt` (made-up Khmer sample FAQ: savings and company accounts, branch hours, mobile app, lost card).
6. **Published** the agent. Changes only take effect after publishing.

Lesson learned: a website crawl or web search lets the bot answer from unapproved pages. The requirements require approved Knowledge Base content only, so both must stay off.

## 3. Finding the API ID

The Channel Configuration page lists Webchat and social channels only, and the Connections catalog has no Chat integration. The ID was found in the Webchat's public config:

1. Settings → Channel Configuration → **Webchat** (not Beta) → share link. The link looks like
   `https://cdn.botpress.cloud/webchat/v5.0/shareable.html?configUrl=https://files.bpcontent.cloud/.../<file>.json`
2. Open the `configUrl` JSON. It contains `botId` and **`clientId`**.
3. Base URL for the Chat API: `https://chat.botpress.cloud/<clientId>`
   (`https://webchat.botpress.cloud/<clientId>` also answers.)

Health check: `GET <base>/hello` returns `Hello, Botpress User 🤖`.

## 4. API flow (verified)

```
POST {base}/users                         body {}
  → { "user": { "id": "user_..." }, "key": "<user key>" }

POST {base}/conversations                 header x-user-key, body {}
  → { "conversation": { "id": "conv_..." } }

POST {base}/messages                      header x-user-key
  body { "conversationId": "conv_...", "payload": { "type": "text", "text": "..." } }
  → { "message": { "id": "...", "createdAt": "...", ... } }

GET  {base}/conversations/{id}/messages   header x-user-key
  → { "messages": [ ... newest first ... ] }   bot messages have a userId different from ours
```

Our adapter `BotpressBotEngine` (`backend/src/main/java/com/kbprasac/chatbot/chat/service/`) follows exactly this flow:

- One Botpress user and conversation per chat session, created on the first message and stored in the session store.
- The reply is read by **polling** the messages list every 700 ms, up to 20 s (`botpress.poll-interval-ms`, `botpress.reply-timeout-ms`).
- If no reply arrives in time, or Botpress fails, the API returns a Khmer / English fallback and `handoverSuggested: true`.
- Botpress also offers a live stream (`GET {base}/conversations/{id}/listen`, SSE). We can switch to it later to cut latency.

## 5. Configuration

The backend reads `backend/.env` on startup (`spring.config.import: optional:file:.env[.properties]`). Real environment variables override it. `.env` is not committed; copy `.env.example`.

```
BOTPRESS_BASE_URL=https://chat.botpress.cloud/<clientId>
DB_URL=jdbc:postgresql://localhost:5432/chatbot_dev_db
DB_USER=postgres
DB_PASSWORD=...
```

Run: `cd backend` then `.\gradlew.bat bootRun`. Swagger UI: `http://localhost:8080/swagger-ui.html`.

## 6. Caveats

| Caveat | Impact | Action |
|---|---|---|
| Using the Webchat client ID with the Chat API is not officially documented | Botpress could change it without notice | Ask Botpress whether it is supported for production, or how a backend should call a Viber agent |
| The client ID is public (it is in the share link) | Anyone with the link can call the bot directly, skipping our masking | Fine for R&D. For production, ask Botpress about a private key (the Chat API docs mention a manual-auth option with an encryption key) |
| Data goes to Botpress Cloud (outside Cambodia) | Compliance question from the requirements | Security / Compliance approval still needed |
| Webchat Beta has a different, unstable setup | | Use the non-beta Webchat's client ID |

## 7. Evaluation results

### In the Botpress preview (knowledge file only)

| # | Test | Result | Notes |
|---|---|---|---|
| 4 | Branch hours (Khmer) | ✅ | Mon–Fri 8:00–15:00, closed weekends and holidays, source `knowledge.txt` |
| 7 | Mobile app registration (English) | ✅ | Replied in English |
| 2 | Opening deposit (asked after an unrelated question) | ✅ | Gave $10 savings and $100 company, a sensible answer to an ambiguous follow-up |
| 9 | Home loan interest rate (not in the file) | ✅ | Said it has no information, pointed to the contact center. Did not invent a rate. |
| 11 | Account balance | ✅ | Refused, pointed to the mobile app or contact center |
| 12 | Loan eligibility ($5,000) | ✅ | Did not confirm eligibility |
| 15 | OTP shared | ✅ | Warned not to share OTP, did not repeat it |

Not yet run: #10 (board of directors), #14 (explicit agent request).

### Through our backend (`POST /api/v1/chat/messages`)

| Test | Result | Time |
|---|---|---|
| Savings account documents (Khmer) | ✅ Correct answer from `knowledge.txt` | 7.0 s (first message also creates the Botpress user and conversation) |
| Follow-up "ចុះប្រាក់ដាក់ដំបូងវិញ?" with the same `sessionId` | ✅ "$10 for a savings account", so context retention works | 2.9 s |
| Card number + balance question | ✅ `inputMasked: true`, card number masked before reaching Botpress; bot refused the balance | 2.8 s |
| Follow-up "ចុះសម្រាប់ក្រុមហ៊ុនវិញ?" after the savings question | ✅ Switched to the company documents and $100 | 3.9 s |
| Khmer secret "លេខសម្ងាត់ខ្ញុំគឺ ៤៨២១" | ✅ `inputMasked: true`; bot warned not to share it | 5.8 s |
| Phone number "012 345 678" | ✅ `inputMasked: true` | 5.9 s |
| Empty `text` / missing `channel` | ✅ HTTP 400 with a validation message | |
| Unknown `sessionId` | ✅ New session created, normal answer | 7.5 s |
| Botpress unreachable (connection refused, or invalid ID) | ✅ Khmer / English fallback, `handoverSuggested: true` | 0.3–1.9 s |

### Issues found

| Issue | Fix |
|---|---|
| Replies start with "I'm an AI" / "ខ្ញុំជាបញ្ញាសិប្បនិម្មិត" | Remove from the agent instructions |
| Replies use markdown (`**bold**`) | Widget must render markdown |
| Mixed digit styles ("៨:00") | Khmer reviewer decides the bank's digit style |
| Stray English word in a Khmer reply ("—even") | Khmer review; tighten instructions |
| Eligibility reply says "not enough information to confirm" | Firmer wording: "I can't assess loan eligibility" |
| When it can't answer, it points to the contact center but does not offer a live agent | Instruction change, or our backend shows the handover button |
| An English message was answered in Khmer (it followed the conversation's language) | Check in the language tests; decide the rule |
| Search label and the bot name / description still come from the onboarding template | Tidy the agent instructions and Webchat settings |
| Without our backend, sensitive data reaches Botpress (the OTP test) | Keep all traffic going through our backend; never embed the Botpress Webchat directly |

## 8. Open questions for Botpress

1. Is calling the Chat API with the Webchat client ID supported for production?
2. If not, what is the supported way for a backend to call a Viber agent? Can we create a Studio bot with the Chat integration on our plan?
3. How do we use a private key instead of the public client ID?
4. How should the agent signal "needs a human" (custom event, tag or message) so our backend can trigger handover?
5. Rate limits and pricing for the expected volume.
