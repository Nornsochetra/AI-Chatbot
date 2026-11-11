import type { Messages } from '../i18n';

interface Props {
  t: Messages;
  onTalkToAgent: () => void;
}

export function HandoverPrompt({ t, onTalkToAgent }: Props) {
  return (
    <div className="kb-handover">
      <span>{t.handoverPrompt}</span>
      <button type="button" className="kb-button kb-button--primary" onClick={onTalkToAgent}>
        {t.talkToAgent}
      </button>
    </div>
  );
}
