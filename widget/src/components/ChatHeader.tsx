import type { Messages } from '../i18n';
import type { ChatMode } from '../state/chatReducer';

interface Props {
  t: Messages;
  mode: ChatMode;
  /** the agent chatting (login username), shown in the subtitle */
  agentName?: string;
  embedded: boolean;
  busy: boolean;
  onToggleLanguage: () => void;
  onNewChat: () => void;
  onTalkToAgent: () => void;
  /** waiting: cancel the request at once; with an agent: the parent asks for confirmation first */
  onEndChat: () => void;
  onClose: () => void;
}

export function ChatHeader({ t, mode, agentName, embedded, busy, onToggleLanguage, onNewChat, onTalkToAgent, onEndChat, onClose }: Props) {
  const withAgent = mode === 'agent';
  return (
    <header className={`kb-header ${withAgent ? 'kb-header--agent' : ''}`}>
      <div className="kb-header__top">
        <div className="kb-header__avatar" aria-hidden="true">
          {withAgent ? (
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-3.3 0-8 1.7-8 5v1h16v-1c0-3.3-4.7-5-8-5z" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M12 3C6.5 3 2 6.8 2 11.5c0 2.4 1.2 4.6 3.1 6.1L4 21l4.1-2c1.2.4 2.5.5 3.9.5 5.5 0 10-3.8 10-8.5S17.5 3 12 3z" />
            </svg>
          )}
        </div>
        <div className="kb-header__titles">
          <div className="kb-header__title">{withAgent ? t.agentTitle : t.title}</div>
          <div className="kb-header__subtitle">
            {withAgent ? (agentName ? t.agentDisclosureNamed.replace('{name}', agentName) : t.agentDisclosure) : t.disclosure}
          </div>
        </div>
        {embedded && (
          <button type="button" className="kb-icon-button" onClick={onClose} aria-label={t.close} title={t.close}>
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        )}
      </div>
      <div className="kb-header__actions">
        {/* one button in the same place, changing with the state: ask for an agent → cancel → end */}
        {mode === 'bot' && (
          <button type="button" className="kb-chip" onClick={onTalkToAgent} disabled={busy}>
            {t.talkToAgent}
          </button>
        )}
        {mode !== 'bot' && (
          <button type="button" className="kb-chip kb-chip--end" onClick={onEndChat} disabled={busy}>
            {mode === 'waiting' ? t.cancelRequest : t.endChat}
          </button>
        )}
        {/* a new chat would leave the open handover behind, so only offer it with the bot */}
        {mode === 'bot' && (
          <button type="button" className="kb-chip" onClick={onNewChat} disabled={busy}>
            {t.newChat}
          </button>
        )}
        <button type="button" className="kb-chip" onClick={onToggleLanguage}>
          {t.switchLanguage}
        </button>
      </div>
    </header>
  );
}
