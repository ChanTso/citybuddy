import { bearerHeaders, requestJson } from './client';
import { decodeChatResponse, decodeSessionResponse } from './decoders';

export function createSupportSession(token: string, signal: AbortSignal) {
  return requestJson(
    '/api/sessions',
    { method: 'POST', headers: bearerHeaders(token), body: '{}', signal },
    [201],
    decodeSessionResponse,
  );
}

export function sendChat(
  token: string,
  sessionId: string,
  idempotencyKey: string,
  message: string,
  signal: AbortSignal,
) {
  return requestJson(
    '/api/chat',
    {
      method: 'POST',
      headers: {
        ...bearerHeaders(token),
        'X-Session-Id': sessionId,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ message }),
      signal,
    },
    [200],
    decodeChatResponse,
  );
}
