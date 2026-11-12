import { describe, expect, it } from 'vitest';

import { detectLanguage } from './language';

describe('detectLanguage', () => {
  it('detects Khmer and English', () => {
    expect(detectLanguage('តើសាខាបើកម៉ោងប៉ុន្មាន?')).toBe('km');
    expect(detectLanguage('What time do branches open?')).toBe('en');
  });

  it('treats mixed Khmer and English as Khmer', () => {
    expect(detectLanguage('ខ្ញុំភ្លេច PIN')).toBe('km');
  });

  it('cannot tell from digits, emoji or one letter', () => {
    expect(detectLanguage('123456')).toBeNull();
    expect(detectLanguage('👍')).toBeNull();
    expect(detectLanguage('k')).toBeNull();
  });
});
