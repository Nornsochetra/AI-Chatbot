# AI Chatbot: Chat Widget

Status: R&D. See [technology.md](technology.md) for the stack, [flow.md](flow.md) for the flows, [botpress-integration.md](botpress-integration.md) for the backend ↔ Botpress part and [handover.md](handover.md) for the live agent part.

## 1. Goal

A chat widget that customers use on the **public website** (floating button) and inside the **mobile app** (full-screen webview page). It talks only to **our backend** (`/api/v1/chat/...`), never to Botpress directly, so masking, logging and handover stay under our control.

## 2. Scope

| Built | Later |
|---|---|
| Floating launcher button and chat window | 📎 Attachments |
| Send / receive messages through our backend | |
| 🎤 Voice input and 🔊 read-aloud (Gemini, see [voice.md](voice.md)) | |
| Khmer and English UI with a language toggle (Khmer default) | |
| Loading ("typing…"), error, retry and fallback states | |
| Post-chat rating card (see [rating.md](rating.md)) | |
| Markdown in bot replies (bold, lists, links), raw HTML disabled | Streaming replies (SSE) |
| "Talk to an agent" button, waiting / live agent states (see [handover.md](handover.md)) | Official branding |
| Safety notice when the backend returns `inputMasked: true` | |
| Input hint "Never share your OTP, PIN or password" | |
| "You're chatting with an AI assistant" disclosure | |
| Session and history kept while the tab is open | |
| Mobile full-screen page | |
| Demo page that imitates a bank website | |

## 3. Technology

