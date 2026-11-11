export type Channel = 'WEBSITE' | 'MOBILE_APP';
export type Lang = 'km' | 'en';

export interface WidgetConfig {
  channel: Channel;
  lang: Lang;
  apiBase: string;
  /** true when shown inside the website iframe, false in the mobile webview */
  embedded: boolean;
  /** Requirement: AI-only chats get the rating request after this many seconds of quiet after a bot answer */
  aiRatingDelaySeconds: number;
}

export const DEFAULT_API_BASE = process.env.NEXT_PUBLIC_API_BASE || 'http://localhost:8080';

const DEFAULT_AI_RATING_DELAY_SECONDS = 60;

function aiRatingDelay(): number {
  const value = Number(process.env.NEXT_PUBLIC_AI_RATING_DELAY_SECONDS);
  return Number.isFinite(value) && value > 0 ? value : DEFAULT_AI_RATING_DELAY_SECONDS;
}

export function readConfig(search: string = window.location.search): WidgetConfig {
  const params = new URLSearchParams(search);
  const channel: Channel = params.get('channel') === 'MOBILE_APP' ? 'MOBILE_APP' : 'WEBSITE';
  const lang: Lang = params.get('lang') === 'en' ? 'en' : 'km';
  const apiBase = (params.get('api') || DEFAULT_API_BASE).replace(/\/+$/, '');
  return { channel, lang, apiBase, embedded: window.parent !== window, aiRatingDelaySeconds: aiRatingDelay() };
}
