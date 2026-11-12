import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';

import { ALLOWED_ATTACHMENT_TYPES, ChatApiError, MAX_ATTACHMENT_BYTES } from '../api/chatApi';
import type { Messages } from '../i18n';
import { isRecordingSupported, useRecorder } from '../lib/useRecorder';
import type { DraftFile } from '../state/chatReducer';
import { formatSize } from './MessageBubble';

export const MAX_LENGTH = 2000;
const COUNTER_FROM = MAX_LENGTH - 200;
const MAX_INPUT_HEIGHT = 120;
/** longest voice question; the recording stops by itself after this */
export const MAX_RECORDING_SECONDS = 60;
/** shorter clicks are treated as "nothing said" without calling the backend */
const MIN_RECORDING_SECONDS = 0.7;

type Notice = 'denied' | 'unavailable' | 'noSpeech' | 'fileType' | 'fileTooLarge' | 'fileLimit' | 'fileFailed' | null;

interface Props {
  t: Messages;
  disabled: boolean;
  onSend: (text: string) => void;
  /** sends the recording as a voice message (shown with its transcript); rejects when nothing was understood */
  onVoice: (audio: Blob, seconds: number) => Promise<void>;
  /** called on each keystroke (the parent throttles it), so a live agent can see the customer typing */
  onTyping?: () => void;
  /** file from the 📎: in the AI chat it becomes the draft below; in a live chat it goes to the agent */
  onAttach?: (file: File) => Promise<void>;
  /** AI chat: the file is read on the server while uploading ("Reading your file…") */
  readingFiles?: boolean;
  /** AI chat file waiting to be sent with the next message, with the text the bot will read */
  draft?: DraftFile | null;
  onDiscardDraft?: () => void;
}

