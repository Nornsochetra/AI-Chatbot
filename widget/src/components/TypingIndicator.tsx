import type { Messages } from '../i18n';

interface Props {
  t: Messages;
  /** visible text next to the dots, e.g. "sokha is typing…"; without it only the dots show (the bot) */
  label?: string;
}

export function TypingIndicator({ t, label }: Props) {
  return (
    <div className="kb-row kb-row--bot" aria-live="polite">
      <div className="kb-bubble kb-bubble--bot kb-typing">
        <span className="kb-dot" />
        <span className="kb-dot" />
        <span className="kb-dot" />
        {label ? <span className="kb-typing__label">{label}</span> : <span className="kb-visually-hidden">{t.typing}</span>}
      </div>
    </div>
  );
}
