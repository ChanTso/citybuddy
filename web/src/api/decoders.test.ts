import { describe, expect, it } from 'vitest';

import {
  decodeLoginResponse,
  decodeProducts,
  decodePublicError,
  decodeReservation,
} from './decoders';

const UUID = '00000000-0000-0000-0000-000000000001';
const product = {
  productId: 'p',
  name: 'Published',
  description: '',
  priceMinor: 1,
  currency: 'AUD',
  stockQuantity: 1,
  available: true,
  publicationVersion: 1,
};
const reservation = {
  reservationId: UUID,
  activityId: 'a',
  quantity: 1,
  activityProjectionVersion: 1,
  state: 'ADMITTED',
  decisionCode: 'ADMITTED',
  projectionVersion: 2,
  replay: false,
  durableOrderCreated: false,
  orderId: null,
};

describe('closed public decoders', () => {
  it('accepts only the bounded login shape', () => {
    expect(
      decodeLoginResponse({
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 900,
      }),
    ).toEqual({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 900 });
    expect(() =>
      decodeLoginResponse({
        accessToken: 'token',
        tokenType: 'Bearer',
        expiresIn: 900,
        owner: 'x',
      }),
    ).toThrow('Malformed');
  });

  it('rejects malformed products instead of producing empty data', () => {
    expect(() => decodeProducts([{ productId: 'p' }])).toThrow('Malformed');
    expect(() =>
      decodeProducts(Array.from({ length: 101 }, () => ({}))),
    ).toThrow('Malformed');
    expect(() =>
      decodeProducts([
        {
          productId: 'p',
          name: 'n',
          description: '',
          priceMinor: 1,
          currency: 'AUD',
          stockQuantity: 1,
          available: true,
          publicationVersion: 1,
          internal: true,
        },
      ]),
    ).toThrow('Malformed');
  });

  it('accepts a valid retail catalog larger than the display limit and validates its tail', () => {
    const catalog = Array.from({ length: 104 }, (_, index) => ({
      ...product,
      productId: `sku-${index}`,
    }));
    expect(decodeProducts(catalog)).toEqual(catalog);
    expect(() =>
      decodeProducts([...catalog, { ...product, currency: 'bad' }]),
    ).toThrow('Malformed');
  });

  it.each([
    ['productId', ''],
    ['name', ''],
    ['description', 'x'.repeat(2_001)],
    ['priceMinor', -1],
    ['currency', 'aud'],
    ['stockQuantity', -1],
    ['available', 'true'],
    ['publicationVersion', 0],
  ])('rejects a product with invalid %s', (field, value) => {
    expect(() => decodeProducts([{ ...product, [field]: value }])).toThrow(
      'Malformed',
    );
  });

  it.each([
    ['reservationId', 'not-a-uuid'],
    ['activityId', ''],
    ['quantity', 0],
    ['activityProjectionVersion', 0],
    ['state', 'UNKNOWN'],
    ['decisionCode', 'UNKNOWN'],
    ['projectionVersion', 0],
    ['replay', 0],
    ['durableOrderCreated', 1],
    ['orderId', 'not-a-uuid'],
  ])('rejects a reservation with invalid %s', (field, value) => {
    expect(() => decodeReservation({ ...reservation, [field]: value })).toThrow(
      'Malformed',
    );
  });

  it('rejects extra reservation fields', () => {
    expect(() =>
      decodeReservation({ ...reservation, userSubject: 'private' }),
    ).toThrow('Malformed');
  });

  it('accepts the production-model terminal cancellation without inventing order truth', () => {
    expect(
      decodeReservation({
        reservationId: UUID,
        activityId: 'a',
        quantity: 1,
        activityProjectionVersion: 1,
        state: 'CANCELLED',
        decisionCode: 'ADMITTED',
        projectionVersion: 4,
        replay: false,
        durableOrderCreated: true,
        orderId: UUID,
      }).state,
    ).toBe('CANCELLED');
  });

  it('accepts the admitted terminal that did not create an order', () => {
    expect(
      decodeReservation({
        ...reservation,
        state: 'UNFULFILLED',
        decisionCode: 'ADMITTED',
        projectionVersion: 3,
        durableOrderCreated: false,
        orderId: null,
      }),
    ).toEqual(
      expect.objectContaining({
        state: 'UNFULFILLED',
        decisionCode: 'ADMITTED',
        projectionVersion: 3,
        durableOrderCreated: false,
        orderId: null,
      }),
    );
  });

  it('keeps public errors closed and bounded', () => {
    expect(decodePublicError({ detail: 'Unavailable' })).toEqual({
      detail: 'Unavailable',
    });
    expect(decodePublicError({ error: 'Unauthorized' })).toEqual({
      error: 'Unauthorized',
    });
    expect(
      decodePublicError({ category: 'CONFLICT', message: 'Fixed' }),
    ).toEqual({ category: 'CONFLICT', message: 'Fixed' });
    expect(() =>
      decodePublicError({ detail: 'Fixed', reason: 'private' }),
    ).toThrow('Malformed');
    expect(() =>
      decodePublicError({ category: 'INTERNAL', message: 'Fixed' }),
    ).toThrow('Malformed');
  });
});
