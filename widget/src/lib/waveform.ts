/** number of bars in a voice message bubble */
export const BAR_COUNT = 30;

/** bars used when the browser cannot decode the recording: a gentle wave, so the bubble still looks like voice */
export function placeholderPeaks(bars = BAR_COUNT): number[] {
  return Array.from({ length: bars }, (_, i) => round(0.35 + 0.3 * Math.abs(Math.sin(i * 0.7))));
}

/** Loudest sample per bar, scaled so the loudest bar is 1; quiet bars keep a small visible height. */
export function peaksFrom(samples: Float32Array, bars = BAR_COUNT): number[] {
  if (samples.length === 0) return placeholderPeaks(bars);
  const size = Math.max(1, Math.floor(samples.length / bars));
  const peaks: number[] = [];
  for (let bar = 0; bar < bars; bar++) {
    let max = 0;
    const end = Math.min((bar + 1) * size, samples.length);
    for (let i = bar * size; i < end; i++) max = Math.max(max, Math.abs(samples[i]));
    peaks.push(max);
  }
  const top = Math.max(...peaks);
  if (top === 0) return peaks.map(() => 0.12);
  return peaks.map((p) => round(Math.max(0.12, p / top)));
}

/** Waveform of the customer's own recording, decoded in the browser (nothing is uploaded for this). */
export async function waveformPeaks(audio: Blob, bars = BAR_COUNT): Promise<number[]> {
  const AudioCtx = typeof window === 'undefined' ? undefined : window.AudioContext;
  if (!AudioCtx) return placeholderPeaks(bars);
  const context = new AudioCtx();
  try {
    const buffer = await context.decodeAudioData(await audio.arrayBuffer());
    return peaksFrom(buffer.getChannelData(0), bars);
  } catch {
    return placeholderPeaks(bars);
  } finally {
    void context.close();
  }
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
