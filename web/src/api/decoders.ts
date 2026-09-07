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
  if (!Array.isArray(value)) throw new Error('Malformed response');
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
