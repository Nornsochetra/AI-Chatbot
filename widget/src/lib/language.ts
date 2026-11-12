import type { Lang } from './config';

/**
 * BRD: messages follow the detected language. Khmer script means Khmer; Latin letters without Khmer mean English.
 * Mixed messages with any Khmer count as Khmer. Digits or emoji alone tell nothing (null).
 */
export function detectLanguage(text: string): Lang | null {
  if (/[ក-៿]/.test(text)) return 'km';
  if (/[A-Za-z]{2,}/.test(text)) return 'en';
  return null;
}
