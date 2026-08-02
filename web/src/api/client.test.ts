import { afterEach, describe, expect, it, vi } from 'vitest';

import { requestJson } from './client';
import { decodeReservation } from './decoders';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('bounded JSON client', () => {
  it('maps a 409 error-shaped reservation response to conflict', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"code":"conflict","message":"fixed"}', {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    await expect(
      requestJson('/reservation', {}, [409], decodeReservation),
    ).rejects.toEqual(expect.objectContaining({ kind: 'conflict' }));
  });

  it('rejects duplicate keys in a successful response before decoding', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"value":1,"value":2}', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    await expect(
      requestJson('/strict', {}, [200], (value) => value),
    ).rejects.toEqual(expect.objectContaining({ kind: 'malformed' }));
  });
});
