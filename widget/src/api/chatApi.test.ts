import { afterEach, describe, expect, it, vi } from 'vitest';

import { ChatApiError, discardFile, endHandover, getUpdates, readAloud, requestHandover, sendFile, sendMessage, submitRating, transcribe } from './chatApi';

afterEach(() => {
  vi.unstubAllGlobals();
});

function mockFetch(impl: () => Promise<Response>) {
  const fn = vi.fn(impl);
  vi.stubGlobal('fetch', fn);
  return fn;
}

const ok = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));

describe('sendMessage', () => {
  it('posts to the chat endpoint and returns the response', async () => {
    const fetchFn = mockFetch(() => ok({ sessionId: 's1', reply: 'Hi' }));
    const res = await sendMessage('http://api', { channel: 'WEBSITE', text: 'hello' });

    expect(res.sessionId).toBe('s1');
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/messages');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ channel: 'WEBSITE', text: 'hello' });
  });

  it('maps 400 to a validation error', async () => {
    mockFetch(() => Promise.resolve(new Response('{}', { status: 400 })));
    await expect(sendMessage('http://api', { channel: 'WEBSITE', text: '' })).rejects.toMatchObject({ kind: 'validation' });
  });

  it('maps 500 to a server error', async () => {
    mockFetch(() => Promise.resolve(new Response('{}', { status: 500 })));
    await expect(sendMessage('http://api', { channel: 'WEBSITE', text: 'x' })).rejects.toMatchObject({ kind: 'server' });
  });

  it('maps a failed connection to a network error', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')));
    await expect(sendMessage('http://api', { channel: 'WEBSITE', text: 'x' })).rejects.toBeInstanceOf(ChatApiError);
    await expect(sendMessage('http://api', { channel: 'WEBSITE', text: 'x' })).rejects.toMatchObject({ kind: 'network' });
  });

  it('maps a timeout to a timeout error', async () => {
    mockFetch(() => Promise.reject(new DOMException('timed out', 'TimeoutError')));
    await expect(sendMessage('http://api', { channel: 'WEBSITE', text: 'x' })).rejects.toMatchObject({ kind: 'timeout' });
  });
});

describe('voice calls', () => {
  it('uploads the recording as multipart without forcing a Content-Type', async () => {
    const fetchFn = mockFetch(() => ok({ text: 'សួស្តី' }));
    const text = await transcribe('http://api', new Blob(['x'], { type: 'audio/webm' }));

    expect(text).toBe('សួស្តី');
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/speech/transcriptions');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('audio')).toBeInstanceOf(Blob);
    expect(init.headers).toBeUndefined(); // the browser adds the multipart boundary itself
  });

  it('fetches read-aloud audio for a stored reply', async () => {
    const fetchFn = mockFetch(() => Promise.resolve(new Response(new Blob(['RIFF']), { status: 200 })));
    const audio = await readAloud('http://api', 's1', 'r-1');

    expect(audio.size).toBe(4);
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/sessions/s1/replies/r-1/speech');
    expect(init.method).toBe('POST');
  });

  it('maps 503 (voice not configured / down) to unavailable', async () => {
    mockFetch(() => Promise.resolve(new Response('{}', { status: 503 })));
    await expect(transcribe('http://api', new Blob(['x']))).rejects.toMatchObject({ kind: 'unavailable' });
  });
});

describe('attachment calls', () => {
  it('sends the file as multipart with the channel and session', async () => {
    const fetchFn = mockFetch(() =>
      ok({
        sessionId: 's1',
        attachment: { id: 'a1', fileName: 'x.png', contentType: 'image/png', sizeBytes: 3, createdAt: '' },
        draft: true,
        readStatus: 'READ',
        text: 'Error 403',
        textMasked: false,
      }),
    );
    const res = await sendFile('http://api', { sessionId: 's1', channel: 'WEBSITE', file: new File(['abc'], 'x.png', { type: 'image/png' }) });

    expect(res.draft).toBe(true);
    expect(res.text).toBe('Error 403');
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/attachments');
    const form = init.body as FormData;
    expect(form.get('file')).toBeInstanceOf(Blob);
    expect(form.get('channel')).toBe('WEBSITE');
    expect(form.get('sessionId')).toBe('s1');
  });

  it('leaves the session out for the first file of a chat', async () => {
    const fetchFn = mockFetch(() => ok({ sessionId: 'new', attachment: {}, draft: true, readStatus: 'SKIPPED', text: null, textMasked: false }));
    await sendFile('http://api', { channel: 'WEBSITE', file: new File(['a'], 'a.png') });
    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.body as FormData).has('sessionId')).toBe(false);
  });

  it('maps 429 (files per chat) to limit', async () => {
    mockFetch(() => Promise.resolve(new Response('{}', { status: 429 })));
    await expect(sendFile('http://api', { channel: 'WEBSITE', file: new File(['a'], 'a.png') })).rejects.toMatchObject({ kind: 'limit' });
  });

  it('discards an unsent file', async () => {
    const fetchFn = mockFetch(() => Promise.resolve(new Response(null, { status: 204 })));
    await discardFile('http://api', 's 1', 'a1');
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/sessions/s%201/attachments/a1');
    expect(init.method).toBe('DELETE');
  });
});

describe('handover calls', () => {
  it('posts the handover request', async () => {
    const fetchFn = mockFetch(() => ok({ sessionId: 's1', state: 'HUMAN_ACTIVE', handoverStatus: 'WAITING' }));
    const res = await requestHandover('http://api', { sessionId: 's1', channel: 'WEBSITE', reason: 'USER_REQUEST' });

    expect(res.handoverStatus).toBe('WAITING');
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/handover');
    expect(JSON.parse(init.body as string)).toEqual({ sessionId: 's1', channel: 'WEBSITE', reason: 'USER_REQUEST' });
  });

  it('ends the live chat for the session', async () => {
    const fetchFn = mockFetch(() => Promise.resolve(new Response(null, { status: 204 })));
    await endHandover('http://api', 's 1');

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/sessions/s%201/handover/close');
    expect(init.method).toBe('POST');
  });

  it('posts a rating and maps 409 (already rated) to a conflict error', async () => {
    const fetchFn = mockFetch(() => ok({ id: 'r1', type: 'AGENT', score: 5, comment: null, createdAt: '' }));
    await submitRating('http://api', 's1', { type: 'AGENT', score: 5 });
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/sessions/s1/rating');
    expect(JSON.parse(init.body as string)).toEqual({ type: 'AGENT', score: 5 });

    mockFetch(() => Promise.resolve(new Response('{}', { status: 409 })));
    await expect(submitRating('http://api', 's1', { type: 'AI', score: 3 })).rejects.toMatchObject({ kind: 'conflict' });
  });

  it('polls updates with the cursor and an encoded session id', async () => {
    const fetchFn = mockFetch(() => ok({ state: 'HUMAN_ACTIVE', handoverStatus: 'ACTIVE', messages: [], cursor: 4 }));
    await getUpdates('http://api', 'a/b', 4);

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/v1/chat/sessions/a%2Fb/updates?after=4');
    expect(init.method).toBe('GET');
  });
});
