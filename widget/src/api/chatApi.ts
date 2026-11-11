import type { Channel } from '../lib/config';

export type SessionState = 'BOT_ACTIVE' | 'HUMAN_ACTIVE';
export type HandoverStatus = 'WAITING' | 'ACTIVE' | 'CLOSED';
export type HandoverReason = 'USER_REQUEST' | 'BOT_SUGGESTED';
export type SystemCode =
  | 'WAITING'
  | 'WAITING_REMINDER'
  | 'AGENT_JOINED' | 'AGENT_LEFT' | 'ATTACHMENT_REMOVED' | 'INACTIVITY_CLOSED';

export interface ChatRequest {
  sessionId?: string;
  channel: Channel;
  text: string;
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
 */
export type ChatErrorKind = 'validation' | 'conflict' | 'unavailable' | 'timeout' | 'network' | 'server';

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

export function submitRating(apiBase: string, sessionId: string, body: RatingRequest, timeoutMs = REQUEST_TIMEOUT_MS): Promise<RatingResponse> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/rating`;
  return request(url, { method: 'POST', body: JSON.stringify(body) }, timeoutMs);
}

/** Upload a file during a live agent chat (409 = no agent yet, 400 = wrong type / too big / virus). */
export async function uploadAttachment(apiBase: string, sessionId: string, file: File, timeoutMs = VOICE_TIMEOUT_MS): Promise<AttachmentResponse> {
  const form = new FormData();
  form.append('file', file, file.name);
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/attachments`;
  const response = await call(url, { method: 'POST', body: form }, timeoutMs);
  return (await response.json()) as AttachmentResponse;
}

/** Real-time stream (Server-Sent Events): "update" events (SessionUpdates) and "typing" events (TypingEvent). */
export function eventsUrl(apiBase: string, sessionId: string, after: number): string {
  return `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/events?after=${after}`;
}

/** Tells the agent the customer is typing. Best effort: failures are ignored. */
export async function sendTyping(apiBase: string, sessionId: string): Promise<void> {
  await call(`${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/typing`, { method: 'POST' }, 5_000);
}

export function getUpdates(apiBase: string, sessionId: string, after: number, timeoutMs = 10_000): Promise<SessionUpdates> {
  const url = `${apiBase}/api/v1/chat/sessions/${encodeURIComponent(sessionId)}/updates?after=${after}`;
  return request(url, { method: 'GET' }, timeoutMs);
}
