import { describe, expect, it, vi } from 'vitest';

import type { ChatResponse, SessionUpdates } from '../api/chatApi';
import {
  chatReducer,
  currentAgentName,
  initialState,
  loadState,
  saveState,
  shouldOfferAiRating,
  type ChatState,
} from './chatReducer';

const response = (overrides: Partial<ChatResponse> = {}): ChatResponse => ({
  sessionId: 's1',
  reply: 'Hello',
  replyId: 'reply-1',
  state: 'BOT_ACTIVE',
  handoverSuggested: false,
  inputMasked: false,
  createdAt: '2026-11-10T00:00:00Z',
  ...overrides,
});

const updates = (overrides: Partial<SessionUpdates> = {}): SessionUpdates => ({
  state: 'HUMAN_ACTIVE',
  handoverStatus: 'ACTIVE',
  messages: [],
  cursor: 0,
  ...overrides,
});

const sent = (text = 'hi') => chatReducer(initialState, { type: 'send_start', id: 'u1', text });

describe('chatReducer: bot chat', () => {
  it('adds the user message and enters sending on send_start', () => {
    const s = sent();
    expect(s.status).toBe('sending');
    expect(s.messages).toEqual([{ id: 'u1', role: 'user', text: 'hi' }]);
  });

  it('adds the bot reply and stores the sessionId on success', () => {
    const s = chatReducer(sent(), { type: 'send_success', userMessageId: 'u1', botMessageId: 'b1', response: response() });
    expect(s.status).toBe('idle');
    expect(s.sessionId).toBe('s1');
    expect(s.messages[1]).toMatchObject({ id: 'b1', role: 'bot', text: 'Hello', handoverSuggested: false, replyId: 'reply-1' });
  });

  it('flags the user message when the backend masked it', () => {
    const s = chatReducer(sent('my OTP is 123456'), {
      type: 'send_success', userMessageId: 'u1', botMessageId: 'b1', response: response({ inputMasked: true }),
    });
    expect(s.messages[0].masked).toBe(true);
  });

  it('keeps the handover suggestion on the bot message', () => {
    const s = chatReducer(sent(), {
      type: 'send_success', userMessageId: 'u1', botMessageId: 'b1', response: response({ handoverSuggested: true }),
    });
    expect(s.messages[1].handoverSuggested).toBe(true);
  });

  it('remembers a failed message and reuses its bubble on retry', () => {
    const failed = chatReducer(sent(), {
      type: 'request_error', kind: 'network', failed: { type: 'message', text: 'hi', messageId: 'u1' },
    });
    expect(failed).toMatchObject({ status: 'error', error: 'network', failed: { type: 'message', messageId: 'u1' } });

    const retrying = chatReducer(failed, { type: 'send_start', id: 'u1', text: 'hi', retry: true });
    expect(retrying.messages).toHaveLength(1);
    expect(retrying.status).toBe('sending');
    expect(retrying.failed).toBeUndefined();
  });

  it('clears everything on new_chat', () => {
    const state: ChatState = { ...initialState, sessionId: 's1', messages: [{ id: 'u1', role: 'user', text: 'hi' }] };
    expect(chatReducer(state, { type: 'new_chat' })).toEqual(initialState);
  });
});

describe('chatReducer: AI rating', () => {
  const answered = (): ChatState =>
    chatReducer(sent(), { type: 'send_success', userMessageId: 'u1', botMessageId: 'b1', response: response() });

  it('is offered once the bot has answered', () => {
    expect(shouldOfferAiRating(answered())).toBe(true);
    const s = chatReducer(answered(), { type: 'ai_rating_prompt', id: 'r1' });
    expect(s.messages[2]).toMatchObject({ id: 'r1', role: 'rating', ratingType: 'AI', ratingStatus: 'open' });
    expect(s.ratingAsked).toBe(true);
  });

  it('is offered only once per session', () => {
    const asked = chatReducer(answered(), { type: 'ai_rating_prompt', id: 'r1' });
    expect(shouldOfferAiRating(asked)).toBe(false);
    expect(chatReducer(asked, { type: 'ai_rating_prompt', id: 'r2' })).toBe(asked);
  });

  it('is not offered before the bot answered, while sending, or with an agent', () => {
    expect(shouldOfferAiRating(initialState)).toBe(false);
    expect(shouldOfferAiRating(sent())).toBe(false);
    expect(shouldOfferAiRating({ ...answered(), mode: 'agent' })).toBe(false);
  });

  it('tracks the card through sending to sent or dismissed', () => {
    const asked = chatReducer(answered(), { type: 'ai_rating_prompt', id: 'r1' });
    const sending = chatReducer(asked, { type: 'rating_status', id: 'r1', status: 'sending' });
    expect(sending.messages[2].ratingStatus).toBe('sending');
    expect(chatReducer(sending, { type: 'rating_status', id: 'r1', status: 'sent' }).messages[2].ratingStatus).toBe('sent');
    expect(chatReducer(asked, { type: 'rating_status', id: 'r1', status: 'dismissed' }).messages[2].ratingStatus).toBe('dismissed');
  });
});

