# AI Chatbot: Attachments During the AI Chat (with OCR)

Status: **Implemented 2026-11-12** (both phases). OCR is **off by default** until the language files are in place (section 7). Nothing has to be installed on Windows: Tesseract comes as a Java library through Gradle. Builds on the file upload in live agent chats ([attachments-simple.md](attachments-simple.md)) and reuses ideas from the OCR reference design ([attachments.md](attachments.md), kept unchanged).

## 1. Goal

Updated BRD: customers can send a photo or PDF **while chatting with the AI**, not only with a live agent. Two reasons given:

- **Error screenshots.** A customer hits an error in the app and sends a screenshot. The bot explains it at once; if it cannot, the chat goes to an agent who already has the file.
- **Promotions.** A customer sends a promotion image and asks for more details. The bot answers from the approved knowledge base.

## 2. Main rule

**The AI never receives the file. It only receives the text read from the file, after masking.**

| Who | Gets |
|---|---|
| Botpress (AI) | The text found in the file, **masked** (section 5), plus the customer's question. Never the file. |
| Gemini (voice) | Nothing |
| Live agent | The file itself, through the secure link (sign-in, every view audited), as in live agent chats |

- Text is read **on our own server** (Tesseract for images and scanned PDFs, PDFBox for a PDF's text layer). The file never goes to a cloud service.
- The raw text is **never stored**. Only the masked text is kept (on the file record, and in the chat event like any bot turn), and it is erased with the file.

Two phases, both built:

- **Phase 1 (OCR off):** 📎 also works in the AI chat. The bot cannot read the file; the customer gets "I can't read this file, but a live agent can look at it" with the "Talk to an agent" button. No personal data reaches the AI.
- **Phase 2 (OCR on):** the text is read, masked, **shown to the customer**, and only sent to the bot when the customer presses Send.

## 3. Flow

```
Customer taps 📎 in the AI chat
   │ widget checks: JPG / PNG / PDF, ≤ 5 MB
   ▼
POST /api/v1/chat/attachments   (file, channel, sessionId if any)
   1. Limits: 5 files per chat, one file every 5 s (429 otherwise)
   2. Type check (from the content) and size, virus scan (ClamAV, when on)
   3. Read the text (OCR_ENABLED=true):
        image → phone photos turned upright (EXIF orientation), small screenshots enlarged,
                photos scaled to ≤ 2000 px, made grey, dark mode inverted → Tesseract (khm+eng)
        lines Tesseract is unsure about (confidence < 60) and lines of mostly symbols are dropped
        PDF   → text layer; a scan → first 3 pages rendered → Tesseract
        timeout 10 s per file
   4. Mask the text (section 5)
   5. Store the file as a DRAFT (not sent yet) with the masked text
   ▼
Widget: draft card above the input box
   "📎 error.png 240 KB  ✕"
   "The assistant will read this text from your file:"
   ┌ Error 403: Transaction limit exceeded
   │ Card [REDACTED] ...
   "[REDACTED] marks numbers and details we hid for your safety."
   input box: "Add a question (optional)…"
   │
   ├─ ✕ → DELETE .../attachments/{id}: the draft is erased
   │
   └─ Send → POST /api/v1/chat/messages  { text: question, attachmentId }
        The bot gets:
          [The customer sent an image. Text found in it:]
          Error 403: Transaction limit exceeded
          Card [REDACTED]
          [Customer's question:] what does this error mean?
        → bot answers → shown in the widget as usual
        (one bubble: image preview + question)

Later: the chat goes to an agent (customer asks, or a Desk agent joins)
   → files sent to the bot earlier are linked to the handover
   → Botpress Desk gets, right after the "[Live agent requested]" line (before anyone joins):
       [Files the customer sent to the assistant before asking for an agent]
       - 📎 Customer sent a file (image, 240 KB). Open it securely: <link>
   → files and messages written while waiting follow as they come, tagged "[Waiting for an agent]"
```

In a **live agent chat** (waiting or active) 📎 works as before: the file goes straight to the agent, nothing is read. While waiting, Botpress Desk gets it at once, tagged "[Waiting for an agent]".

## 4. When nothing can be read

OCR off, OCR failed or timed out, or no text found (a photo without words):

| Case | Draft card says | On Send |
|---|---|---|
| OCR off (`SKIPPED`) | "The assistant can't read files yet. You can still send it: an agent can look at it." | No question: fixed reply offering an agent (the bot is not asked). With a question: the bot gets `[The customer sent an image; no text could be read from it.]` + the question, and the reply always shows "Talk to an agent". |
| `NO_TEXT` / `FAILED` | "No text could be read from this file. You can still send it: an agent can look at it." | Same as above |

The file is still stored, because an agent may need it. **Nothing unmasked ever reaches the AI**, also when reading fails.

## 5. Masking of file text

`InputMaskingService.maskFileText` = the full masking used for typed messages (OTP, PIN, password, CVV, card / account / ID numbers, phone numbers) **plus**:

| Added for files | Example → result |
|---|---|
| Email addresses | `sokha@example.com` → `[REDACTED]` |
| Every amount with a currency (`$`, `៛`, USD, KHR, riel, រៀល, ដុល្លារ), also promotion amounts | `Balance: 1,250.00 USD` → `Balance: [REDACTED]`, `up to $10` → `up to [REDACTED]` |
| An amount after a money word (balance, amount, total, សមតុល្យ, ទឹកប្រាក់ …) without a currency | `Balance 1250.00` → `Balance [REDACTED]` |
| Reference / transaction numbers after a keyword | `Ref: FT26111200123` → `Ref: [REDACTED]` |
| Any number with 6+ digits (spaces allowed) | `000 123 456 78` → `[REDACTED]` |

Why so strict: OCR drops separators and misreads digits (test 2026-11-12: `$590` → `$390`, `$1,240` → `$1230`), so a card or account number can come out in a shape the typed-message rules do not catch, and "looks like a balance" cannot be told reliably. Over-masking costs little; the customer sees the result before sending.

Kept readable, so the bot can still help: error codes (`Error 403`), short codes and order numbers (up to 5 digits), percentages (`5% cashback`), dates with `-` or `/`, opening hours.

**Not masked: people's names.** They cannot be found reliably. A screenshot showing an account holder's name sends that name to Botpress. The customer sees the text before sending and can remove the file instead.

## 6. What changed

| Part | Change |
|---|---|
| Database (Flyway **V7**) | `attachment.handover_id` optional (filled in at handover); new `sent_at` (null = draft), `read_status` (`READ`, `NO_TEXT`, `FAILED`, `SKIPPED`; null = live agent chat file), `read_text` (masked), `read_masked`; `chat_event.attachment_id` |
| OCR | `OcrPort` with two adapters, picked by `OCR_ENGINE`: **`EmbeddedTesseractOcr`** (default; Tess4J 5.13.0 from Maven Central, which bundles Tesseract 5.4.1 for Windows x64, so no installer) and `TesseractOcr` (runs an installed `tesseract` program as a separate process, killed at the timeout; for a Linux server). `FileTextReader`: scaling, PDF text layer via PDFBox 3.0.4, scanned pages, clean-up |
| API | `POST /api/v1/chat/attachments` (any mode; draft in the AI chat, to the agent in a live chat), `DELETE /api/v1/chat/sessions/{id}/attachments/{fileId}` (remove a draft), `POST /api/v1/chat/messages` accepts `attachmentId` (text may then be empty). The old `POST /sessions/{id}/attachments` still works, now also while waiting for an agent. 429 for the limits. |
| Chat flow | `ChatService` builds the bracketed message for the bot from the masked text and the masked question; stores it as a chat event pointing at the file |
| Handover | `HandoverService` links files sent to the bot to a new handover (also when a Desk agent joins on their own) as customer file messages; Botpress Desk lists them with the request, before anyone joins |
| Clean-up | Drafts never sent are erased after 1 hour (every 15 min); the 30-day retention covers the rest. Erasing removes the masked text too. |
| Widget | 📎 in every mode; draft card with the text the bot will read; "Reading your file…"; file + question in one bubble; one file per message |
| Botpress Studio (manual) | Instructions: how to treat `[The customer sent …]` blocks; never ask customers for card or ID photos; offer an agent when unsure |

## 7. Configuration

| Setting | Default | Meaning |
|---|---|---|
| `OCR_ENABLED` | `false` | Off: files can be sent in the AI chat, nothing is read, the bot offers an agent |
| `OCR_ENGINE` | `embedded` | `embedded`: Tesseract as a Java library, nothing to install (Windows x64). `program`: an installed `tesseract` program |
| `OCR_TESSDATA_DIR` | (empty) | Folder with `khm.traineddata` and `eng.traineddata`. Empty = `backend/tessdata` (embedded) or Tesseract's own folder (program) |
| `OCR_TESSERACT_PATH` | `tesseract` | Program engine only: the program, e.g. `/usr/bin/tesseract` |
| `OCR_LANGUAGES` | `khm+eng` | Languages Tesseract looks for |
| `OCR_TIMEOUT` | `10s` | Per file; slower counts as "could not be read" |
| `OCR_MIN_CONFIDENCE` | `60` | Lines Tesseract is less sure about (0–100) are dropped. On icons, charts and photos Tesseract "reads" noise with low confidence. Lower it if real Khmer lines go missing. |
| `ATTACHMENT_MAX_PER_SESSION` | `5` | Files a customer can send to the AI chat per chat (live agent chats: no limit) |

The startup log says whether OCR is on, and warns when a language file is missing or the engine cannot start.

**Setting up OCR on Windows (no installer, no Docker):**

1. Download the two language data files (data, not programs) from the official repository https://github.com/tesseract-ocr/tessdata: open `eng.traineddata`, then the download button; the same for `khm.traineddata`.
2. Put both in `backend/tessdata/` (create the folder). It is git-ignored.
3. In `backend/.env`: `OCR_ENABLED=true`.
4. Restart the backend. The log shows `OCR is ON (Tesseract library, languages khm+eng, ...)`.

More accurate, slower Khmer: `khm.traineddata` from https://github.com/tesseract-ocr/tessdata_best instead.

**On a Linux server:** Tess4J bundles Windows only. Install from the system's signed packages (`apt install tesseract-ocr tesseract-ocr-khm`) and set `OCR_ENGINE=program`.

## 8. Known limits

- **Khmer OCR with Tesseract is weaker than English.** Test with generated images first (section 10). Fallback: PaddleOCR on our server, behind the same `OcrPort`.
- **OCR reads words, not pictures.** For a promotion that is mostly a picture, the bot only gets the words on it.
- **OCR noise.** On icons, charts, logos and photos Tesseract still outputs something (seen 2026-11-12: `-- @'- - @»- -g`, `mEnEowwss Zhems`). Lines below `OCR_MIN_CONFIDENCE` and lines that are mostly symbols are dropped; if nothing is left, the file counts as "no text".
- **Dense, low-resolution screenshots read poorly.** Test 2026-11-12 with a generated 752 px dashboard mockup (tiny text, a table): after the tuning below, headings, labels, totals and some table cells were read, but most table rows were missed and some digits misread (`$590` → `$390`). Misread numbers can slip past masking. Small icons next to labels come back as stray Khmer letters (`យ Orders`). Phone screenshots (1080 px and up, larger text) read much better. Tuning applied: images use Tesseract's sparse-text mode (psm 11; read 3× more items than paragraph mode), scanned PDF pages keep paragraph mode (psm 3), small images are enlarged up to 3×, lines with fewer than 3 letters or digits are dropped.
- **Phone photos and dark mode** are handled before OCR: the EXIF orientation flag is applied (Java ignores it; a sideways photo would read as nothing), and images that are mostly dark (average brightness < 110) are inverted. A screen photographed at an angle, with glare or moiré, still reads poorly.
- **Khmer accuracy (first test, 2026-11-12, default `khm.traineddata`):** a clean generated line came back with one wrong letter (`បរាជ័យ` → `ឃរាជ័យ`). Try `tessdata_best` next.
- **Names are not masked** (section 5).
- **OCR adds about 1–3 seconds per image** while the draft card is prepared.
- **Embedded engine runs inside the backend.** A native call cannot be stopped: at the timeout the file counts as unreadable while the call finishes in the background (at most 2 at a time). A crash inside Tesseract would stop the backend; the `program` engine avoids that. The bundled engine needs the Microsoft Visual C++ runtime, which most Windows machines have; the startup log says if it cannot start.
- OCR can misread digits (glare, blur, angle); misread digits can slip past masking. The customer sees the text before sending; the upload warning stays the first line of defence.
- Files linked to one handover are not linked again to a later handover in the same chat.
- The hidden agent console (console mode) shows the earlier files as customer file messages; it was not tested further.

## 9. Decisions taken (R&D defaults, open for the tech lead)

1. **What the AI gets:** masked text only, no image to a vision model.
2. **What the agent sees:** the original file, as in live agent chats.
3. **Names in file text:** sent, but the customer sees the text first and can remove the file.
4. **Limits:** 5 files per chat, 5 MB per file, one file every 5 seconds.

## 10. Testing

Done (2026-11-12):

- Unit tests: masking of file text, reading (scaling, OCR noise, failure, PDF text layer, scanned PDF page limit), drafts, limits, discard, unsent clean-up, the message to the bot, handover linking, the Desk message.
- End to end against Botpress, with `OCR_ENABLED=true` and Tesseract **not** installed: a generated PDF (fake error, test card `4111 1111 1111 1111`, email, balance, reference) was read from its text layer, all four values masked, and the bot answered from the masked text. A generated image counted as `FAILED` and got the agent offer. Limits (429), discard before and after sending (204 / 409), and linking at handover all worked. Widget checked in Chrome: draft card, file + question bubble, no console errors.

Still to do, once Tesseract is installed:

- Generated sample images only, never real customer files: fake app error screens, a fake promotion, a fake bank card, a mock receipt with a phone number; Khmer and English; sharp, blurry, rotated and dark.
- Measure how much text OCR reads correctly, and whether masking hides every number it should.
- Virus test with the EICAR file once ClamAV is on.
- Botpress Desk: the agent opens the earlier files through the secure link.
