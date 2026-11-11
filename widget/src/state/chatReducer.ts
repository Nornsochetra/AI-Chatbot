import type {
  AttachmentResponse,
  ChatErrorKind,
  ChatResponse,
  HandoverReason,
  HandoverResponse,
  HandoverStatus,
  RatingType,
  SessionUpdates,
  SystemCode,
} from '../api/chatApi';

export type RatingStatus = 'open' | 'sending' | 'sent' | 'dismissed' | 'error';

export interface ChatMessage {
  id: string;
  role: 'user' | 'bot' | 'agent' | 'system' | 'rating';
  text: string;
  /** user message: the backend masked sensitive data in it */
  masked?: boolean;
  /** bot message: the backend suggests a live agent */
  handoverSuggested?: boolean;
  /** bot message: stored reply id, enables read-aloud */
  replyId?: string;
  /** agent message / agent joined or left: the agent's login username */
  senderName?: string;
  /** user message: a file the customer sent to the agent */
  attachment?: SentAttachment;
  /** system message: handover event, shown in the UI language */
  systemCode?: SystemCode;
  /** rating card: what is rated and how far the customer got */
  ratingType?: RatingType;
  ratingStatus?: RatingStatus;
}

export interface SentAttachment {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  /** local preview of the customer's own image; only valid while the page is open */
  previewUrl?: string;
  /** the agent removed it */
  removed?: boolean;
}

/** bot: the AI answers · waiting: in the agent queue · agent: a live agent is chatting */
export type ChatMode = 'bot' | 'waiting' | 'agent';

export type FailedAction =
  | { type: 'message'; text: string; messageId: string }
  | { type: 'handover'; reason: HandoverReason };

export interface ChatState {
  sessionId?: string;
  messages: ChatMessage[];
  mode: ChatMode;
  /** last handover message id seen, for polling */
  cursor: number;
  /** a rating card was already shown in this session (the AI prompt is shown at most once) */
  ratingAsked: boolean;
  /** place in the agent queue while waiting (1 = next) */
  queuePosition?: number;
  status: 'idle' | 'sending' | 'error';
  error?: ChatErrorKind;
  /** what to repeat on Retry */
  failed?: FailedAction;
}

export type ChatAction =
  | { type: 'send_start'; id: string; text: string; retry?: boolean }
  | { type: 'send_success'; userMessageId: string; botMessageId: string; response: ChatResponse }
  | { type: 'handover_start' }
  | { type: 'handover_success'; response: HandoverResponse }
  | { type: 'request_error'; kind: ChatErrorKind; failed: FailedAction }
  | { type: 'updates_received'; updates: SessionUpdates }
  | { type: 'attachment_sent'; id: string; response: AttachmentResponse; previewUrl?: string }
  | { type: 'ai_rating_prompt'; id: string }
  | { type: 'rating_status'; id: string; status: RatingStatus }
  | { type: 'new_chat' };

export const initialState: ChatState = { messages: [], mode: 'bot', cursor: 0, ratingAsked: false, status: 'idle' };

/** handover events after which the customer is asked to rate the agent (requirement: immediately) */
const AGENT_CHAT_ENDED: SystemCode[] = ['AGENT_LEFT', 'INACTIVITY_CLOSED'];

function modeFor(handoverStatus: HandoverStatus | null): ChatMode {
  if (handoverStatus === 'ACTIVE') return 'agent';
  if (handoverStatus === 'WAITING') return 'waiting';
  return 'bot';
}

function ratingCard(id: string, ratingType: RatingType): ChatMessage {
  return { id, role: 'rating', text: '', ratingType, ratingStatus: 'open' };
}

/** the agent currently chatting (from the latest agent message or joined event), for the header */
export function currentAgentName(state: ChatState): string | undefined {
  if (state.mode !== 'agent') return undefined;
  for (let i = state.messages.length - 1; i >= 0; i--) {
    const name = state.messages[i].senderName;
    if (name) return name;
  }
  return undefined;
}

