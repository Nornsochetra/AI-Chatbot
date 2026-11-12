'use client';

import { useEffect, useReducer, useRef, useState } from 'react';

import {
  ChatApiError,
  discardFile,
  endHandover,
  eventsUrl,
  getChatConfig,
  getUpdates,
  readAloud,
  requestHandover,
  sendFile,
  sendMessage,
  sendTyping,
  submitRating,
  transcribe,
  type ChatErrorKind,
  type HandoverReason,
  type SessionUpdates,
  type TypingEvent,
} from '../api/chatApi';
import { messagesFor, type Messages } from '../i18n';
import type { Lang, WidgetConfig } from '../lib/config';
import { newId } from '../lib/ids';
import { detectLanguage } from '../lib/language';
import { waveformPeaks } from '../lib/waveform';
import {
  chatReducer,
  currentAgentName,
  loadState,
  saveState,
  shouldOfferAiRating,
  type ChatMessage,
  type DraftFile,
  type SentAttachment,
} from '../state/chatReducer';
import { ChatHeader } from './ChatHeader';
import { Composer, MAX_LENGTH } from './Composer';
import { MessageList } from './MessageList';
import { Notice } from './Notice';
import type { SpeakState } from './SpeakButton';

const LANG_KEY = 'kb-chat:lang';
/** fallback only: how often to check for agent messages when the real-time stream is not available */
const POLL_MS = 2500;
/** how long "… is typing" stays after the last typing event */
const AGENT_TYPING_VISIBLE_MS = 5000;
/** the customer's typing is sent to the agent at most this often */
const TYPING_THROTTLE_MS = 2000;

function loadLang(fallback: Lang): Lang {
  try {
    const saved = sessionStorage.getItem(LANG_KEY);
    return saved === 'en' || saved === 'km' ? saved : fallback;
  } catch {
    return fallback;
  }
}

function errorText(t: Messages, kind: ChatErrorKind): string {
  switch (kind) {
    case 'validation':
      return t.errorValidation;
    case 'conflict':
    case 'unavailable':
      return t.errorServer;
    case 'limit':
      return t.attachLimit;
    case 'timeout':
      return t.errorTimeout;
    case 'network':
      return t.errorNetwork;
    case 'server':
      return t.errorServer;
  }
}

function errorKind(e: unknown): ChatErrorKind {
  return e instanceof ChatApiError ? e.kind : 'server';
}

