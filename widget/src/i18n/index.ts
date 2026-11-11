import type { Lang } from '../lib/config';
import { en, type Messages } from './en';
import { km } from './km';

export type { Messages };

export function messagesFor(lang: Lang): Messages {
  return lang === 'en' ? en : km;
}