/** the AI rating is offered once the bot has answered and the customer has gone quiet */
export function shouldOfferAiRating(state: ChatState): boolean {
  const last = state.messages[state.messages.length - 1];
  return state.mode === 'bot' && state.status === 'idle' && !state.ratingAsked && last?.role === 'bot';
}

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'send_start':
      return {
        ...state,
        // a retry reuses the user bubble that is already shown
        messages: action.retry ? state.messages : [...state.messages, { id: action.id, role: 'user', text: action.text }],
        status: 'sending',
        error: undefined,
        failed: undefined,
      };

    case 'send_success': {
      const { response } = action;
      const messages = state.messages.map((m) =>
        m.id === action.userMessageId ? { ...m, masked: response.inputMasked } : m,
      );
      if (response.reply) {
        messages.push({
          id: action.botMessageId,
          role: 'bot',
          text: response.reply,
          handoverSuggested: response.handoverSuggested,
          replyId: response.replyId ?? undefined,
        });
      }
      return { ...state, sessionId: response.sessionId, messages, status: 'idle' };
    }

    case 'handover_start':
      return { ...state, status: 'sending', error: undefined, failed: undefined };

    case 'handover_success':
      return {
        ...state,
        sessionId: action.response.sessionId,
        mode: modeFor(action.response.handoverStatus),
        queuePosition: action.response.queuePosition ?? undefined,
        status: 'idle',
      };

    case 'request_error':
      return { ...state, status: 'error', error: action.kind, failed: action.failed };

    case 'updates_received': {
      const { updates } = action;
      const added: ChatMessage[] = [];
      let ratingAsked = state.ratingAsked;
      // the push stream and the fallback poll can deliver the same message; the cursor drops repeats
      let messages = state.messages;
      for (const m of updates.messages.filter((message) => message.id > state.cursor)) {
        const senderName = m.senderName ?? undefined;
        // the agent removed a file: mark the customer's own bubble
        if (m.sender === 'SYSTEM' && m.text === 'ATTACHMENT_REMOVED' && m.attachment) {
          const removedId = m.attachment.id;
          messages = messages.map((msg) =>
            msg.attachment?.id === removedId ? { ...msg, attachment: { ...msg.attachment, removed: true, previewUrl: undefined } } : msg,
          );
        }
        // the customer's own messages are already on screen
        if (m.sender === 'AGENT') added.push({ id: `live-${m.id}`, role: 'agent', text: m.text, senderName });
        if (m.sender === 'SYSTEM') {
          const code = m.text as SystemCode;
          added.push({ id: `live-${m.id}`, role: 'system', text: m.text, systemCode: code, senderName });
          if (AGENT_CHAT_ENDED.includes(code)) {
            added.push(ratingCard(`rating-${m.id}`, 'AGENT'));
            ratingAsked = true;
          }
        }
      }
      return {
        ...state,
        messages: added.length ? [...messages, ...added] : messages,
        cursor: Math.max(state.cursor, updates.cursor),
        mode: updates.state === 'BOT_ACTIVE' ? 'bot' : modeFor(updates.handoverStatus),
        queuePosition: updates.queuePosition ?? undefined,
        ratingAsked,
      };
    }

    case 'attachment_sent': {
      const { response } = action;
      const message: ChatMessage = {
        id: action.id,
        role: 'user',
        text: response.fileName,
        attachment: {
          id: response.id,
          fileName: response.fileName,
          contentType: response.contentType,
          sizeBytes: response.sizeBytes,
          previewUrl: action.previewUrl,
        },
      };
      return { ...state, messages: [...state.messages, message] };
    }

    case 'ai_rating_prompt':
      if (!shouldOfferAiRating(state)) return state;
      return { ...state, messages: [...state.messages, ratingCard(action.id, 'AI')], ratingAsked: true };

    case 'rating_status':
      return {
        ...state,
        messages: state.messages.map((m) => (m.id === action.id ? { ...m, ratingStatus: action.status } : m)),
      };

    case 'new_chat':
      return initialState;
  }
}

/** sessionStorage can be blocked (private mode, webviews); the chat must still work without it. */
export function loadState(key: string): ChatState {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return initialState;
    const saved = JSON.parse(raw) as Partial<ChatState>;
    // never restore a half-finished request
    const messages = (saved.messages ?? []).map((m) => {
      if (m.role === 'rating' && m.ratingStatus === 'sending') return { ...m, ratingStatus: 'open' as const };
      // a preview is a page-local object URL: it is gone after a reload
      if (m.attachment?.previewUrl) return { ...m, attachment: { ...m.attachment, previewUrl: undefined } };
      return m;
    });
    return {
      ...initialState,
      sessionId: saved.sessionId,
      messages,
      mode: saved.mode ?? 'bot',
      cursor: saved.cursor ?? 0,
      ratingAsked: saved.ratingAsked ?? false,
    };
  } catch {
    return initialState;
  }
}

export function saveState(key: string, state: ChatState): void {
  try {
    const { sessionId, messages, mode, cursor, ratingAsked } = state;
    sessionStorage.setItem(key, JSON.stringify({ sessionId, messages, mode, cursor, ratingAsked }));
  } catch {
    // ignore: storage unavailable
  }
}
