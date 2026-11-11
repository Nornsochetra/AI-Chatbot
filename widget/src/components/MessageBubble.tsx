import Markdown from 'react-markdown';

import type { Messages } from '../i18n';
import type { ChatMessage } from '../state/chatReducer';
import { HandoverPrompt } from './HandoverPrompt';
import { Notice } from './Notice';
import { SpeakButton, type SpeakState } from './SpeakButton';

interface Props {
  message: ChatMessage;
  t: Messages;
  /** null hides the handover prompt (already with an agent) */
  onTalkToAgent: (() => void) | null;
  /** read-aloud for bot replies that have a stored reply id; omitted hides the button */
  onSpeak?: (message: ChatMessage) => void;
  speakState?: SpeakState;
}

/** said to the customer like a chat reply, not shown as a grey status line */
const SPOKEN_CODES = new Set(['WAITING', 'WAITING_REMINDER']);

export function MessageBubble({ message, t, onTalkToAgent, onSpeak, speakState }: Props) {
  if (message.role === 'system' && message.systemCode && SPOKEN_CODES.has(message.systemCode)) {
    return (
      <div className="kb-row kb-row--bot">
        <div className="kb-bubble kb-bubble--bot">{t.system[message.systemCode]}</div>
      </div>
    );
  }

  if (message.role === 'system') {
    const text = message.systemCode
      ? t.system[message.systemCode].replace('{name}', message.senderName ?? t.agentLabel)
      : message.text;
    return <div className="kb-system">{text}</div>;
  }

  if (message.role === 'user' && message.attachment) {
    const file = message.attachment;
    return (
      <div className="kb-row kb-row--user">
        <div className={`kb-bubble kb-bubble--user kb-file ${file.removed ? 'kb-file--removed' : ''}`}>
          {file.previewUrl && !file.removed && file.contentType.startsWith('image/') && (
            // the customer's own image, from their device (never loaded back from the server)
            <img className="kb-file__preview" src={file.previewUrl} alt="" />
          )}
          <div className="kb-file__row">
            <span aria-hidden="true">📎</span>
            <span className="kb-file__name">{file.fileName}</span>
            <span className="kb-file__size">{formatSize(file.sizeBytes)}</span>
          </div>
          {file.removed && <div className="kb-file__status">{t.attachRemoved}</div>}
        </div>
      </div>
    );
  }

  if (message.role === 'user') {
    return (
      <div className="kb-row kb-row--user">
        <div className="kb-bubble kb-bubble--user">{message.text}</div>
        {message.masked && <Notice kind="info">{t.maskedNotice}</Notice>}
      </div>
    );
  }

  if (message.role === 'agent') {
    // agent text is shown as plain text, never as markdown / HTML
    return (
      <div className="kb-row kb-row--bot">
        <div className="kb-sender">{message.senderName ?? t.agentLabel}</div>
        <div className="kb-bubble kb-bubble--agent">{message.text}</div>
      </div>
    );
  }

  return (
    <div className="kb-row kb-row--bot">
      <div className="kb-bubble kb-bubble--bot">
        {/* skipHtml: bot replies can never inject HTML or scripts */}
        <Markdown
          skipHtml
          components={{
            a: ({ href, children }) => (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ),
          }}
        >
          {message.text}
        </Markdown>
      </div>
      {message.replyId && onSpeak && <SpeakButton t={t} state={speakState} onClick={() => onSpeak(message)} />}
      {message.handoverSuggested && onTalkToAgent && <HandoverPrompt t={t} onTalkToAgent={onTalkToAgent} />}
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
