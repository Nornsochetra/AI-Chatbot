# AI Chatbot: Attachments (Photo / File Upload)

Status: **Implemented and verified end to end on 2026-11-11.** Simple upload: **no OCR / no image masking** — files are saved as uploaded and the agent opens them through a link. The OCR + masking design is kept as a reference in [attachments.md](attachments.md). See [handover.md](handover.md) for the live agent chat this builds on, and [technology.md](technology.md) for the stack.

## 1. Goal

Requirements section III.F: customers can open the camera, share a photo or upload a supported file during a chat, and live agents can view uploaded attachments.

## 2. Principle

**A file goes only to the bank's own server and only the human agent can open it. It is never sent to any AI.**

- Botpress (chat AI) ✗ never receives files
- Gemini (voice AI) ✗ never receives files
- The file is stored **as uploaded** (no OCR, no masking) in the bank's database
- Only the agent of that chat opens it, through a link that checks the agent login; every view is audited

## 3. Flow

```
Customer (in a live agent chat) taps 📎 → picks a photo / PDF
   │  warning first: "Never send photos of your bank card, PIN or OTP."
   │  widget checks: JPG / PNG / PDF, ≤ 5 MB
   ▼
Our backend (bank's own server, in Cambodia)
   1. Check type (JPG / PNG / PDF) and size (≤ 5 MB)  ── wrong type / too big → rejected with a message
   2. Virus scan (ClamAV)                             ── infected → rejected
   3. Save the file in the database (as uploaded), linked to the chat
   ▼
Chat message: "📎 photo.jpg"
   ├─ Customer sees the file name (and a small preview of their own image)
   └─ Agent console: "📎 photo.jpg" (the name is a link) → click → opens the file in a new tab
                     (login checked, every view written to the audit log)

Botpress (AI)  ✗ never receives the file
Gemini (AI)    ✗ never receives the file
```

## 4. The agent's link

- In the console chat: **📎 file name · size**, with **Delete** below. The **file name is the link**.
- Clicking the name opens the file in a new tab. It is fetched with the agent's login, so there are **no public URLs**; a link copied out of the console does not work for anyone else.
- Only the agent handling that chat (and later, supervisors / admins) can open it.
- Every view is written to the **audit log** (who, which file, when). This starts the audit log for the admin portal.
- **Delete** button: asks first in a pop-up (**Delete "file name"?** — Cancel / Delete), then removes the file (logged); the customer sees that the agent removed it. Used e.g. when a customer sends a card photo by mistake.

## 5. Safe serving of files

Even without masking, files are served carefully so a disguised file cannot cause harm:

| Protection | What it does |
|---|---|
| Type allow-list | Only JPG, PNG and PDF are accepted; the type is checked from the file content, not only the name |
| Virus scan | ClamAV before saving (requirement) |
| Random ids | The customer's file name is shown as a label only; files are looked up by a random id |
| Safe headers | Served with the checked content type, `X-Content-Type-Options: nosniff` and no caching, so the browser cannot run a disguised file |
| Login on every request | The agent API checks the agent login for each file |

## 6. Limits

| Limit | Value |
|---|---|
| File types | JPG, PNG, PDF |
| Max size | **5 MB per file** (checked in the widget before upload and again in the backend) |
| Retention | Automatic deletion after N days (bank decides, e.g. 30) |

## 7. Where uploads are allowed

**Only during a live agent chat.** The 📎 button appears in the widget once an agent has joined, because the AI must not collect personal data (requirement).

## 8. Storage

| Part | Choice | Note |
|---|---|---|
| Where | PostgreSQL table `attachment` (file bytes + type, size, chat, uploader, time) | Simple for R&D; 5 MB limit keeps the database manageable |
| Link to chat | A handover message of type attachment, so it appears in the conversation for both sides | |
| Retention | Scheduled job deletes files older than N days | |
| Later | Move the bytes to file / object storage behind `FileStoragePort` if volume grows | The database then keeps only the metadata |

Files stay in Cambodia on the bank's server and are never sent to Botpress or Gemini.

## 9. Risks of storing files unmasked

Without masking, a photo of a bank card or national ID card is stored exactly as uploaded:

| Risk | Mitigation in this flow |
|---|---|
| **Bank card photos** — the card number and CVV fall under PCI DSS; the CVV must never be stored | Warning before upload; agents never ask for card photos; agent **Delete** button; short retention |
| **National ID photos** — personal data | Agent-only access, audited views, retention; Compliance decides whether agents may request them |
| Someone copies the database or a backup | Optional: encrypt the file bytes in the database (AES, key in `.env`) |

The OCR + masking design in [attachments.md](attachments.md) (black boxes over card / ID numbers before storing) remains an option for later if Compliance requires it.

## 10. Technology

| Part | Choice |
|---|---|
| Backend | New `attachment` feature (controller / service / payload / mapper), Flyway migration for the `attachment` table |
| Virus scan | ClamAV (native Windows install), called over its local network port |
| Widget | 📎 button (camera / photo / file picker), size and type check, upload progress, preview |
| Agent console | 📎 message with **View** and **Delete** |

## 11. Testing plan

