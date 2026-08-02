import { bearerHeaders, requestJson } from './client';
import { decodeProducts, decodeReservation } from './decoders';

export function listProducts(token: string, signal: AbortSignal) {
  return requestJson(
    '/api/products',
    { headers: bearerHeaders(token), signal },
    [200],
    decodeProducts,
  );
}

export type ReservationRequest = {
  quantity: number;
  expectedActivityVersion: number;
};

export function submitReservation(
  token: string,
  activityId: string,
  idempotencyKey: string,
  request: ReservationRequest,
  signal: AbortSignal,
) {
  return requestJson(
    `/api/seckill/activities/${encodeURIComponent(activityId)}/reservations`,
    {
      method: 'POST',
      headers: { ...bearerHeaders(token), 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(request),
      signal,
    },
    [200, 201, 202, 409],
    decodeReservation,
  );
}

export function pollReservation(
  token: string,
  reservationId: string,
  signal: AbortSignal,
) {
  return requestJson(
    `/api/reservations/${encodeURIComponent(reservationId)}`,
    { headers: bearerHeaders(token), signal },
    [200],
    decodeReservation,
  );
}
