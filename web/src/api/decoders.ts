export type Product = {
  productId: string;
  name: string;
  description: string;
  priceMinor: number;
  currency: string;
  stockQuantity: number;
  available: boolean;
  publicationVersion: number;
};

export type ReservationState =
  | 'PENDING'
  | 'ADMITTED'
  | 'REJECTED'
  | 'ORDERED'
  | 'CANCELLED'
  | 'UNFULFILLED';

export type Reservation = {
  reservationId: string;
  activityId: string;
  quantity: number;
  activityProjectionVersion: number;
  state: ReservationState;
  decisionCode:
    | 'ADMITTED'
    | 'ACTIVITY_INACTIVE'
    | 'NOT_OPEN'
    | 'EXPIRED'
    | 'STALE_VERSION'
    | 'EXHAUSTED'
    | 'DUPLICATE_USER'
    | 'TRANSACTION_TIMEOUT'
    | null;
  projectionVersion: number;
  replay: boolean;
  durableOrderCreated: boolean;
  orderId: string | null;
};

export type ChatOutcome =
  | 'completed'
  | 'action_completed'
  | 'budget_exhausted'
  | 'provider_denied'
  | 'retrieval_denied'
  | 'action_pending'
  | 'action_clarification'
  | 'action_declined'
  | 'action_expired'
  | 'action_rejected';

export type Citation = {
  sourceId: string;
  chunkId: string;
  sourceVersion: number;
  docType: 'faq' | 'product';
  title: string;
};

export type ChatResponse = {
  conversationId: string;
  traceId: string;
  turnId: string;
  reply: string;
  outcome: ChatOutcome;
  receiptId: string | null;
  citations: Citation[];
};

export type PublicError =
  | { detail: string }
  | { error: string }
  | {
      category:
        | 'AUTHENTICATION'
        | 'AUTHORIZATION'
        | 'VALIDATION'
        | 'CONFLICT'
        | 'UNAVAILABLE';
      message: string;
    };

type RecordValue = Record<string, unknown>;

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function closedRecord(value: unknown, keys: readonly string[]): RecordValue {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Malformed response');
  }
  const record = value as RecordValue;
  const actual = Object.keys(record).sort();
  const expected = [...keys].sort();
  if (
    actual.length !== expected.length ||
    actual.some((key, index) => key !== expected[index])
  ) {
    throw new Error('Malformed response');
  }
  return record;
}

function stringValue(value: unknown, minimum: number, maximum: number): string {
  if (
    typeof value !== 'string' ||
    value.length < minimum ||
    value.length > maximum
  ) {
    throw new Error('Malformed response');
  }
  return value;
}

function integerValue(
  value: unknown,
  minimum: number,
  maximum: number,
): number {
  if (
    !Number.isSafeInteger(value) ||
    (value as number) < minimum ||
    (value as number) > maximum
  ) {
    throw new Error('Malformed response');
  }
  return value as number;
}

function booleanValue(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Malformed response');
  return value;
}

function enumValue<const T extends readonly string[]>(
  value: unknown,
  allowed: T,
): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value))
    throw new Error('Malformed response');
  return value as T[number];
}

function uuidValue(value: unknown): string {
  const decoded = stringValue(value, 36, 36);
  if (!UUID_PATTERN.test(decoded)) throw new Error('Malformed response');
  return decoded;
}

export function decodeLoginResponse(value: unknown) {
  const record = closedRecord(value, ['accessToken', 'tokenType', 'expiresIn']);
  if (record.tokenType !== 'Bearer') throw new Error('Malformed response');
  return {
    accessToken: stringValue(record.accessToken, 1, 16_384),
    tokenType: 'Bearer' as const,
    expiresIn: integerValue(record.expiresIn, 1, 86_400),
  };
}

export function decodeSessionResponse(value: unknown) {
  const record = closedRecord(value, ['sessionId']);
  return { sessionId: stringValue(record.sessionId, 1, 64) };
}

