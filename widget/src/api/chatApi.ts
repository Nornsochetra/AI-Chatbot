import type { Channel } from '../lib/config';

export type SessionState = 'BOT_ACTIVE' | 'HUMAN_ACTIVE';
export type HandoverStatus = 'WAITING' | 'ACTIVE' | 'CLOSED';
export type HandoverReason = 'USER_REQUEST' | 'BOT_SUGGESTED';
export type SystemCode =
  | 'WAITING'
  | 'WAITING_REMINDER'
  | 'AGENT_JOINED' | 'AGENT_LEFT' | 'ATTACHMENT_REMOVED' | 'INACTIVITY_CLOSED'
  | 'CUSTOMER_ENDED' | 'REQUEST_CANCELLED'
  /** widget only: the goodbye line after a live chat ends (BRD message customization) */
  | 'GOODBYE';

export interface ChatRequest {
  sessionId?: string;
  channel: Channel;
  /** may be empty when a file is sent */
  text: string;
  /** a file sent to the AI chat first (sendFile); the bot gets its masked text, never the file */
  attachmentId?: string;
}

export interface ChatResponse {
  sessionId: string;
  /** null while a live agent is active */
  reply: string | null;
  /** id of the stored bot reply (for read-aloud); null when there is no bot reply */
  replyId: string | null;
  state: SessionState;
  handoverSuggested: boolean;
  inputMasked: boolean;
  createdAt: string;
  /** WAITING when this message opened a live agent request by itself (BRD auto-route) */
  handoverStatus?: HandoverStatus | null;
}

export interface HandoverRequest {
  sessionId?: string;
  channel: Channel;
  reason: HandoverReason;
}

export interface HandoverResponse {
  sessionId: string;
  state: SessionState;
  handoverStatus: HandoverStatus;
  /** 1 = next in line; null unless waiting for an agent */
  queuePosition?: number | null;
}

export interface LiveMessage {
  id: number;
  sender: 'CUSTOMER' | 'AGENT' | 'SYSTEM';
  /** for SYSTEM: a SystemCode */
  text: string;
  /** the agent's login username on agent messages and agent joined / left events */
  senderName: string | null;
  /** set for a customer upload and for the "file removed" event */
  attachment?: AttachmentInfo | null;
  createdAt: string;
}

export interface AttachmentInfo {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  removed: boolean;
}

/** Same limits as the backend (docs/attachments-simple.md). */
export const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
export const ALLOWED_ATTACHMENT_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

export interface AttachmentResponse {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

/** what came of reading the text in a file sent to the AI chat (SKIPPED: reading is switched off) */
export type ReadStatus = 'READ' | 'NO_TEXT' | 'FAILED' | 'SKIPPED';

export interface FileUploadResponse {
  sessionId: string;
  attachment: AttachmentResponse;
  /** true: AI chat, the customer still presses Send; false: a live chat is open and the file went to the agent */
  draft: boolean;
  readStatus: ReadStatus | null;
  /** AI chat: the masked text the bot will read, shown to the customer before sending */
  text: string | null;
  textMasked: boolean;
}

/** pushed when the other side is typing; hide it after a few seconds without a new one */
export interface TypingEvent {
  who: 'AGENT' | 'CUSTOMER';
  name: string | null;
}

export interface SessionUpdates {
  state: SessionState;
  handoverStatus: HandoverStatus | null;
  /** 1 = next in line; null unless waiting for an agent */
  queuePosition?: number | null;
  messages: LiveMessage[];
  cursor: number;
}

export type RatingType = 'AI' | 'AGENT';

export interface RatingRequest {
  type: RatingType;
  score: number;
  comment?: string;
}

export interface RatingResponse {
  id: string;
  type: RatingType;
  score: number;
  comment: string | null;
  createdAt: string;
}

/**
 * conflict: the request is valid but not allowed now (e.g. already rated)
 * unavailable: an external service is off or down (e.g. voice without a Gemini key)
 * limit: a per-chat limit was reached (files sent to the AI chat)
 */
export type ChatErrorKind = 'validation' | 'conflict' | 'unavailable' | 'limit' | 'timeout' | 'network' | 'server';

export class ChatApiError extends Error {
  constructor(public readonly kind: ChatErrorKind, message: string) {
    super(message);
    this.name = 'ChatApiError';
  }
}

export const REQUEST_TIMEOUT_MS = 30_000;

async function request<T>(url: string, init: RequestInit, timeoutMs: number): Promise<T> {
  const response = await call(url, { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } }, timeoutMs);
  return (await response.json()) as T;
}

/** fetch with a timeout, mapping failures to ChatApiError kinds. */
async function call(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    const name = (e as Error).name;
    if (name === 'TimeoutError' || name === 'AbortError') {
      throw new ChatApiError('timeout', 'The reply took too long');
    }
    throw new ChatApiError('network', 'Could not reach the chat service');
  }

  if (response.status === 400 || response.status === 413) {
    throw new ChatApiError('validation', 'Invalid request');
  }
  if (response.status === 409) {
    throw new ChatApiError('conflict', 'Not allowed in the current state');
  }
  if (response.status === 429) {
    throw new ChatApiError('limit', 'Limit reached');
  }
  if (response.status === 503) {
    throw new ChatApiError('unavailable', 'Service temporarily unavailable');
  }
  if (!response.ok) {
    throw new ChatApiError('server', `Chat service error ${response.status}`);
  }
  return response;
}

