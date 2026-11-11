import type { Messages } from '../i18n';

export type SpeakState = 'loading' | 'playing' | 'error';

interface Props {
  t: Messages;
  state?: SpeakState;
  onClick: () => void;
}

/** Read-aloud control under a bot reply; the text stays visible while the audio plays. */
export function SpeakButton({ t, state, onClick }: Props) {
  const playing = state === 'playing';
  const label = playing ? t.stopReading : state === 'loading' ? t.readAloudLoading : t.readAloud;
  return (
    <div className="kb-speak">
      <button
        type="button"
        className={`kb-speak__button ${playing ? 'kb-speak__button--playing' : ''}`}
        onClick={onClick}
        disabled={state === 'loading'}
        aria-pressed={playing}
        aria-label={label}
        title={label}
      >
        {state === 'loading' ? (
          <span className="kb-spinner kb-spinner--small" aria-hidden="true" />
        ) : playing ? (
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
            <rect x="6" y="6" width="12" height="12" rx="2" />
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
            <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4zM14 3.2v2.07a7 7 0 0 1 0 13.46v2.07a9 9 0 0 0 0-17.6z" />
          </svg>
        )}
        <span>{label}</span>
      </button>
      {state === 'error' && <span className="kb-speak__error">{t.readAloudError}</span>}
    </div>
  );
}