function decodeProduct(value: unknown): Product {
  const record = closedRecord(value, [
    'productId',
    'name',
    'description',
    'priceMinor',
    'currency',
    'stockQuantity',
    'available',
    'publicationVersion',
  ]);
  const currency = stringValue(record.currency, 3, 3);
  if (!/^[A-Z]{3}$/.test(currency)) throw new Error('Malformed response');
  return {
    productId: stringValue(record.productId, 1, 128),
    name: stringValue(record.name, 1, 200),
    description: stringValue(record.description, 0, 2_000),
    priceMinor: integerValue(record.priceMinor, 0, Number.MAX_SAFE_INTEGER),
    currency,
    stockQuantity: integerValue(
      record.stockQuantity,
      0,
      Number.MAX_SAFE_INTEGER,
    ),
    available: booleanValue(record.available),
    publicationVersion: integerValue(
      record.publicationVersion,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
  };
}

export function decodeProducts(value: unknown): Product[] {
  if (!Array.isArray(value) || value.length > 100)
    throw new Error('Malformed response');
  return value.map(decodeProduct);
}

export function decodeReservation(value: unknown): Reservation {
  const record = closedRecord(value, [
    'reservationId',
    'activityId',
    'quantity',
    'activityProjectionVersion',
    'state',
    'decisionCode',
    'projectionVersion',
    'replay',
    'durableOrderCreated',
    'orderId',
  ]);
  const decisionCode =
    record.decisionCode === null
      ? null
      : enumValue(record.decisionCode, [
          'ADMITTED',
          'ACTIVITY_INACTIVE',
          'NOT_OPEN',
          'EXPIRED',
          'STALE_VERSION',
          'EXHAUSTED',
          'DUPLICATE_USER',
          'TRANSACTION_TIMEOUT',
        ] as const);
  return {
    reservationId: uuidValue(record.reservationId),
    activityId: stringValue(record.activityId, 1, 64),
    quantity: integerValue(record.quantity, 1, Number.MAX_SAFE_INTEGER),
    activityProjectionVersion: integerValue(
      record.activityProjectionVersion,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    state: enumValue(record.state, [
      'PENDING',
      'ADMITTED',
      'REJECTED',
      'ORDERED',
      'CANCELLED',
      'UNFULFILLED',
    ] as const),
    decisionCode,
    projectionVersion: integerValue(
      record.projectionVersion,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    replay: booleanValue(record.replay),
    durableOrderCreated: booleanValue(record.durableOrderCreated),
    orderId: record.orderId === null ? null : uuidValue(record.orderId),
  };
}

function decodeCitation(value: unknown): Citation {
  const record = closedRecord(value, [
    'sourceId',
    'chunkId',
    'sourceVersion',
    'docType',
    'title',
  ]);
  return {
    sourceId: stringValue(record.sourceId, 1, 128),
    chunkId: stringValue(record.chunkId, 1, 128),
    sourceVersion: integerValue(
      record.sourceVersion,
      1,
      Number.MAX_SAFE_INTEGER,
    ),
    docType: enumValue(record.docType, ['faq', 'product'] as const),
    title: stringValue(record.title, 1, 200),
  };
}

// A committed action and its receipt are one truth on this path too: the page must not be able to
// render a success the server did not record, whichever endpoint it came from.
function receiptFor(outcome: unknown, receiptId: unknown): string | null {
  if (outcome === 'action_completed') return uuidValue(receiptId);
  if (receiptId !== null) throw new Error('Malformed response');
  return null;
}

export function decodeChatResponse(value: unknown): ChatResponse {
  const record = closedRecord(value, [
    'conversationId',
    'traceId',
    'turnId',
    'reply',
    'outcome',
    'receiptId',
    'citations',
  ]);
  if (!Array.isArray(record.citations) || record.citations.length > 3) {
    throw new Error('Malformed response');
  }
  return {
    conversationId: uuidValue(record.conversationId),
    traceId: uuidValue(record.traceId),
    turnId: uuidValue(record.turnId),
    reply: stringValue(record.reply, 0, 256),
    outcome: enumValue(record.outcome, [
      'completed',
      'action_completed',
      'budget_exhausted',
      'provider_denied',
      'retrieval_denied',
      'action_pending',
      'action_clarification',
      'action_declined',
      'action_expired',
      'action_rejected',
    ] as const),
    receiptId: receiptFor(record.outcome, record.receiptId),
    citations: record.citations.map(decodeCitation),
  };
}

export function decodePublicError(value: unknown): PublicError {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Malformed error');
  }
  const keys = Object.keys(value);
  if (keys.length === 1 && keys[0] === 'detail') {
    const record = value as RecordValue;
    return { detail: stringValue(record.detail, 1, 64) };
  }
  if (keys.length === 1 && keys[0] === 'error') {
    const record = value as RecordValue;
    return { error: stringValue(record.error, 1, 64) };
  }
  const record = closedRecord(value, ['category', 'message']);
  return {
    category: enumValue(record.category, [
      'AUTHENTICATION',
      'AUTHORIZATION',
      'VALIDATION',
      'CONFLICT',
      'UNAVAILABLE',
    ] as const),
    message: stringValue(record.message, 1, 256),
  };
}