- Upload JPG / PNG / PDF under 5 MB → saved, shown in both chats, agent opens it with **View**.
- Over 5 MB, wrong type (e.g. `.exe` renamed to `.jpg`) → rejected.
- Virus test file (standard EICAR file) → rejected.
- Link without agent login → 401; another session's file → 404.
- Every view appears in the audit log; **Delete** removes the file.
- No file ever reaches Botpress or Gemini.
- Retention job removes old files.

## 12. Decisions needed

1. Retention period, e.g. **30 days**?
2. Encrypt the file bytes in the database? (Recommended for production.)
3. May agents ask customers for ID photos at all? (Compliance — the requirements say national ID needs a separately approved process.)
4. Is storing card / ID photos unmasked acceptable to Compliance, or is the OCR + masking design needed later?

## 13. Implementation (2026-11-11)

Decisions taken for R&D (all are settings):

| Decision | R&D choice | Setting |
|---|---|---|
| Retention | 30 days, erased every night at 03:00 | `ATTACHMENT_RETENTION_DAYS` |
| Virus scan | ClamAV client built in, **off until ClamAV is installed** (uploads are then not scanned; a warning is logged at startup). When on and ClamAV is unreachable, uploads are refused | `CLAMAV_ENABLED`, `CLAMAV_HOST`, `CLAMAV_PORT` |
| Encryption of stored bytes | Not for R&D (recommended for production) | — |

API:

| Method | Path | Who | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/chat/sessions/{sessionId}/attachments` | customer (widget) | multipart field `file`; 409 unless an agent has joined; 400 wrong type / empty / virus; 413 over 5 MB |
| `GET` | `/api/v1/agent/handovers/{handoverId}/attachments/{attachmentId}` | agent | the file, with the detected type, `no-store`, `nosniff`, inline file name; audited |
| `DELETE` | same | agent | erases the bytes; both sides get the "file removed" event; audited |

Data (Flyway `V5__attachment_and_audit.sql`): `attachment` (metadata), `attachment_data` (bytes, in their own table so chat lists never load files), `handover_message.attachment_id` (the upload and the "file removed" event appear in the chat), `audit_event` (who did what, when — start of the admin portal audit log: `ATTACHMENT_VIEWED`, `ATTACHMENT_DELETED`, `ATTACHMENT_EXPIRED`, `ATTACHMENT_REJECTED_VIRUS`).

Code: `attachment` feature (`AttachmentService`, `FileTypeDetector` — type from the first bytes, `MalwareScanPort` / `ClamAvMalwareScanner` — clamd INSTREAM over TCP), `audit` feature (`AuditService`).

Widget: 📎 button next to the mic, only while an agent is chatting; type / 5 MB check before upload; "Sending file…"; the file appears as the customer's message (name, size, preview of their own image); "Removed by the agent" when deleted; the input hint changes to "Never send photos of your bank card, PIN or OTP."

Agent console: 📎 **file name** (a link: click it to open the file in a new tab, fetched with the agent login) · size, and **Delete**, which opens a confirmation pop-up (*Delete "file name"?* — Cancel / Delete). After deleting, the name is crossed out and no longer clickable, with "File removed". The live push shows new files instantly.

### Turning on the virus scan (ClamAV on Windows)

1. Install ClamAV for Windows from clamav.net.
2. In its folder, copy `conf_examples\clamd.conf.sample` to `clamd.conf` and `freshclam.conf.sample` to `freshclam.conf`; remove the `Example` line in both; in `clamd.conf` set `TCPSocket 3310` and `TCPAddr localhost`.
3. Run `freshclam.exe` (downloads the virus database), then start `clamd.exe`.
4. In `backend/.env` set `CLAMAV_ENABLED=true` and restart the backend.
5. Test with the standard EICAR test file: the upload must be rejected with "The file was rejected by the virus scan".

### Verification (2026-11-11)

Unit tests: backend 99 (attachment service 10: chat state rules, type from content, size, virus, view / delete / audit, other chat, retention, file names; ClamAV client 5 against a fake clamd: clean, infected, unreachable → refused, off, error reply), widget 33 (sent file, "removed" marking, upload call, 409 mapping).

End-to-end against the real backend and PostgreSQL (20 checks, all passed):

| Check | Result |
|---|---|
| Upload with the bot / while waiting for an agent → 409 | ✅ |
| Upload PNG during the agent chat → 200; folders stripped from the name | ✅ |
| Agent console gets the file pushed live | ✅ 61 ms |
| Renamed `.exe` as `photo.jpg` → 400; HTML → 400; empty → 400; over 5 MB → 413 | ✅ |
| PDF named `.jpg` → stored as `application/pdf` | ✅ |
| Agent View → exact bytes, detected type, `no-store`, `nosniff`, inline name | ✅ |
| View without login → 401; through another chat → 404 | ✅ |
| Delete → 204; customer gets "file removed" pushed (named); View afterwards → 404; Delete twice → 204 | ✅ |
| Audit log in the database: `agent / ATTACHMENT_VIEWED`, `agent / ATTACHMENT_DELETED` | ✅ |
| Chat history shows the deleted file as removed | ✅ |

Checked in a browser (agent console, 2026-11-11): the file name opens the image in a new tab; Delete shows the confirmation pop-up; Cancel keeps the file; Delete removes it (name crossed out, "File removed", "agent removed a file").

Not checked yet: the widget's 📎 button clicked through in a browser, ClamAV with a real installation (the client is tested against a fake clamd), and camera capture on a phone.