export function Composer({ t, disabled, onSend, onVoice, onTyping, onAttach, readingFiles, draft, onDiscardDraft }: Props) {
  const [text, setText] = useState('');
  const [transcribing, setTranscribing] = useState(false);
  const [voiceNotice, setVoiceNotice] = useState<Notice>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [voiceSupported] = useState(isRecordingSupported);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const recorder = useRecorder({
    maxSeconds: MAX_RECORDING_SECONDS,
    onComplete: (audio, seconds) => void handleRecording(audio, seconds),
  });

  // grow with the text, up to the CSS max-height
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT)}px`;
    // only show a scrollbar once the text is taller than the box can grow
    el.style.overflowY = el.scrollHeight > MAX_INPUT_HEIGHT ? 'auto' : 'hidden';
  }, [text]);

  async function handleRecording(audio: Blob, seconds: number) {
    if (seconds < MIN_RECORDING_SECONDS || audio.size === 0) {
      setVoiceNotice('noSpeech');
      return;
    }
    setTranscribing(true);
    try {
      await onVoice(audio, seconds);
    } catch (e) {
      setVoiceNotice(e instanceof ChatApiError && e.kind === 'validation' ? 'noSpeech' : 'unavailable');
    } finally {
      setTranscribing(false);
    }
  }

  async function toggleRecording() {
    setVoiceNotice(null);
    if (recorder.recording) {
      recorder.stop();
      return;
    }
    try {
      await recorder.start();
    } catch {
      // NotAllowedError (blocked), NotFoundError (no microphone), ...
      setVoiceNotice('denied');
    }
  }

  async function attach(file: File | undefined) {
    if (!file || !onAttach) return;
    setVoiceNotice(null);
    // same limits as the backend; checked here first so the customer gets an instant answer
    if (!ALLOWED_ATTACHMENT_TYPES.includes(file.type)) {
      setVoiceNotice('fileType');
      return;
    }
    if (file.size > MAX_ATTACHMENT_BYTES) {
      setVoiceNotice('fileTooLarge');
      return;
    }
    setUploading(true);
    try {
      await onAttach(file);
    } catch (e) {
      const kind = e instanceof ChatApiError ? e.kind : 'server';
      setVoiceNotice(kind === 'limit' ? 'fileLimit' : kind === 'validation' ? 'fileType' : 'fileFailed');
    } finally {
      setUploading(false);
    }
  }

  function submit(e?: FormEvent) {
    e?.preventDefault();
    const value = text.trim();
    // a file alone can be sent; the question is optional
    if ((!value && !draft) || disabled) return;
    onSend(value);
    setText('');
    setVoiceNotice(null);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends, Shift+Enter adds a line; ignore Enter while an input method is composing
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault(); // otherwise the Enter also lands in the emptied box as a new line
      submit();
    }
  }

  const remaining = MAX_LENGTH - text.length;
  const noticeText = {
    denied: t.voiceDenied,
    unavailable: t.voiceUnavailable,
    noSpeech: t.voiceNoSpeech,
    fileType: t.attachWrongType,
    fileTooLarge: t.attachTooLarge,
    fileLimit: t.attachLimit,
    fileFailed: t.attachFailed,
  };

  return (
    <form className="kb-composer" onSubmit={submit}>
      {draft && (
        <div className="kb-draft" role="group" aria-label={t.attachDraftLabel}>
          <div className="kb-draft__head">
            {draft.previewUrl && <img className="kb-draft__thumb" src={draft.previewUrl} alt="" />}
            <span className="kb-draft__name">
              <span aria-hidden="true">📎 </span>
              {draft.fileName}
            </span>
            <span className="kb-file__size">{formatSize(draft.sizeBytes)}</span>
            <button type="button" className="kb-draft__remove" onClick={onDiscardDraft} aria-label={t.attachRemove} title={t.attachRemove}>
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </div>
          {draft.readStatus === 'READ' && draft.readText ? (
            <>
              <div className="kb-draft__label">{t.attachBotWillRead}</div>
              {/* plain text: exactly what the bot gets, after masking */}
              <div className="kb-draft__text">{draft.readText}</div>
              {draft.readMasked && <div className="kb-draft__hint">{t.attachTextMasked}</div>}
            </>
          ) : (
            <div className="kb-draft__hint">{draft.readStatus === 'SKIPPED' ? t.attachNotRead : t.attachNoText}</div>
          )}
        </div>
      )}
      <div className="kb-composer__row">
        {onAttach && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept={ALLOWED_ATTACHMENT_TYPES.join(',')}
              hidden
              onChange={(e) => {
                void attach(e.target.files?.[0]);
                e.target.value = ''; // the same file can be picked again
              }}
            />
            <button
              type="button"
              className="kb-mic"
              onClick={() => fileInputRef.current?.click()}
              // one file per message
              disabled={uploading || !!draft}
              aria-label={t.attach}
              title={t.attach}
            >
              {uploading ? (
                <span className="kb-spinner" aria-hidden="true" />
              ) : (
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <path d="M21 11.5l-8.6 8.6a5 5 0 0 1-7.1-7.1l8.6-8.6a3.5 3.5 0 0 1 5 5l-8.6 8.6a2 2 0 0 1-2.8-2.8l7.9-7.9" />
                </svg>
              )}
            </button>
          </>
        )}
        {voiceSupported && (
          <button
            type="button"
            className={`kb-mic ${recorder.recording ? 'kb-mic--recording' : ''}`}
            onClick={() => void toggleRecording()}
            // no new recording while the last one or the bot's answer is still on its way
            disabled={transcribing || (disabled && !recorder.recording)}
            aria-pressed={recorder.recording}
            aria-label={recorder.recording ? t.voiceStop : t.voiceStart}
            title={recorder.recording ? t.voiceStop : t.voiceStart}
          >
            {transcribing ? (
              <span className="kb-spinner" aria-hidden="true" />
            ) : recorder.recording ? (
              <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
            ) : (
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
                <path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z" />
              </svg>
            )}
          </button>
        )}
        <textarea
          ref={inputRef}
          className="kb-composer__input"
          rows={1}
          value={text}
          maxLength={MAX_LENGTH}
          placeholder={
            recorder.recording ? t.voiceRecording : transcribing ? t.voiceTranscribing : draft ? t.attachQuestionPlaceholder : t.placeholder
          }
          aria-label={t.placeholder}
          onChange={(e) => {
            setText(e.target.value);
            if (e.target.value.trim()) onTyping?.();
          }}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="kb-button kb-button--primary kb-composer__send" disabled={disabled || (!text.trim() && !draft)}>
          {t.send}
        </button>
      </div>
      <div className="kb-composer__meta">
        {recorder.recording ? (
          <span className="kb-recording" role="status">
            ● {t.voiceRecording} {recorder.elapsed}s / {MAX_RECORDING_SECONDS}s · {t.voiceSafety}
          </span>
        ) : voiceNotice ? (
          <span className="kb-voice-notice" role="status">
            {noticeText[voiceNotice]}
          </span>
        ) : (
          <span>{uploading ? (readingFiles ? t.attachReading : t.attachUploading) : onAttach ? t.attachWarning : t.safetyHint}</span>
        )}
        {text.length >= COUNTER_FROM && (
          <span className="kb-composer__counter">
            {remaining} {t.charactersLeft}
          </span>
        )}
      </div>
    </form>
  );
}