/** Voice calls can try several Gemini models in a row on the backend, so they get more time. */
export const VOICE_TIMEOUT_MS = 90_000;

/** Speech-to-text: the recording goes to our backend (never straight to Gemini). */
export async function transcribe(apiBase: string, audio: Blob, timeoutMs = VOICE_TIMEOUT_MS): Promise<string> {
  const form = new FormData();
  form.append('audio', audio, 'recording');
  // no Content-Type header: the browser sets the multipart boundary
  const response = await call(`${apiBase}/api/v1/chat/speech/transcriptions`, { method: 'POST', body: form }, timeoutMs);
  return ((await response.json()) as { text: string }).text;
}

/** Read-aloud of a stored bot reply; returns WAV audio. */
export async function readAloud(apiBase: string, sessionId: string, replyId: string, timeoutMs = VOICE_TIMEOUT_MS): Promise<Blob> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/replies/${encodeURIComponent(replyId)}/speech`;
  const response = await call(url, { method: 'POST' }, timeoutMs);
  return response.blob();
}

export function sendMessage(apiBase: string, body: ChatRequest, timeoutMs = REQUEST_TIMEOUT_MS): Promise<ChatResponse> {
  return request(`${apiBase}/api/v1/chat/messages`, { method: 'POST', body: JSON.stringify(body) }, timeoutMs);
}

export function requestHandover(apiBase: string, body: HandoverRequest, timeoutMs = REQUEST_TIMEOUT_MS): Promise<HandoverResponse> {
  return request(`${apiBase}/api/v1/chat/handover`, { method: 'POST', body: JSON.stringify(body) }, timeoutMs);
}

/** The customer ends the live chat, or cancels the request while waiting; the bot answers again. */
export async function endHandover(apiBase: string, sessionId: string, timeoutMs = REQUEST_TIMEOUT_MS): Promise<void> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/handover/close`;
  await call(url, { method: 'POST' }, timeoutMs);
}

export function submitRating(apiBase: string, sessionId: string, body: RatingRequest, timeoutMs = REQUEST_TIMEOUT_MS): Promise<RatingResponse> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/rating`;
  return request(url, { method: 'POST', body: JSON.stringify(body) }, timeoutMs);
}

/**
 * A file from the 📎, in any mode (docs/attachments-ai-chat.md). AI chat: returns a draft with the text the bot
 * will read (nothing reaches the bot until the next message); live agent chat: the file goes to the agent.
 * 400 = wrong type / too big / virus, 429 = per-chat limit.
 */
export async function sendFile(
  apiBase: string,
  body: { sessionId?: string; channel: Channel; file: File },
  timeoutMs = VOICE_TIMEOUT_MS,
): Promise<FileUploadResponse> {
  const form = new FormData();
  form.append('file', body.file, body.file.name);
  form.append('channel', body.channel);
  if (body.sessionId) form.append('sessionId', body.sessionId);
  const response = await call(`${apiBase}/api/v1/chat/attachments`, { method: 'POST', body: form }, timeoutMs);
  return (await response.json()) as FileUploadResponse;
}

/** Removes an AI chat file the customer has not sent (the backend also erases unsent files after an hour). */
export async function discardFile(apiBase: string, sessionId: string, attachmentId: string, timeoutMs = REQUEST_TIMEOUT_MS): Promise<void> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/attachments/${encodeURIComponent(attachmentId)}`;
  await call(url, { method: 'DELETE' }, timeoutMs);
}

/** Real-time stream (Server-Sent Events): "update" events (SessionUpdates) and "typing" events (TypingEvent). */
export function eventsUrl(apiBase: string, sessionId: string, after: number): string {
  return `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/events?after=${after}`;
}

/** Tells the agent the customer is typing. Best effort: failures are ignored. */
export async function sendTyping(apiBase: string, sessionId: string): Promise<void> {
  await call(`${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/typing`, { method: 'POST' }, 5_000);
}

/** Backend setup the widget adapts to. */
export interface ChatConfig {
  agentInbox: 'CONSOLE' | 'BOTPRESS_DESK';
  /** Botpress Desk: an agent can join any chat, so the live stream stays open while the bot is answering too */
  agentMayJoinAnytime: boolean;
}

export function getChatConfig(apiBase: string, timeoutMs = 10_000): Promise<ChatConfig> {
  return request(`${apiBase}/api/v1/chat/config`, { method: 'GET' }, timeoutMs);
}

export function getUpdates(apiBase: string, sessionId: string, after: number, timeoutMs = 10_000): Promise<SessionUpdates> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/updates?after=${after}`;
  return request(url, { method: 'GET' }, timeoutMs);
}
