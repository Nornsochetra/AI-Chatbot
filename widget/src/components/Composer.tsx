import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';

import { ALLOWED_ATTACHMENT_TYPES, ChatApiError, MAX_ATTACHMENT_BYTES } from '../api/chatApi';
import type { Messages } from '../i18n';
import { isRecordingSupported, useRecorder } from '../lib/useRecorder';

export const MAX_LENGTH = 2000;
const COUNTER_FROM = MAX_LENGTH - 200;
const MAX_INPUT_HEIGHT = 120;
/** longest voice question; the recording stops by itself after this */
export const MAX_RECORDING_SECONDS = 60;
/** shorter clicks are treated as "nothing said" without calling the backend */
const MIN_RECORDING_SECONDS = 0.7;

type Notice = 'denied' | 'unavailable' | 'noSpeech' | 'fileType' | 'fileTooLarge' | 'fileNeedsAgent' | 'fileFailed' | null;

interface Props {
  t: Messages;
  disabled: boolean;
  onSend: (text: string) => void;
  /** speech-to-text; the transcript is put in the input box so the customer can check it before sending */
  onTranscribe: (audio: Blob) => Promise<string>;
  /** called on each keystroke (the parent throttles it), so a live agent can see the customer typing */
  onTyping?: () => void;
  /** file upload; only passed while a live agent is chatting (the AI must not receive files) */
  onAttach?: (file: File) => Promise<void>;
}

export function Composer({ t, disabled, onSend, onTranscribe, onTyping, onAttach }: Props) {
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
      const transcript = (await onTranscribe(audio)).trim();
      if (!transcript) {
        setVoiceNotice('noSpeech');
        return;
      }
      setText((current) => (current.trim() ? `${current.trim()} ${transcript}` : transcript).slice(0, MAX_LENGTH));
      inputRef.current?.focus();
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
      setVoiceNotice(kind === 'conflict' ? 'fileNeedsAgent' : kind === 'validation' ? 'fileType' : 'fileFailed');
    } finally {
      setUploading(false);
    }
  }

  function submit(e?: FormEvent) {
    e?.preventDefault();
    const value = text.trim();
    if (!value || disabled) return;
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
    fileNeedsAgent: t.attachNeedsAgent,
    fileFailed: t.attachFailed,
  };

  return (
    <form className="kb-composer" onSubmit={submit}>
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
              disabled={uploading}
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
            disabled={transcribing}
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
          placeholder={recorder.recording ? t.voiceRecording : transcribing ? t.voiceTranscribing : t.placeholder}
          aria-label={t.placeholder}
          onChange={(e) => {
            setText(e.target.value);
            if (e.target.value.trim()) onTyping?.();
          }}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="kb-button kb-button--primary kb-composer__send" disabled={disabled || !text.trim()}>
          {t.send}
        </button>
      </div>
      <div className="kb-composer__meta">
        {recorder.recording ? (
          <span className="kb-recording" role="status">
            ● {t.voiceRecording} {recorder.elapsed}s / {MAX_RECORDING_SECONDS}s
          </span>
        ) : voiceNotice ? (
          <span className="kb-voice-notice" role="status">
            {noticeText[voiceNotice]}
          </span>
        ) : (
          <span>{uploading ? t.attachUploading : onAttach ? t.attachWarning : t.safetyHint}</span>
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
