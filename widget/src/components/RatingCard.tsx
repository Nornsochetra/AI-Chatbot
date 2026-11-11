import { useState } from 'react';

import type { Messages } from '../i18n';
import type { ChatMessage } from '../state/chatReducer';

export const COMMENT_MAX_LENGTH = 500;

interface Props {
  message: ChatMessage;
  t: Messages;
  onSubmit: (score: number, comment: string) => void;
  onDismiss: () => void;
}

/** Post-chat rating: 1–5 stars and an optional comment. */
export function RatingCard({ message, t, onSubmit, onDismiss }: Props) {
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');

  if (message.ratingStatus === 'dismissed') return null;
  if (message.ratingStatus === 'sent') return <div className="kb-system">{t.ratingThanks}</div>;

  const sending = message.ratingStatus === 'sending';

  return (
    <form
      className="kb-rating"
      onSubmit={(e) => {
        e.preventDefault();
        if (score > 0 && !sending) onSubmit(score, comment.trim());
      }}
    >
      <div className="kb-rating__title">{message.ratingType === 'AGENT' ? t.ratingAgentTitle : t.ratingAiTitle}</div>
      <div className="kb-rating__stars" role="radiogroup" aria-label={t.ratingAgentTitle}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={score === n}
            aria-label={t.ratingStarLabel.replace('{n}', String(n))}
            className={`kb-rating__star ${n <= score ? 'kb-rating__star--on' : ''}`}
            onClick={() => setScore(n)}
            disabled={sending}
          >
            ★
          </button>
        ))}
      </div>
      {score > 0 && (
        <textarea
          className="kb-rating__comment"
          rows={2}
          maxLength={COMMENT_MAX_LENGTH}
          placeholder={t.ratingCommentPlaceholder}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          disabled={sending}
        />
      )}
      {message.ratingStatus === 'error' && <div className="kb-rating__error">{t.ratingError}</div>}
      <div className="kb-rating__actions">
        <button type="button" className="kb-button kb-button--link" onClick={onDismiss} disabled={sending}>
          {t.ratingSkip}
        </button>
        <button type="submit" className="kb-button kb-button--primary" disabled={score === 0 || sending}>
          {t.ratingSubmit}
        </button>
      </div>
    </form>
  );
}
