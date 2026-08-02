import { ApiFailure, bearerHeaders, failureKind, requestJson } from './client';
import { decodeChatResponse, decodeSessionResponse } from './decoders';
import { SseParser } from './sse';

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

export async function streamChat(
  token: string,
  sessionId: string,
  idempotencyKey: string,
  message: string,
  signal: AbortSignal,
) {
  let response: Response;
  try {
    response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        ...bearerHeaders(token),
        'X-Session-Id': sessionId,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ message }),
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError')
      throw error;
    throw new ApiFailure('network');
  }
  if (response.status !== 200)
    throw new ApiFailure(failureKind(response.status));
  if (
    !response.headers.get('content-type')?.startsWith('text/event-stream') ||
    response.body === null
  ) {
    throw new ApiFailure('malformed');
  }
  const reader = response.body.getReader();
  const parser = new SseParser();
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      parser.push(chunk.value);
    }
    return parser.finish();
  } catch (error) {
    void reader.cancel();
    throw error;
  }
}
