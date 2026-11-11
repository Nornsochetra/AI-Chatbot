import { useEffect, useRef } from 'react';

import type { Messages } from '../i18n';
import type { ChatMessage } from '../state/chatReducer';
import { MessageBubble } from './MessageBubble';
import { RatingCard } from './RatingCard';
import type { SpeakState } from './SpeakButton';
import { TypingIndicator } from './TypingIndicator';

interface Props {
  messages: ChatMessage[];
  /** show the bot's typing dots (not while an agent is chatting) */
  typing: boolean;
  /** the agent is typing: their login username, or null */
  agentTyping: string | null;
  t: Messages;
  onTalkToAgent: (() => void) | null;
  onRate: (message: ChatMessage, score: number, comment: string) => void;
  onDismissRating: (message: ChatMessage) => void;
  onSpeak: (message: ChatMessage) => void;
  /** the reply being read aloud (or loading / failed) */
  speaking: { id: string; state: SpeakState } | null;
}

export function MessageList({
  messages,
  typing,
  agentTyping,
  t,
  onTalkToAgent,
  onRate,
  onDismissRating,
  onSpeak,
  speaking,
}: Props) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [messages.length, typing, agentTyping]);

  return (
    <div className="kb-messages" role="log" aria-live="polite">
      {/* welcome is not stored, so it follows the language toggle */}
      <MessageBubble message={{ id: 'welcome', role: 'bot', text: t.welcome }} t={t} onTalkToAgent={null} />
      {messages.map((m) =>
        m.role === 'rating' ? (
          <RatingCard
            key={m.id}
            message={m}
            t={t}
            onSubmit={(score, comment) => onRate(m, score, comment)}
            onDismiss={() => onDismissRating(m)}
          />
        ) : (
          <MessageBubble
            key={m.id}
            message={m}
            t={t}
            onTalkToAgent={onTalkToAgent}
            onSpeak={onSpeak}
            speakState={speaking?.id === m.id ? speaking.state : undefined}
          />
        ),
      )}
      {agentTyping ? (
        <TypingIndicator t={t} label={t.agentTyping.replace('{name}', agentTyping)} />
      ) : (
        typing && <TypingIndicator t={t} />
      )}
      <div ref={endRef} />
    </div>
  );
}
