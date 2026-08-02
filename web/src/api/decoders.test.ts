import { describe, expect, it } from 'vitest';

import {
  decodeChatResponse,
  decodeLoginResponse,
  decodeProducts,
  decodeReservation,
} from './decoders';

const UUID = '00000000-0000-0000-0000-000000000001';
const chat = {
  conversationId: UUID,
  traceId: UUID,
  turnId: UUID,
  reply: 'safe',
  outcome: 'completed',
  citations: [],
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

  it.each([
    'pendingAction',
    'pendingActionId',
    'requiredScope',
    'targetVersion',
    'argumentCommitment',
    'userSubject',
    'sandboxId',
    'toolCalls',
    'evidence',
    'serverReason',
    'internalReason',
    'reasonCode',
    'retrievedDocs',
    'stateChanges',
    'supportSessionId',
  ])('rejects forbidden chat field %s', (field) => {
    expect(() => decodeChatResponse({ ...chat, [field]: 'private' })).toThrow(
      'Malformed',
    );
  });

  it('rejects malformed or unbounded products instead of producing empty data', () => {
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
});