| Item | Choice | Why |
|---|---|---|
| Framework | **Next.js 16** (App Router) + React 19 + TypeScript | Team standard; static pages that can be hosted anywhere |
| Pages | `/` demo bank page, `/chat` the chat (both prerendered as static pages) | |
| Styling | Plain CSS with variables (`theme.css`), CSS module for the demo page, no UI library | Small bundle, easy to brand later |
| Khmer font | Kantumruy Pro through `next/font` (self-hosted by Next.js, no request to Google from the customer's browser) | Renders Khmer correctly |
| Markdown | `react-markdown` with HTML skipped | Bot replies cannot inject HTML or scripts |
| State | React `useReducer` | One chat, no Redux needed |
| Loader | Plain JavaScript file in `public/` (no build step) | Bank pages include one fixed URL |
| Tests | Vitest (reducer and API client) | |

Versions: `next` 16.4.6, `react` / `react-dom` 19.3.1, `react-markdown` 10.1.0, TypeScript 5.9.3 (Next.js tooling is built for TypeScript 5), Vitest 2.1.8.

The widget was first built with React + Vite and moved to Next.js on 2026-11-10. Components, state, API client, i18n, styles and tests were kept; only the app shell, the pages and the loader packaging changed.

## 4. Embedding: iframe

```
Bank website
 └─ <script src="https://<widget host>/widget-loader.js" data-kb-chat data-channel="WEBSITE" data-lang="km" data-api="https://api...">
       │  small loader script
       ├─ draws the floating button (bottom right)
       └─ on first click → creates an <iframe> → our chat page: /chat?channel=WEBSITE&lang=km&api=...

Mobile app
 └─ webview opens the same chat page full screen: /chat?channel=MOBILE_APP&lang=km&api=...
```

Why an iframe:
- **Isolation.** The bank's CSS / JavaScript cannot break the widget, and the widget cannot break the bank's page. The bank's scripts cannot read the chat.
- **One app for both channels.** Website iframe and mobile webview load the same page.
- **Voice later.** The iframe can be given microphone permission (`allow="microphone"`).

The close button inside the chat sends a `postMessage` to the loader, which checks the sender's origin before hiding the window. In the mobile webview (no parent page) the close button is hidden; the app closes the webview itself.

The chat page must be allowed inside an iframe, so it does not send `X-Frame-Options: DENY`. For production, restrict who may frame it with `Content-Security-Policy: frame-ancestors <bank domains>`.

### Loader attributes

| Attribute | Default | Meaning |
|---|---|---|
| `data-kb-chat` | (required) | Marks the loader script |
| `data-channel` | `WEBSITE` | `WEBSITE` or `MOBILE_APP` |
| `data-lang` | `km` | `km` or `en` (initial UI language) |
| `data-api` | backend default of the chat page | Backend base URL |
| `data-chat-url` | `/chat` on the loader's origin | Where the chat page is hosted |

### Chat page URL parameters

`channel`, `lang`, `api` (same meaning as above). Without `api`, the chat page uses `NEXT_PUBLIC_API_BASE` (see `.env.example`), default `http://localhost:8080`.

## 5. Folder structure

```
widget/
 ├─ package.json, next.config.ts, tsconfig.json, vitest.config.ts, .env.example
 ├─ public/
 │   └─ widget-loader.js       launcher button + iframe (plain JavaScript, served as-is)
 └─ src/
     ├─ app/                   Next.js routes
     │   ├─ layout.tsx         html shell, Kantumruy Pro font
     │   ├─ page.tsx           "/"      demo page imitating a bank website (+ loader <Script>)
     │   ├─ demo.module.css
     │   └─ chat/page.tsx      "/chat"  the chat (iframe / webview)
     ├─ components/
     │   ├─ ChatPage.tsx       client entry: reads URL / sessionStorage in the browser only
     │   ├─ ChatApp.tsx        chat logic: send, handover, polling, retry
     │   └─ ChatHeader, MessageList, MessageBubble, TypingIndicator, Composer, Notice, HandoverPrompt
     ├─ api/chatApi.ts         backend calls, 30 s timeout, error kinds
     ├─ state/chatReducer.ts   messages, sessionId, mode (bot / waiting / agent), cursor; sessionStorage
     ├─ i18n/km.ts, en.ts      all UI text
     ├─ lib/config.ts, ids.ts  URL parameters, ids
     └─ styles/theme.css       colors, fonts, spacing as CSS variables
```

`AGENTS.md` and `next-env.d.ts` are generated by `next dev`.

## 6. Flow inside the widget

```
Open widget
  → welcome message (in the selected language) + AI disclosure
User types and presses Send (or Enter)
  → user bubble, "typing…", Send disabled
  → POST /api/v1/chat/messages { sessionId?, channel, text }
       ├─ 200 → save sessionId, bot bubble (markdown)
       │        ├─ inputMasked: true       → safety notice under the user bubble
       │        └─ handoverSuggested: true → "Talk to an agent" prompt
       ├─ 400 → "Your message is empty or too long"
       └─ network error / timeout (30 s) → error notice + Retry
"Talk to an agent" → live agent flow, see handover.md
```

- **Session:** `sessionId`, message history, mode and cursor are kept in `sessionStorage` (per channel). They survive a page refresh and end when the tab closes. If the backend session expired (30 min), the backend starts a new one silently.
- **Language:** the toggle changes the UI text only. The bot replies in the language the user writes. The user's last toggle is remembered for the tab session and wins over the `lang` parameter.
- **New chat:** clears the history and the `sessionId` (only offered while chatting with the bot).
- **Input limit:** 2000 characters (same as the backend); a counter appears near the limit.

## 7. Security

- No secrets in the widget; it only knows the backend URL.
- Bot replies are rendered as markdown with HTML skipped; links open in a new tab with `rel="noopener noreferrer"`. Agent replies are plain text.
- Masking happens in the backend. The widget only shows the notice.
- The loader only accepts `postMessage` events from the chat page's origin.

## 8. Defaults chosen for R&D

| Question | Default |
|---|---|
| Branding | Neutral placeholder colors and a generic chat icon (official colors / logo to be provided by the bank) |
| Default language | Khmer |
| Launcher position | Bottom right |
| Welcome message | "សួស្តី! ខ្ញុំជាជំនួយការ AI របស់ធនាគារ។ តើខ្ញុំអាចជួយអ្វីបាន?" / "Hello! I'm the bank's AI assistant. How can I help you?" (placeholder; the requirements makes welcome messages customizable) |
| Hosting | Next.js dev server on `http://localhost:3000` (Next.js default); backend on `http://localhost:8080` (CORS allows 3000) |

All Khmer UI text is in `src/i18n/km.ts` and needs review by a native speaker.

## 9. How to run

1. Start the backend: `cd backend` then `.\gradlew.bat bootRun` (port 8080).
2. Start the widget: `cd widget`, `npm install` (first time), then `npm run dev`.
3. Open:
   - `http://localhost:3000/` → demo bank page with the floating button
   - `http://localhost:3000/chat?channel=MOBILE_APP&lang=km` → mobile full-screen page
4. Tests: `npm test`. Type check: `npm run typecheck`. Production build: `npm run build`, then `npm start` (port 3000).

Note: the npm scripts call Node directly (`node node_modules/next/dist/bin/next ...`) instead of `next` / `vitest` / `tsc`. npm's Windows command shims break in folders with `&` in the path, such as `C:\R&D`.

## 10. Status (2026-11-10)

First built with React + Vite and checked in Chrome against the real backend and Botpress:

| Check | Result |
|---|---|
| Demo page shows the floating button; click opens the chat iframe | ✅ |
| Khmer UI, AI disclosure, welcome message, safety hint | ✅ |
| Khmer question → typing indicator → Botpress answer from `knowledge.txt` | ✅ |
| Markdown bullet list in the bot reply | ✅ |
| "My OTP is 123456" → yellow safety notice; bot reply says the code was redacted | ✅ |
| Language toggle switches all UI text, including notices | ✅ |
| Mobile page full screen, no close button | ✅ |

After the move to Next.js:

| Check | Result |
|---|---|
| Unit tests (reducer incl. live-agent states, API client) | ✅ 18 passed |
| `next build` (type check + build) | ✅ `/` and `/chat` prerendered as static pages |
| Dev server: `/` and `/chat` return 200; `/widget-loader.js` served as JavaScript | ✅ |
| Loader `<Script>` with its `data-kb-chat` attributes is in the demo page | ✅ |
| `/chat` sends no frame-blocking header (works inside the iframe) | ✅ |

Not checked yet after the move: clicking through the widget in a browser (the browser tool was disconnected). The chat logic itself did not change, and is covered by the unit tests. Also not checked: a real phone screen, and the bank's real website.

Known limitations:
- The user's own bubble shows what they typed, including the sensitive number. It never leaves the bank unmasked and is only kept in their own browser tab (`sessionStorage`), but we could show the masked text instead if preferred.
- Branding is a neutral placeholder.
