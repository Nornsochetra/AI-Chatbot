# AI Chatbot: Attachments (Photo / File Upload)

Status: **Draft — proposed flow, not implemented yet.** Open for review and changes. See [handover.md](handover.md) for the live agent chat this builds on, and [technology.md](technology.md) for the stack.

## 1. Goal

Requirements section III.F: customers can open the camera, share a photo or upload a supported file during a chat, and live agents can view uploaded attachments.

Customers sometimes upload very sensitive images — a photo of their **bank card**, **national ID card**, or a document with phone or account numbers. Text masking cannot see inside an image, so files need their own protection.

## 2. Principle

**A file is masked on the bank's own server before anyone sees it, and it is never sent to any AI.**

- Botpress (chat AI) ✗ never receives files
- Gemini (voice AI) ✗ never receives files
- Only the **human agent** sees the file, and only the **masked** version
- The original is **never stored** — only the masked copy exists

## 3. Flow

```
Customer (in a live agent chat) taps 📎 → picks a photo / PDF
   │  warning first: "Never send your PIN or OTP. Card and ID numbers will be hidden automatically."
   │  widget checks: JPG / PNG / PDF, ≤ 5 MB
   ▼
Our backend (bank's own server, in Cambodia) — everything below happens here
   1. Check type (JPG / PNG / PDF) and size (≤ 5 MB)  ── wrong type / too big → rejected with a message
   2. Virus scan (ClamAV)                             ── infected → rejected
   3. Read the text in the image (OCR with Tesseract, installed on our server — not a cloud AI)
   4. Apply the SAME masking rules as text            ── card number, CVV, expiry, ID number, phone, OTP …
   5. Draw black boxes over the matching words
   6. Re-save the image (also removes GPS location / camera data)
   7. Store ONLY the masked file (encrypted); the original is thrown away
   ▼
Chat message: "📎 photo.jpg — 3 areas hidden"
   ├─ Customer sees a small masked preview (so they know what the agent sees)
   └─ Agent console: "📎 photo.jpg (masked) · View" → click → opens the masked file in a new tab
                     (login checked, every view written to the audit log)

Botpress (AI)  ✗ never receives the file
Gemini (AI)    ✗ never receives the file
```

## 4. How the image masking works

1. **OCR** reads every word in the image together with its position (a box with coordinates).
2. The words are joined into lines and **the existing text masking rules** run over them (the same `InputMaskingService` patterns used for chat messages).
3. Each matched word's box is covered with a **solid black rectangle**.
4. The image is re-encoded, so nothing underneath the boxes survives.

**Extra rules for bank cards** (the card standard, PCI DSS, forbids keeping the CVV): when a card number is found, any expiry date (`MM/YY`) and any 3–4 digit group on that image are hidden as well.

**PDFs:** each page is turned into an image, masked, and rebuilt as a new PDF made only of images, so hidden text layers in the original cannot leak.

## 5. Masking level (decision needed)

| Option | Hidden in images | Agent can verify an ID from the photo? |
|---|---|---|
| **A. Full masking** (recommended for R&D) | Card number, CVV, expiry, **ID number**, phone, OTP | No — the agent verifies by asking questions |
| B. Same as agent text messages | Card number, CVV, expiry, OTP | Yes — the ID number stays visible |

Faces and names on ID cards are not masked in either option. The level should be a setting; the bank's Compliance team confirms the choice.

## 6. Fail safe when OCR is not sure

- If OCR fails or times out, **the agent does not get the file**. The customer sees: *"We couldn't check this file. Please type the information instead."*
- The agent always sees the label **"Masked automatically — check before use"** and a **Delete** button.
- The warning shown before upload remains the first line of defense.

## 7. The agent's link

- In the console chat: **📎 file name · N areas hidden · View**.
- Clicking opens the masked file in a new tab. It is fetched with the agent's login (no public URLs).
- Every view is written to the **audit log** (who, which file, when). This starts the audit log for the admin portal.
- **Delete** removes the file immediately (logged); the customer sees "The file was removed."

## 8. Limits

| Limit | Value |
|---|---|
| File types | JPG, PNG, PDF |
| Max size | **5 MB per file** (checked in the widget before upload and again in the backend) |
| PDF pages | Max 5 pages |
| Image size | Large photos scaled down (e.g. to 2000 px on the long side) before OCR, for speed |
| Retention | Automatic deletion after N days (bank decides, e.g. 30) |

## 9. Where uploads are allowed

**Only during a live agent chat.** The 📎 button appears in the widget once an agent has joined, because the AI must not collect personal data (requirement).

If the bank later wants the bot to read images (e.g. a receipt), the same order applies: **mask first on our server, then send only the masked image** to the AI. That needs a separate Compliance approval — a masked ID photo still shows a face and a name.

## 10. Technology

| Part | Choice | Note |
|---|---|---|
| OCR | **Tesseract** via Tess4J (Java) | Runs on the bank's server, not a cloud AI; Khmer + English language data; native Windows install, no Docker |
| PDF | Apache PDFBox | Pages → images → masked → new PDF |
| Images | Java ImageIO (+ TwelveMonkeys for JPEG) | Re-encoding removes GPS and camera data |
| Virus scan | ClamAV (native Windows install) | requirement |
| Storage | Disk behind `FileStoragePort`, **AES encrypted**, key in `.env` | Files stay in Cambodia; never sent to Botpress or Gemini |
| Upgrade path | PaddleOCR (also on our own server) | If Tesseract misses too much on real cards |

## 11. Testing plan

- **Generated sample images only:** fake cards (`4111 1111 1111 1111`), fake ID cards, a mock receipt with a phone number — in English and Khmer. **Never real customer cards.**
- Measure how often numbers are masked correctly, including rotated, blurry and dark photos.
- Test the 5 MB limit, wrong file types, the fail-safe path, virus rejection (standard EICAR test file), the agent's link, the audit log, deletion, and that **no file ever reaches Botpress or Gemini**.

## 12. Known limits

- **OCR will miss some cases** (glare, embossed digits, steep angles). The fail-safe and the agent's Delete button cover this.
- Processing takes about **1–3 seconds per image** on a normal PC.
- Two installs needed on the server / developer machine: **Tesseract** (with the Khmer language file) and **ClamAV**.

## 13. Decisions needed

1. Masking level: **A (full)** or **B (same as agent text messages)**?
2. Throw away the original and keep only the masked file? (Recommended: yes.)
3. Retention period, e.g. **30 days**?
4. May agents ask customers for ID photos at all? (Compliance — the requirements say national ID needs a separately approved process.)
