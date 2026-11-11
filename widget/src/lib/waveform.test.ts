import { describe, expect, it } from 'vitest';

import { BAR_COUNT, peaksFrom, placeholderPeaks } from './waveform';

describe('waveform', () => {
  it('takes the loudest sample per bar and scales the loudest bar to 1', () => {
    const samples = new Float32Array([0.1, -0.5, 0.2, 0.25, -0.05, 0.0]);
    expect(peaksFrom(samples, 3)).toEqual([1, 0.5, 0.12]);
  });

  it('keeps silent bars visible and handles empty or silent audio', () => {
    expect(peaksFrom(new Float32Array(9), 3)).toEqual([0.12, 0.12, 0.12]);
    expect(peaksFrom(new Float32Array(0))).toHaveLength(BAR_COUNT);
  });

  it('has a placeholder wave when the recording cannot be decoded', () => {
    const peaks = placeholderPeaks();
    expect(peaks).toHaveLength(BAR_COUNT);
    expect(peaks.every((p) => p > 0 && p <= 1)).toBe(true);
  });
});
