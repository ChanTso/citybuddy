import { ApiFailure } from './client';
import type { ChatOutcome } from './decoders';
import { parseStrictJson } from './strictJson';

const MAX_EVENT_BYTES = 2_048;
const MAX_BUFFER_BYTES = 4_096;
const MAX_STREAM_BYTES = 16_384;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type StreamOutcome = Extract<
  ChatOutcome,
  | 'completed'
  | 'action_pending'
  | 'action_clarification'
  | 'action_declined'
  | 'action_expired'
>;

export type StreamResult = { reply: string; outcome: StreamOutcome };

export class UnsupportedReceiptError extends Error {
  constructor() {
    super('Unsupported receipt event');
    this.name = 'UnsupportedReceiptError';
  }
}

function record(
  value: unknown,
  keys: readonly string[],
): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiFailure('malformed');
  }
  const result = value as Record<string, unknown>;
  const actual = Object.keys(result).sort();
  const expected = [...keys].sort();
  if (
    actual.length !== expected.length ||
    actual.some((key, index) => key !== expected[index])
  ) {
    throw new ApiFailure('malformed');
  }
  return result;
}

function integer(value: unknown, minimum: number, maximum: number): number {
  if (
    !Number.isSafeInteger(value) ||
    (value as number) < minimum ||
    (value as number) > maximum
  ) {
    throw new ApiFailure('malformed');
  }
  return value as number;
}

function boundedString(
  value: unknown,
  minimum: number,
  maximum: number,
): string {
  if (
    typeof value !== 'string' ||
    value.length < minimum ||
    value.length > maximum
  ) {
    throw new ApiFailure('malformed');
  }
  return value;
}

export class SseParser {
  private buffer = '';
  private bytes = 0;
  private sequence = 0;
  private reply = '';
  private terminal: StreamResult | 'error' | null = null;
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private pendingCarriageReturn = false;

  push(chunk: Uint8Array): void {
    this.bytes += chunk.byteLength;
    if (this.bytes > MAX_STREAM_BYTES) throw new ApiFailure('malformed');
    let text: string;
    try {
      text = this.decoder.decode(chunk, { stream: true });
    } catch {
      throw new ApiFailure('malformed');
    }
    if (this.pendingCarriageReturn) text = `\r${text}`;
    this.pendingCarriageReturn = text.endsWith('\r');
    if (this.pendingCarriageReturn) text = text.slice(0, -1);
    this.buffer += text.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    if (new TextEncoder().encode(this.buffer).byteLength > MAX_BUFFER_BYTES) {
      throw new ApiFailure('malformed');
    }
    let boundary = this.buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const frame = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      this.consume(frame);
      boundary = this.buffer.indexOf('\n\n');
    }
  }

  finish(): StreamResult {
    try {
      const finalText = this.decoder.decode();
      if (finalText) this.buffer += finalText;
    } catch {
      throw new ApiFailure('malformed');
    }
    if (this.pendingCarriageReturn) throw new ApiFailure('malformed');
    if (this.buffer.length !== 0 || this.terminal === null)
      throw new ApiFailure('malformed');
    if (this.terminal === 'error') throw new ApiFailure('dependency');
    return this.terminal;
  }

  private consume(frame: string): void {
    if (
      this.terminal !== null ||
      new TextEncoder().encode(frame).byteLength > MAX_EVENT_BYTES
    ) {
      throw new ApiFailure('malformed');
    }
    const lines = frame.split('\n');
    if (
      lines.length !== 2 ||
      !lines[0].startsWith('event: ') ||
      !lines[1].startsWith('data: ')
    ) {
      throw new ApiFailure('malformed');
    }
    const name = lines[0].slice(7);
    let data: unknown;
    try {
      data = parseStrictJson(lines[1].slice(6));
    } catch {
      throw new ApiFailure('malformed');
    }
    if (name === 'token') {
      const value = record(data, ['sequence', 'text']);
      this.next(integer(value.sequence, 1, 4));
      this.reply += boundedString(value.text, 1, 64);
      if (this.reply.length > 256) throw new ApiFailure('malformed');
      return;
    }
    if (name === 'action_receipt') {
      const value = record(data, ['sequence', 'receiptId', 'status']);
      this.next(integer(value.sequence, 1, 5));
      if (
        !UUID_PATTERN.test(boundedString(value.receiptId, 36, 36)) ||
        value.status !== 'SUCCEEDED'
      ) {
        throw new ApiFailure('malformed');
      }
      throw new UnsupportedReceiptError();
    }
    if (name === 'done') {
      const value = record(data, [
        'sequence',
        'conversationId',
        'traceId',
        'turnId',
        'outcome',
      ]);
      this.next(integer(value.sequence, 1, 5));
      for (const key of ['conversationId', 'traceId', 'turnId'] as const) {
        if (!UUID_PATTERN.test(boundedString(value[key], 36, 36)))
          throw new ApiFailure('malformed');
      }
      const allowed: StreamOutcome[] = [
        'completed',
        'action_pending',
        'action_clarification',
        'action_declined',
        'action_expired',
      ];
      if (
        typeof value.outcome !== 'string' ||
        !allowed.includes(value.outcome as StreamOutcome)
      ) {
        throw new ApiFailure('malformed');
      }
      this.terminal = {
        reply: this.reply,
        outcome: value.outcome as StreamOutcome,
      };
      return;
    }
    if (name === 'error') {
      const value = record(data, ['sequence', 'code']);
      this.next(integer(value.sequence, 1, 1));
      const allowed = [
        'attempt_budget_exhausted',
        'provider_unavailable',
        'stream_unavailable',
        'unsafe_output',
      ];
      if (typeof value.code !== 'string' || !allowed.includes(value.code)) {
        throw new ApiFailure('malformed');
      }
      this.terminal = 'error';
      return;
    }
    throw new ApiFailure('malformed');
  }

  private next(value: number): void {
    if (value !== this.sequence + 1) throw new ApiFailure('malformed');
    this.sequence = value;
  }
}
