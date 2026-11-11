import { useCallback, useEffect, useRef, useState } from 'react';

/** Microphone recording needs getUserMedia + MediaRecorder and a secure page (https or localhost). */
export function isRecordingSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.isSecureContext &&
    typeof navigator.mediaDevices?.getUserMedia === 'function' &&
    typeof MediaRecorder !== 'undefined'
  );
}

/** Chrome / Firefox record WebM or Ogg Opus, Safari records MP4; all are accepted by the backend. */
function pickMimeType(): string | undefined {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];
  return candidates.find((type) => MediaRecorder.isTypeSupported(type));
}

interface Options {
  maxSeconds: number;
  /** called with the recording when it stops (by the user or at maxSeconds) */
  onComplete: (audio: Blob, seconds: number) => void;
}

export function useRecorder({ maxSeconds, onComplete }: Options) {
  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startedAtRef = useRef(0);
  const discardRef = useRef(false);
  const onCompleteRef = useRef(onComplete);
  onCompleteRef.current = onComplete;

  const cleanup = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = null;
    streamRef.current?.getTracks().forEach((track) => track.stop()); // turns the browser's mic indicator off
    streamRef.current = null;
    recorderRef.current = null;
    setRecording(false);
  }, []);

  const stop = useCallback(() => {
    if (recorderRef.current?.state === 'recording') recorderRef.current.stop();
  }, []);

  /** Throws when the microphone is blocked or missing (e.g. NotAllowedError). */
  const start = useCallback(async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mimeType = pickMimeType();
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    const chunks: Blob[] = [];

    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.push(e.data);
    };
    recorder.onstop = () => {
      const seconds = (Date.now() - startedAtRef.current) / 1000;
      const audio = new Blob(chunks, { type: recorder.mimeType || mimeType || 'audio/webm' });
      cleanup();
      if (!discardRef.current) onCompleteRef.current(audio, seconds);
    };

    streamRef.current = stream;
    recorderRef.current = recorder;
    discardRef.current = false;
    startedAtRef.current = Date.now();
    setElapsed(0);
    setRecording(true);
    recorder.start();
    timerRef.current = setInterval(() => {
      const seconds = Math.floor((Date.now() - startedAtRef.current) / 1000);
      setElapsed(seconds);
      if (seconds >= maxSeconds) stop();
    }, 250);
  }, [cleanup, maxSeconds, stop]);

  // leaving the page while recording: stop the mic, drop the recording
  useEffect(
    () => () => {
      discardRef.current = true;
      if (recorderRef.current?.state === 'recording') recorderRef.current.stop();
      else cleanup();
    },
    [cleanup],
  );

  return { recording, elapsed, start, stop };
}
