import { decodePublicError } from './decoders';
import { parseStrictJson } from './strictJson';

const MAX_JSON_BYTES = 64 * 1024;

export type ApiFailureKind =
  | 'unauthorized'
  | 'forbidden'
  | 'conflict'
  | 'invalid'
  | 'dependency'
  | 'malformed'
  | 'network';

export class ApiFailure extends Error {
  constructor(public readonly kind: ApiFailureKind) {
    super(kind);
    this.name = 'ApiFailure';
  }
}

async function readBoundedBody(
  response: Response,
  maximum = MAX_JSON_BYTES,
): Promise<string> {
  const declared = response.headers.get('content-length');
  if (declared !== null) {
    const length = Number(declared);
    if (!Number.isSafeInteger(length) || length < 0 || length > maximum) {
      throw new ApiFailure('malformed');
    }
  }
  if (response.body === null) throw new ApiFailure('malformed');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let bytes = 0;
  let text = '';
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > maximum) throw new ApiFailure('malformed');
      text += decoder.decode(chunk.value, { stream: true });
    }
    text += decoder.decode();
    return text;
  } catch (error) {
    void reader.cancel();
    if (error instanceof ApiFailure) throw error;
    throw new ApiFailure('malformed');
  }
}

async function bodyAsUnknown(response: Response): Promise<unknown> {
  const text = await readBoundedBody(response);
  try {
    return parseStrictJson(text);
  } catch {
    throw new ApiFailure('malformed');
  }
}

export function failureKind(status: number): ApiFailureKind {
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 409) return 'conflict';
  if (status === 400 || status === 404 || status === 422) return 'invalid';
  if (status === 502 || status === 503) return 'dependency';
  return 'network';
}

export async function requestJson<T>(
  url: string,
  init: RequestInit,
  successStatuses: readonly number[],
  decode: (value: unknown) => T,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError')
      throw error;
    throw new ApiFailure('network');
  }
  if (!successStatuses.includes(response.status)) {
    try {
      decodePublicError(await bodyAsUnknown(response));
    } catch {
      // The UI exposes only the status classification, never an untrusted body.
    }
    throw new ApiFailure(failureKind(response.status));
  }
  try {
    return decode(await bodyAsUnknown(response));
  } catch (error) {
    if (response.status >= 400)
      throw new ApiFailure(failureKind(response.status));
    if (error instanceof ApiFailure) throw error;
    throw new ApiFailure('malformed');
  }
}

export function bearerHeaders(token: string): HeadersInit {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}