export function ChatApp({ config }: { config: WidgetConfig }) {
  const storageKey = `kb-chat:${config.channel}`;
  const [state, dispatch] = useReducer(chatReducer, storageKey, loadState);
  const [lang, setLang] = useState<Lang>(() => loadLang(config.lang));
  const t = messagesFor(lang);

  // the poller reads the latest cursor without restarting on every message
  const cursorRef = useRef(state.cursor);
  cursorRef.current = state.cursor;

  // read-aloud: one reply plays at a time; fetched audio is kept for this page so replaying is free
  const [speaking, setSpeaking] = useState<{ id: string; state: SpeakState } | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioCacheRef = useRef(new Map<string, string>());

  useEffect(() => {
    const cache = audioCacheRef.current;
    return () => {
      audioRef.current?.pause();
      cache.forEach((url) => URL.revokeObjectURL(url));
    };
  }, []);

  useEffect(() => saveState(storageKey, state), [storageKey, state]);

  useEffect(() => {
    document.documentElement.lang = lang;
    document.title = state.mode === 'agent' ? t.agentTitle : t.title;
    try {
      sessionStorage.setItem(LANG_KEY, lang);
    } catch {
      // ignore: storage unavailable
    }
  }, [lang, state.mode, t.title, t.agentTitle]);

  // "sokha is typing…": shown for a few seconds after each typing event, hidden when the agent's message arrives
  const [agentTyping, setAgentTyping] = useState<string | null>(null);
  const agentTypingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  function showAgentTyping(name: string | null) {
    setAgentTyping(name || t.agentLabel);
    if (agentTypingTimerRef.current) clearTimeout(agentTypingTimerRef.current);
    agentTypingTimerRef.current = setTimeout(() => setAgentTyping(null), AGENT_TYPING_VISIBLE_MS);
  }

  function hideAgentTyping() {
    if (agentTypingTimerRef.current) clearTimeout(agentTypingTimerRef.current);
    setAgentTyping(null);
  }

  // the handlers below live in a long-lived stream; keep them current without reconnecting
  const liveHandlersRef = useRef({ showAgentTyping, hideAgentTyping });
  liveHandlersRef.current = { showAgentTyping, hideAgentTyping };

  // Botpress Desk mode: an agent can join any chat, even one the customer did not escalate
  const [agentMayJoinAnytime, setAgentMayJoinAnytime] = useState(false);
  useEffect(() => {
    getChatConfig(config.apiBase)
      .then((c) => setAgentMayJoinAnytime(c.agentMayJoinAnytime))
      .catch(() => undefined); // older backend or network blip: console behaviour
  }, [config.apiBase]);

  // while an agent is waiting or active: real-time push (Server-Sent Events), polling only as a fallback
  const live = state.mode !== 'bot' || agentMayJoinAnytime;
  useEffect(() => {
    if (!live || !state.sessionId) return;
    const sessionId = state.sessionId;
    let stopped = false;
    let inFlight = false;
    let pollTimer: ReturnType<typeof setInterval> | null = null;
    let source: EventSource | null = null;

    function received(updates: SessionUpdates) {
      if (stopped) return;
      if (updates.messages.some((m) => m.sender !== 'CUSTOMER')) liveHandlersRef.current.hideAgentTyping();
      dispatch({ type: 'updates_received', updates });
    }

    async function poll() {
      if (inFlight) return;
      inFlight = true;
      try {
        received(await getUpdates(config.apiBase, sessionId, cursorRef.current));
      } catch {
        // network blip: try again on the next tick
      } finally {
        inFlight = false;
      }
    }

    function startPolling() {
      if (pollTimer || stopped) return;
      void poll();
      pollTimer = setInterval(poll, POLL_MS);
    }

    if (typeof EventSource === 'undefined') {
      startPolling();
    } else {
      source = new EventSource(eventsUrl(config.apiBase, sessionId, cursorRef.current));
      source.addEventListener('update', (e) => received(JSON.parse((e as MessageEvent).data) as SessionUpdates));
      source.addEventListener('typing', (e) => {
        const event = JSON.parse((e as MessageEvent).data) as TypingEvent;
        if (event.who === 'AGENT' && !stopped) liveHandlersRef.current.showAgentTyping(event.name);
      });
      // the browser retries dropped connections by itself; CLOSED means it gave up (e.g. blocked by a proxy)
      source.onerror = () => {
        if (source?.readyState === EventSource.CLOSED) startPolling();
      };
    }

    return () => {
      stopped = true;
      source?.close();
      if (pollTimer) clearInterval(pollTimer);
    };
  }, [live, state.sessionId, config.apiBase]);

  useEffect(() => {
    if (!live) liveHandlersRef.current.hideAgentTyping();
  }, [live]);

  // tell the agent the customer is typing (at most every 2 s)
  const lastTypingSentRef = useRef(0);
  function notifyTyping() {
    if (state.mode !== 'agent' || !state.sessionId) return;
    const now = Date.now();
    if (now - lastTypingSentRef.current < TYPING_THROTTLE_MS) return;
    lastTypingSentRef.current = now;
    sendTyping(config.apiBase, state.sessionId).catch(() => undefined);
  }

  // Requirement: AI-only chats are asked for a rating after a delay; restarts whenever the conversation moves
  const offerAiRating = shouldOfferAiRating(state);
  useEffect(() => {
    if (!offerAiRating) return;
    const timer = setTimeout(() => dispatch({ type: 'ai_rating_prompt', id: newId() }), config.aiRatingDelaySeconds * 1000);
    return () => clearTimeout(timer);
  }, [offerAiRating, state.messages.length, config.aiRatingDelaySeconds]);

  async function rate(message: ChatMessage, score: number, comment: string) {
    if (!state.sessionId || !message.ratingType) return;
    dispatch({ type: 'rating_status', id: message.id, status: 'sending' });
    try {
      await submitRating(config.apiBase, state.sessionId, { type: message.ratingType, score, comment: comment || undefined });
      dispatch({ type: 'rating_status', id: message.id, status: 'sent' });
    } catch (e) {
      // already rated (e.g. from another tab) counts as done
      const status = e instanceof ChatApiError && e.kind === 'conflict' ? 'sent' : 'error';
      dispatch({ type: 'rating_status', id: message.id, status });
    }
  }

  function stopSpeaking() {
    audioRef.current?.pause();
    setSpeaking(null);
  }

  async function speak(message: ChatMessage) {
    if (!message.replyId || !state.sessionId) return;
    if (speaking?.id === message.id && speaking.state === 'playing') {
      stopSpeaking();
      return;
    }
    audioRef.current?.pause();
    setSpeaking({ id: message.id, state: 'loading' });
    try {
      let url = audioCacheRef.current.get(message.replyId);
      if (!url) {
        url = URL.createObjectURL(await readAloud(config.apiBase, state.sessionId, message.replyId));
        audioCacheRef.current.set(message.replyId, url);
      }
      const audio = audioRef.current ?? new Audio();
      audioRef.current = audio;
      audio.src = url;
      audio.onended = () => setSpeaking((current) => (current?.id === message.id ? null : current));
      await audio.play();
      setSpeaking({ id: message.id, state: 'playing' });
    } catch {
      setSpeaking({ id: message.id, state: 'error' });
    }
  }

  // previews of the customer's own images; released when the chat closes
  const previewUrlsRef = useRef<string[]>([]);
  useEffect(() => {
    const urls = previewUrlsRef.current;
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, []);

  // AI chat: the picked file waits above the composer, with the text the bot will read, until the next Send
  const [draft, setDraft] = useState<DraftFile | null>(null);

  /**
   * File from the 📎; errors are shown by the Composer. In a live chat it goes to the agent at once. In the AI
   * chat the backend keeps it as a draft and reads its text (masked); the bot gets nothing until Send.
   */
  async function attach(file: File) {
    const response = await sendFile(config.apiBase, { sessionId: state.sessionId, channel: config.channel, file });
    if (!state.sessionId) dispatch({ type: 'session_started', sessionId: response.sessionId });
    let previewUrl: string | undefined;
    if (file.type.startsWith('image/')) {
      previewUrl = URL.createObjectURL(file);
      previewUrlsRef.current.push(previewUrl);
    }
    if (!response.draft) {
      dispatch({ type: 'attachment_sent', id: newId(), response: response.attachment, previewUrl });
      return;
    }
    const { id, fileName, contentType, sizeBytes } = response.attachment;
    setDraft({
      id,
      fileName,
      contentType,
      sizeBytes,
      previewUrl,
      readStatus: response.readStatus,
      readText: response.text ?? undefined,
      readMasked: response.textMasked,
    });
  }

  /** The customer removed the file before sending it: erased on the backend (best effort; unsent files expire). */
  function discardDraft() {
    if (!draft) return;
    if (state.sessionId) discardFile(config.apiBase, state.sessionId, draft.id).catch(() => undefined);
    setDraft(null);
  }

  /** Send pressed: the typed question goes with the draft file, if there is one. */
  function sendTyped(text: string) {
    const file = draft;
    setDraft(null);
    const attachment: SentAttachment | undefined = file
      ? { id: file.id, fileName: file.fileName, contentType: file.contentType, sizeBytes: file.sizeBytes, previewUrl: file.previewUrl }
      : undefined;
    void send(text, undefined, true, attachment);
  }

  // the customer's recordings, played back from their own device; released when the chat closes
  const voiceUrlsRef = useRef<string[]>([]);
  useEffect(() => {
    const urls = voiceUrlsRef.current;
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, []);

  /**
   * Voice message: the bubble (waveform + "turning your voice into text…") appears at once, then the
   * transcript fills in and goes the normal text path (masking, Botpress or the live agent).
   * Only the transcript leaves the browser as a message; the recording itself is not stored anywhere.
   */
  async function sendVoice(audio: Blob, seconds: number) {
    const id = newId();
    const url = URL.createObjectURL(audio);
    voiceUrlsRef.current.push(url);
    dispatch({ type: 'voice_recorded', id, voice: { url, seconds, peaks: await waveformPeaks(audio) } });
    let transcript: string;
    try {
      transcript = (await transcribe(config.apiBase, audio)).trim().slice(0, MAX_LENGTH);
    } catch (e) {
      dispatch({ type: 'voice_failed', id });
      throw e;
    }
    if (!transcript) {
      dispatch({ type: 'voice_failed', id });
      throw new ChatApiError('validation', 'no speech');
    }
    dispatch({ type: 'voice_transcribed', id, text: transcript });
    await send(transcript, id, false);
  }

  async function send(text: string, retryId?: string, newBubble = !retryId, attachment?: SentAttachment) {
    const id = retryId ?? newId();
    // BRD: messages follow the detected language (the language button still switches it by hand)
    const detected = detectLanguage(text);
    if (detected && detected !== lang) setLang(detected);
    dispatch({ type: 'send_start', id, text, retry: !newBubble, attachment });
    try {
      const response = await sendMessage(config.apiBase, {
        sessionId: state.sessionId,
        channel: config.channel,
        text,
        attachmentId: attachment?.id,
      });
      dispatch({ type: 'send_success', userMessageId: id, botMessageId: newId(), response });
    } catch (e) {
      dispatch({ type: 'request_error', kind: errorKind(e), failed: { type: 'message', text, messageId: id, attachment } });
    }
  }

  async function talkToAgent(reason: HandoverReason) {
    dispatch({ type: 'handover_start' });
    try {
      const response = await requestHandover(config.apiBase, { sessionId: state.sessionId, channel: config.channel, reason });
      dispatch({ type: 'handover_success', response });
    } catch (e) {
      dispatch({ type: 'request_error', kind: errorKind(e), failed: { type: 'handover', reason } });
    }
  }

  // "End chat" with an agent asks first (on the page: the viewer frame has no confirm()); "Cancel request" does not
  const [confirmingEnd, setConfirmingEnd] = useState(false);

  async function endChat() {
    setConfirmingEnd(false);
    if (!state.sessionId) return;
    const sessionId = state.sessionId;
    dispatch({ type: 'handover_start' });
    try {
      await endHandover(config.apiBase, sessionId);
      // the live stream also brings this; fetching it here keeps the widget right if the stream is down
      dispatch({ type: 'updates_received', updates: await getUpdates(config.apiBase, sessionId, cursorRef.current) });
      dispatch({ type: 'request_done' });
    } catch (e) {
      dispatch({ type: 'request_error', kind: errorKind(e), failed: { type: 'end' } });
    }
  }

  function retry() {
    const failed = state.failed;
    if (failed?.type === 'message') void send(failed.text, failed.messageId, false, failed.attachment);
    if (failed?.type === 'handover') void talkToAgent(failed.reason);
    if (failed?.type === 'end') void endChat();
  }

  function close() {
    // the loader checks our origin before acting on this
    window.parent.postMessage({ type: 'kb-chat:close' }, '*');
  }

  const busy = state.status === 'sending';

  return (
    <div className={`kb-app ${config.embedded ? 'kb-app--embedded' : 'kb-app--standalone'}`}>
      <ChatHeader
        t={t}
        mode={state.mode}
        agentName={currentAgentName(state)}
        embedded={config.embedded}
        busy={busy}
        onToggleLanguage={() => setLang(lang === 'km' ? 'en' : 'km')}
        onNewChat={() => {
          discardDraft();
          // a request or live chat left open would stay waiting on the server and hold an agent's place
          if (state.mode !== 'bot' && state.sessionId) {
            endHandover(config.apiBase, state.sessionId).catch(() => undefined);
          }
          dispatch({ type: 'new_chat' });
        }}
        onTalkToAgent={() => void talkToAgent('USER_REQUEST')}
        onEndChat={() => (state.mode === 'agent' ? setConfirmingEnd(true) : void endChat())}
        onClose={close}
      />
      {confirmingEnd && state.mode === 'agent' && (
        <div className="kb-confirm" role="alertdialog" aria-modal="true" aria-labelledby="kb-confirm-title">
          <div className="kb-confirm__box">
            <div id="kb-confirm-title" className="kb-confirm__title">
              {currentAgentName(state) ? t.endChatConfirmNamed.replace('{name}', currentAgentName(state)!) : t.endChatConfirm}
            </div>
            <p className="kb-confirm__hint">{t.endChatConfirmHint}</p>
            <div className="kb-confirm__actions">
              <button type="button" className="kb-button" onClick={() => setConfirmingEnd(false)} autoFocus>
                {t.keepChatting}
              </button>
              <button type="button" className="kb-button kb-button--danger" onClick={() => void endChat()}>
                {t.endChatYes}
              </button>
            </div>
          </div>
        </div>
      )}
      {/* BRD: a handover state only; no queue number (it only counted waiting requests, not free agents) */}
      {state.mode === 'waiting' && <div className="kb-banner">{t.waitingBanner}</div>}
      <MessageList
        messages={state.messages}
        // plain dots while the bot answers; "<agent> is typing…" while the agent types
        typing={busy && state.mode === 'bot'}
        agentTyping={state.mode === 'agent' ? agentTyping : null}
        t={t}
        onTalkToAgent={state.mode === 'bot' && !busy ? () => void talkToAgent('BOT_SUGGESTED') : null}
        onRate={(message, score, comment) => void rate(message, score, comment)}
        onDismissRating={(message) => dispatch({ type: 'rating_status', id: message.id, status: 'dismissed' })}
        onSpeak={(message) => void speak(message)}
        speaking={speaking}
      />
      {state.status === 'error' && state.error && (
        <div className="kb-error">
          <Notice kind="error">
            {errorText(t, state.error)}
            {state.error !== 'validation' && (
              <button type="button" className="kb-button kb-button--link" onClick={retry}>
                {t.retry}
              </button>
            )}
          </Notice>
        </div>
      )}
      <Composer
        t={t}
        disabled={busy}
        onSend={sendTyped}
        onVoice={sendVoice}
        onTyping={notifyTyping}
        // AI chat: the bot only ever gets the masked text read from a file; a live agent gets the file itself
        onAttach={attach}
        readingFiles={state.mode === 'bot'}
        draft={draft}
        onDiscardDraft={discardDraft}
      />
    </div>
  );
}
