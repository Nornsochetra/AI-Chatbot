# AI Chatbot: Voice (Gemini)

Status: R&D, built and **verified live with Gemini** on 2026-11-10 (read-aloud and transcription, Khmer and English; see section 9). See [technology.md](technology.md), [widget.md](widget.md) and [flow.md](flow.md).

## 1. Goal

Requirements sections III.D and III.E:
- **Voice input (speech-to-text):** the customer speaks a question in Khmer or English instead of typing. Required on the website and the mobile app.
- **Voice output (read-aloud):** the customer can play a bot reply as audio; the text stays visible.
- Typing / reading always remains available when voice cannot be used.

## 2. Design

```
🎤 Mic                                          🔊 Read aloud
Widget records (MediaRecorder)                  Customer taps "Read aloud" under a bot reply
   │ audio (WebM / Ogg / MP4, ≤ 60 s)              │ sessionId + replyId (no text is sent)
   ▼                                               ▼
Our backend  POST /speech/transcriptions        Our backend  POST /sessions/{id}/replies/{replyId}/speech
   │ checks format / size                          │ loads that session's stored bot reply
   ▼                                               │ removes markdown, max 2000 characters
Gemini gemini-3.8-flash (audio → text)             ▼
   │                                            Gemini gemini-3.8-flash-tts (text → WAV)
   ▼                                               │
Transcript → the customer's input box              ▼
   │ customer checks / edits, presses Send      WAV → played in the widget, text stays visible
   ▼
normal chat path (masking → Botpress)
```

Decisions:

| Decision | Why |
|---|---|
| The browser never calls Gemini; our backend does | The API key stays on the server; we control limits and logging |
| The transcript goes into the input box, the customer presses Send | They can fix recognition mistakes; the normal path (masking, logging, handover rules) applies unchanged. Answers feasibility question 8 ("is the transcription shown in the UI?"): yes |
| Read-aloud only for **stored bot replies of the same session** | The public endpoint cannot be used to synthesize arbitrary text on the bank's Gemini bill |
| Numbers are transcribed as digits (prompt) | So "my OTP is one two three…" becomes digits and the existing masking catches it |
| No audio is stored, no transcript is logged | Privacy; the transcript only becomes data when the customer sends it as a message |
| Voice is off when `GEMINI_API_KEY` is empty | Endpoints answer 503; the widget says "Voice isn't available right now. You can type instead." |

## 3. Gemini API (as documented on 2026-11-10)

Gemini now uses the **Interactions API**: `POST https://generativelanguage.googleapis.com/v1beta/interactions` with header `x-goog-api-key` (and `Api-Revision: 2026-05-20`).

| Use | Models (tried in order) | Request | Response |
|---|---|---|---|
| Speech-to-text | `gemini-3.5-flash`, `gemini-3.6-flash`, `gemini-3.8-flash` | `input`: a text prompt + `{"type": "audio", "data": <base64>, "mime_type": "audio/webm"}` | text in `steps[]` → `model_output` → `content[]` items of type `text` |
| Read-aloud | `gemini-3.8-flash-tts`, `gemini-3.8-flash-lite-tts` | `input`: `user_input` with the text; `response_format: {"type": "audio"}`; `generation_config.speech_config: [{"voice": "Kore"}]` | WAV (24 kHz, mono, 16-bit) as base64 in `steps[]` → `model_output` → `content[]` items of type `audio` |

- Supported recording formats include WebM, Ogg, MP4/M4A, WAV, MP3 (WebM is what Chrome records).
- **Khmer is listed as supported** by Gemini 3.8 Flash TTS ("over 130 languages").
- Inline requests are limited to 20 MB; our limit is 5 MB / 60 seconds.
- The response shape (`steps[]` → `model_output` → `content[]`) is **confirmed by live calls** for both text and audio.
- **Model fallback:** Gemini models are sometimes overloaded. On 2026-11-10, `gemini-3.8-flash`, `gemini-3.7-flash` and `gemini-flash-latest` answered "currently experiencing high demand… try again later" (503) or hung for minutes, while `gemini-3.5-flash` answered reliably. The adapter therefore tries the configured models in order: a 503 / 429 / 5xx / 404 or a timeout (25 s per attempt) moves on to the next model; a 400 / 401 / 403 (bad request, invalid key) stops at once. The order puts the reliable model first; move `gemini-3.8-flash` to the front once it is stable.

The adapter is `GeminiSpeechAdapter` behind `SpeechPort`, so another provider (e.g. Google Cloud Speech-to-Text, Azure) can replace it without touching the widget.

## 4. API (our backend)

| Method | Path | Body | Result |
|---|---|---|---|
| `POST` | `/api/v1/chat/speech/transcriptions` | multipart, field `audio` (max 5 MB) | `{ "text": "..." }` (empty when no speech) |
| `POST` | `/api/v1/chat/sessions/{sessionId}/replies/{replyId}/speech` | none | `audio/wav` |

`POST /api/v1/chat/messages` now also returns `replyId` (the stored bot reply) so the widget can ask for read-aloud.

Errors: 400 unsupported format / empty / malformed upload, 404 reply not found or not this session's, 413 over 5 MB, 503 voice off or Gemini unavailable (generic message to the customer; details only in the server log).

## 5. Widget

- **Mic button** left of the input box (only shown when the browser supports recording on a secure page; `localhost` counts as secure).
  - Tap → recording (red, pulsing, "Recording… 5s / 60s"), tap ■ to stop; stops by itself at 60 s.
  - Then "Turning your voice into text…" → the transcript appears in the input box (appended if text was already there).
  - Blocked microphone → "Microphone access is blocked. You can type instead." No speech → "We didn't catch that…". Voice off / error → "Voice isn't available right now…".
