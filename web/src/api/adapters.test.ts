import { afterEach, describe, expect, it, vi } from 'vitest';

import { createSupportSession, sendChat, streamChat } from './agent';
import { login } from './auth';
import { listProducts, pollReservation, submitReservation } from './commerce';
import { UnsupportedReceiptError } from './sse';

const UUID = '00000000-0000-0000-0000-000000000001';
const TOKEN = 'direct-user-token';

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const product = {
  productId: 'tea-1',
  name: 'Tea',
  description: 'Published.',
  priceMinor: 1250,
  currency: 'AUD',
  stockQuantity: 4,
  available: true,
  publicationVersion: 3,
};

const reservation = {
  reservationId: UUID,
  activityId: 'tea/drop',
  quantity: 2,
  activityProjectionVersion: 7,
  state: 'ADMITTED',
  decisionCode: 'ADMITTED',
  projectionVersion: 2,
  replay: false,
  durableOrderCreated: false,
  orderId: null,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('public API adapters', () => {
  it('freezes the exact login route, method, headers, body, and signal', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        accessToken: TOKEN,
        tokenType: 'Bearer',
        expiresIn: 900,
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await expect(
      login('direct@example.test', 'secret', controller.signal),
    ).resolves.toEqual({
      accessToken: TOKEN,
      tokenType: 'Bearer',
      expiresIn: 900,
    });
    expect(fetchMock).toHaveBeenCalledWith('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        loginIdentifier: 'direct@example.test',
        password: 'secret',
      }),
      signal: controller.signal,
    });
  });

  it('freezes product and owner-scoped reservation reads without owner headers', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([product]))
      .mockResolvedValueOnce(jsonResponse(reservation));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await expect(listProducts(TOKEN, controller.signal)).resolves.toEqual([
      product,
    ]);
    await expect(
      pollReservation(TOKEN, UUID, controller.signal),
    ).resolves.toEqual(reservation);
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/products', {
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        'Content-Type': 'application/json',
      },
      signal: controller.signal,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/reservations/${UUID}`, {
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        'Content-Type': 'application/json',
      },
      signal: controller.signal,
    });
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers).not.toHaveProperty('X-User-Id');
      expect(init.headers).not.toHaveProperty('X-Owner-Id');
      expect(init.headers).not.toHaveProperty('X-Eval-Sandbox-Id');
    }
  });

  it('freezes the reservation mutation path, encoded locator, intent key, and exact body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(reservation, 201));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await expect(
      submitReservation(
        TOKEN,
        'tea/drop',
        'reservation-intent-key',
        { quantity: 2, expectedActivityVersion: 7 },
        controller.signal,
      ),
    ).resolves.toEqual(reservation);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/seckill/activities/tea%2Fdrop/reservations',
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${TOKEN}`,
          'Content-Type': 'application/json',
          'Idempotency-Key': 'reservation-intent-key',
        },
        body: JSON.stringify({ quantity: 2, expectedActivityVersion: 7 }),
        signal: controller.signal,
      },
    );
  });

  it('freezes support session and JSON chat routes, ownership headers, and exact bodies', async () => {
    const chat = {
      conversationId: UUID,
      traceId: UUID,
      turnId: UUID,
      reply: 'Safe reply.',
      outcome: 'completed',
      citations: [],
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ sessionId: 'owned-session' }, 201))
      .mockResolvedValueOnce(jsonResponse(chat));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await expect(
      createSupportSession(TOKEN, controller.signal),
    ).resolves.toEqual({ sessionId: 'owned-session' });
    await expect(
      sendChat(
        TOKEN,
        'owned-session',
        'chat-intent-key',
        'hello',
        controller.signal,
      ),
    ).resolves.toEqual(chat);
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/sessions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: '{}',
      signal: controller.signal,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/chat', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        'Content-Type': 'application/json',
        'X-Session-Id': 'owned-session',
        'Idempotency-Key': 'chat-intent-key',
      },
      body: JSON.stringify({ message: 'hello' }),
      signal: controller.signal,
    });
  });

  it('freezes the POST-SSE request and cancels its reader on unsupported receipt truth', async () => {
    const cancel = vi.fn();
    const bytes = new TextEncoder().encode(
      `event: action_receipt\ndata: {"sequence":1,"receiptId":"${UUID}","status":"SUCCEEDED"}\n\n`,
    );
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes);
      },
      cancel,
    });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await expect(
      streamChat(
        TOKEN,
        'owned-session',
        'stream-intent-key',
        'stream this',
        controller.signal,
      ),
    ).rejects.toBeInstanceOf(UnsupportedReceiptError);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/chat/stream', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        'Content-Type': 'application/json',
        'X-Session-Id': 'owned-session',
        'Idempotency-Key': 'stream-intent-key',
      },
      body: JSON.stringify({ message: 'stream this' }),
      signal: controller.signal,
    });
  });
});
