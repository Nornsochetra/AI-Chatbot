import { useEffect, useRef, useState } from 'react';

import type { Messages } from '../i18n';
import type { VoiceClip } from '../state/chatReducer';

/** only one voice message plays at a time */
let playingNow: HTMLAudioElement | null = null;

interface Props {
  t: Messages;
  voice: VoiceClip;
}

/** The customer's own recording: play button, waveform and length, like a voice message in a messaging app. */
export function VoiceNote({ t, voice }: Props) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);

  useEffect(
    () => () => {
      audioRef.current?.pause();
    },
    [],
  );

  function toggle() {
    if (!voice.url) return;
    let audio = audioRef.current;
    if (!audio) {
      audio = new Audio(voice.url);
      audio.ontimeupdate = () => setPosition(audio!.currentTime);
      audio.onpause = () => setPlaying(false);
      audio.onplay = () => setPlaying(true);
      audio.onended = () => setPosition(0);
      audioRef.current = audio;
    }
    if (playing) {
      audio.pause();
      return;
    }
    if (playingNow && playingNow !== audio) playingNow.pause();
    playingNow = audio;
    void audio.play().catch(() => setPlaying(false));
  }

  // WebM recordings carry no length, so progress uses the length measured while recording
  const progress = playing || position > 0 ? Math.min(1, position / Math.max(voice.seconds, 0.1)) : 0;
  const shownSeconds = playing ? position : voice.seconds;
  const label = !voice.url ? t.voiceNoteGone : playing ? t.voicePause : t.voicePlay;

  return (
    <div className="kb-voice">
      <button
        type="button"
        className="kb-voice__play"
        onClick={toggle}
        disabled={!voice.url}
        aria-pressed={playing}
        aria-label={label}
        title={label}
      >
        {playing ? (
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
            <rect x="6" y="5" width="4" height="14" rx="1" />
            <rect x="14" y="5" width="4" height="14" rx="1" />
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
            <path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z" />
          </svg>
        )}
      </button>
      <div className="kb-voice__wave" aria-hidden="true">
        {voice.peaks.map((peak, i) => (
          <span
            key={i}
            className={`kb-voice__bar ${(i + 0.5) / voice.peaks.length <= progress ? 'kb-voice__bar--played' : ''}`}
            style={{ height: `${Math.round(peak * 100)}%` }}
          />
        ))}
      </div>
      <span className="kb-voice__time">{formatSeconds(shownSeconds)}</span>
    </div>
  );
}

function formatSeconds(value: number): string {
  const total = Math.max(0, Math.round(value));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