describe('chatReducer: voice messages', () => {
  const voice = { url: 'blob:v', seconds: 4.2, peaks: [0.2, 1, 0.5] };
  const recorded = () => chatReducer(initialState, { type: 'voice_recorded', id: 'v1', voice });

  it('shows the voice bubble at once, transcript still pending', () => {
    expect(recorded().messages[0]).toMatchObject({ role: 'user', text: '', voice: { ...voice, transcribing: true } });
  });

  it('fills in the transcript, then sends it through the same bubble', () => {
    const transcribed = chatReducer(recorded(), { type: 'voice_transcribed', id: 'v1', text: 'branch hours?' });
    expect(transcribed.messages[0]).toMatchObject({ text: 'branch hours?', voice: { transcribing: false } });

    const sending = chatReducer(transcribed, { type: 'send_start', id: 'v1', text: 'branch hours?', retry: true });
    const s = chatReducer(sending, { type: 'send_success', userMessageId: 'v1', botMessageId: 'b1', response: response() });
    expect(s.messages).toHaveLength(2);
    expect(s.messages[0]).toMatchObject({ role: 'user', voice: { url: 'blob:v' } });
    expect(s.messages[1]).toMatchObject({ role: 'bot', text: 'Hello' });
  });

  it('removes the bubble when nothing was understood', () => {
    expect(chatReducer(recorded(), { type: 'voice_failed', id: 'v1' }).messages).toHaveLength(0);
  });

  it('keeps transcript and waveform after a reload, but not the recording or an unsent one', () => {
    const store = new Map<string, string>();
    vi.stubGlobal('sessionStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) });
    try {
      const done = chatReducer(chatReducer(recorded(), { type: 'voice_transcribed', id: 'v1', text: 'hi' }), {
        type: 'voice_recorded',
        id: 'v2',
        voice,
      });
      saveState('k', done);
      const restored = loadState('k');
      expect(restored.messages).toHaveLength(1);
      expect(restored.messages[0]).toMatchObject({ text: 'hi', voice: { url: undefined, seconds: 4.2, peaks: voice.peaks } });
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('chatReducer: attachments', () => {
  const upload = { id: 'att-1', fileName: 'card.jpg', contentType: 'image/jpeg', sizeBytes: 2048, createdAt: '' };

  it('shows the sent file as the customer message', () => {
    const withAgent: ChatState = { ...initialState, sessionId: 's1', mode: 'agent' };
    const s = chatReducer(withAgent, { type: 'attachment_sent', id: 'm1', response: upload, previewUrl: 'blob:x' });
    expect(s.messages[0]).toMatchObject({
      role: 'user',
      text: 'card.jpg',
      attachment: { id: 'att-1', fileName: 'card.jpg', sizeBytes: 2048, previewUrl: 'blob:x' },
    });
  });

  it('marks the file as removed when the agent deletes it', () => {
    const withAgent: ChatState = { ...initialState, sessionId: 's1', mode: 'agent' };
    const sent = chatReducer(withAgent, { type: 'attachment_sent', id: 'm1', response: upload, previewUrl: 'blob:x' });
    const s = chatReducer(sent, {
      type: 'updates_received',
      updates: updates({
        cursor: 5,
        messages: [{
          id: 5, sender: 'SYSTEM', text: 'ATTACHMENT_REMOVED', senderName: 'sokha', createdAt: '',
          attachment: { id: 'att-1', fileName: 'card.jpg', contentType: 'image/jpeg', sizeBytes: 2048, removed: true },
        }],
      }),
    });
    expect(s.messages[0].attachment).toMatchObject({ removed: true, previewUrl: undefined });
    expect(s.messages[1]).toMatchObject({ role: 'system', systemCode: 'ATTACHMENT_REMOVED', senderName: 'sokha' });
  });
});

describe('chatReducer: live agent', () => {
  it('enters waiting mode when the handover is accepted by the backend', () => {
    const s = chatReducer(initialState, {
      type: 'handover_success', response: { sessionId: 's9', state: 'HUMAN_ACTIVE', handoverStatus: 'WAITING' },
    });
    expect(s).toMatchObject({ mode: 'waiting', sessionId: 's9', status: 'idle' });
  });

  it('keeps the queue position while waiting and drops it once an agent accepts', () => {
    let s = chatReducer(initialState, {
      type: 'handover_success',
      response: { sessionId: 's9', state: 'HUMAN_ACTIVE', handoverStatus: 'WAITING', queuePosition: 3 },
    });
    expect(s.queuePosition).toBe(3);

    s = chatReducer(s, { type: 'updates_received', updates: updates({ handoverStatus: 'WAITING', queuePosition: 1 }) });
    expect(s.queuePosition).toBe(1);

    s = chatReducer(s, { type: 'updates_received', updates: updates({ handoverStatus: 'ACTIVE', queuePosition: null }) });
    expect(s).toMatchObject({ mode: 'agent', queuePosition: undefined });
  });

  it('shows the waiting reminder without an agent name', () => {
    const waiting: ChatState = { ...initialState, sessionId: 's1', mode: 'waiting' };
    const s = chatReducer(waiting, {
      type: 'updates_received',
      updates: updates({
        handoverStatus: 'WAITING',
        cursor: 4,
        messages: [{ id: 4, sender: 'SYSTEM', text: 'WAITING_REMINDER', senderName: null, createdAt: '' }],
      }),
    });
    expect(s.messages).toEqual([
      { id: 'live-4', role: 'system', text: 'WAITING_REMINDER', systemCode: 'WAITING_REMINDER', senderName: undefined },
    ]);
  });

  it('adds agent and system messages, skips the customer echo, advances the cursor', () => {
    const waiting: ChatState = { ...initialState, sessionId: 's1', mode: 'waiting' };
    const s = chatReducer(waiting, {
      type: 'updates_received',
      updates: updates({
        cursor: 7,
        messages: [
          { id: 5, sender: 'SYSTEM', text: 'AGENT_JOINED', senderName: null, createdAt: '' },
          { id: 6, sender: 'CUSTOMER', text: 'hi', senderName: null, createdAt: '' },
          { id: 7, sender: 'AGENT', text: 'Hello, how can I help?', senderName: null, createdAt: '' },
        ],
      }),
    });
    expect(s.mode).toBe('agent');
    expect(s.cursor).toBe(7);
    expect(s.messages).toEqual([
      { id: 'live-5', role: 'system', text: 'AGENT_JOINED', systemCode: 'AGENT_JOINED' },
      { id: 'live-7', role: 'agent', text: 'Hello, how can I help?' },
    ]);
  });

  it('returns to the bot when the handover closes', () => {
    const withAgent: ChatState = { ...initialState, sessionId: 's1', mode: 'agent', cursor: 7 };
    const s = chatReducer(withAgent, {
      type: 'updates_received',
      updates: updates({
        state: 'BOT_ACTIVE', handoverStatus: null, cursor: 8,
        messages: [{ id: 8, sender: 'SYSTEM', text: 'INACTIVITY_CLOSED', senderName: null, createdAt: '' }],
      }),
    });
    expect(s.mode).toBe('bot');
    expect(s.messages[0].systemCode).toBe('INACTIVITY_CLOSED');
  });

  it('drops messages it already has (stream and fallback poll can overlap)', () => {
    const withAgent: ChatState = { ...initialState, sessionId: 's1', mode: 'agent', cursor: 7 };
    const s = chatReducer(withAgent, {
      type: 'updates_received',
      updates: updates({
        cursor: 8,
        messages: [
          { id: 7, sender: 'AGENT', text: 'old', senderName: 'sokha', createdAt: '' },
          { id: 8, sender: 'AGENT', text: 'new', senderName: 'sokha', createdAt: '' },
        ],
      }),
    });
    expect(s.messages.map((m) => m.text)).toEqual(['new']);
  });

  it('keeps the agent login name on agent messages and joined events, for the header too', () => {
    const waiting: ChatState = { ...initialState, sessionId: 's1', mode: 'waiting' };
    const s = chatReducer(waiting, {
      type: 'updates_received',
      updates: updates({
        cursor: 2,
        messages: [
          { id: 1, sender: 'SYSTEM', text: 'AGENT_JOINED', senderName: 'sokha', createdAt: '' },
          { id: 2, sender: 'AGENT', text: 'Hi', senderName: 'sokha', createdAt: '' },
        ],
      }),
    });
    expect(s.messages.map((m) => m.senderName)).toEqual(['sokha', 'sokha']);
    expect(currentAgentName(s)).toBe('sokha');
    expect(currentAgentName({ ...s, mode: 'bot' })).toBeUndefined();
  });

  it('never moves the cursor backwards', () => {
    const s = chatReducer({ ...initialState, mode: 'agent', cursor: 10 }, {
      type: 'updates_received', updates: updates({ cursor: 3 }),
    });
    expect(s.cursor).toBe(10);
  });

  it('asks for an agent rating right after the agent closes the chat', () => {
    const withAgent: ChatState = { ...initialState, sessionId: 's1', mode: 'agent' };
    const s = chatReducer(withAgent, {
      type: 'updates_received',
      updates: updates({
        state: 'BOT_ACTIVE', handoverStatus: null, cursor: 9,
        messages: [{ id: 9, sender: 'SYSTEM', text: 'AGENT_LEFT', senderName: null, createdAt: '' }],
      }),
    });
    expect(s.messages.map((m) => m.role)).toEqual(['system', 'rating']);
    expect(s.messages[1]).toMatchObject({ ratingType: 'AGENT', ratingStatus: 'open' });
    expect(s.ratingAsked).toBe(true);
  });

  it('adds no bot bubble when the reply is null (message went to the agent)', () => {
    const s = chatReducer({ ...sent(), mode: 'agent' }, {
      type: 'send_success', userMessageId: 'u1', botMessageId: 'b1', response: response({ reply: null, state: 'HUMAN_ACTIVE' }),
    });
    expect(s.messages).toHaveLength(1);
    expect(s.mode).toBe('agent');
  });
});
