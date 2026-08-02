import { requestJson } from './client';
import { decodeLoginResponse } from './decoders';

export function login(
  loginIdentifier: string,
  password: string,
  signal: AbortSignal,
) {
  return requestJson(
    '/auth/login',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginIdentifier, password }),
      signal,
    },
    [200],
    decodeLoginResponse,
  );
}