- **Read aloud** under every bot reply: tap → "Preparing audio…" → plays, button becomes "Stop". One reply plays at a time; audio is cached for the page so replaying is instant. Error → "Read-aloud isn't available right now."
- The loader already gives the iframe microphone permission (`allow="microphone"`).
- All texts in Khmer and English.

## 6. Configuration

| Setting | Default | Meaning |
|---|---|---|
| `GEMINI_API_KEY` | (empty → voice off) | Put your key in `backend/.env`. Never in code or chat. |
| `GEMINI_TRANSCRIBE_MODELS` | `gemini-3.5-flash,gemini-3.6-flash,gemini-3.8-flash` | Speech-to-text models, tried in order |
| `GEMINI_TTS_MODELS` | `gemini-3.8-flash-tts,gemini-3.8-flash-lite-tts` | Read-aloud models, tried in order (Khmer is listed for both) |
| `GEMINI_TTS_VOICE` | `Kore` | One of the 30 prebuilt voices |
| `gemini.timeout` | `25s` | Per model attempt |
| Widget voice timeout | 90 s | Room for the backend to try several models |
| `spring.servlet.multipart.max-file-size` | `5MB` | Recording upload limit |

## 7. How to try it

1. Put your key in `backend/.env`: `GEMINI_API_KEY=...`, restart the backend.
2. Open `http://localhost:3000/`, open the chat, allow the microphone when the browser asks.
3. Tap 🎤, say "តើសាខាបើកម៉ោងប៉ុន្មាន?", tap ■ → the text appears in the box → Send.
4. Under the bot's answer tap **Read aloud** / **អានឮៗ** → the answer is spoken.
5. Try English, mixed Khmer / English, and a sentence with numbers ("my OTP is 1 2 3 4 5 6" → after Send, the masking notice should appear).

Quick backend check without the widget (after setting the key and restarting):
`python -I tools\voice_live_check.py http://localhost:8080` (asks the bot a Khmer and an English question, reads each answer aloud, then transcribes that audio back).

## 8. Security and privacy

- API key only in `backend/.env` / environment; sent to Gemini per request; never sent to the browser or logged.
- Recordings are sent to Gemini and not stored; transcripts are not logged. Request bodies are never logged (they contain the customer's voice / text).
- Read-aloud only for the session's own stored bot replies, max 2000 characters per request.
- Data leaves Cambodia (Gemini API): the same Security / Compliance approval question as Botpress.

## 9. Verification (2026-11-10)

Unit tests: backend 78 (13 new: Gemini adapter against a fake server for the exact request / response shapes, key-missing behavior, errors; speech service for formats, ownership, length limit, markdown removal), widget 27 (voice API calls: multipart upload without a forced Content-Type, read-aloud blob, 503 → "unavailable").

End-to-end against the real backend **without a Gemini key** (12 checks, all passed):

| Check | Result |
|---|---|
| Chat replies carry a `replyId` | ✅ |
| Read-aloud and transcription without a key → 503 with a generic message; the reason ("GEMINI_API_KEY is empty") only in the log | ✅ |
| Read-aloud of another session's reply / unknown reply → 404; malformed id → 400 | ✅ |
| Unsupported format, empty recording, missing `audio` part, JSON instead of multipart → 400 | ✅ |
| Recording over 5 MB → 413 | ✅ |

**Live with Gemini** (`tools\voice_live_check.py`: ask the bot, read the answer aloud, transcribe that audio back):

| Language | Read-aloud | Transcription of that audio |
|---|---|---|
| Khmer | ✅ WAV, ~490 KB (~10 s) | ✅ "សាខាបើកពីថ្ងៃច័ន្ទ ដល់ថ្ងៃសុក្រ ម៉ោង 8 ព្រឹក ដល់ 3 រសៀល សាខាបិទនៅថ្ងៃសៅរ៍ ថ្ងៃអាទិត្យ និងថ្ងៃឈប់សម្រាកជាតិ" (almost word for word; one spelling variant, numbers as digits) |
| English | ✅ WAV, ~470 KB | ✅ "…Branches are open Monday to Friday, 8 a.m. to 3 p.m. and closed on Saturdays, Sundays, and national holidays." |

Latency observed: transcription of a ~10 s clip took 11–21 s on `gemini-3.5-flash` / `3.6-flash` during the overload; real questions are shorter. Unit tests: backend 84 (adapter 9, incl. fallback on "high demand", all models failing, no fallback on a rejected request, and the live response shape).

**Not verified yet:**
1. Khmer quality with real human voices (accents, noise, banking terms) — the test above uses Gemini's own voice.
2. The mic and speaker buttons clicked through in a browser (the browser tool is disconnected).
3. Safari (records MP4) and the mobile app webview (microphone permission inside the app).
4. Latency when the models are not overloaded.

## 10. Open items

1. **Live test with the key**, then Khmer quality review with native speakers (accents, banking terms, numbers).
2. **Cost and abuse limits:** per-session limits on recordings / read-aloud (rate limiting with Bucket4j), monitoring of Gemini spend.
3. **Mobile app webview:** the app must grant microphone permission to the webview (Android `onPermissionRequest`, iOS `NSMicrophoneUsageDescription`).
4. **Compliance:** customer voice goes to Google; needs the same approval as the other cloud services.
5. **Optional:** auto-read new replies for accessibility, a voice choice per language, streaming TTS for long replies.
