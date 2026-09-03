import { describe, expect, it } from 'vitest';

import { ApiFailure } from './client';
import { SseParser } from './sse';

const UUID = '00000000-0000-0000-0000-000000000001';
const encode = (value: string) => new TextEncoder().encode(value);
const token = 'event: token\ndata: {"sequence":1,"text":"Hello "}\n\n';
const done = `event: done\ndata: {"sequence":2,"conversationId":"${UUID}","traceId":"${UUID}","turnId":"${UUID}","outcome":"completed"}\n\n`;

describe('bounded SSE parser', () => {
  it('handles fragmented chunks, multiple frames, Unicode, LF and CRLF', () => {
    const parser = new SseParser();
    const bytes = encode(
      (token + done).replaceAll('\n', '\r\n').replace('Hello ', '你好 '),
    );
    for (let index = 0; index < bytes.length; index += 3)
      parser.push(bytes.slice(index, index + 3));
    expect(parser.finish()).toEqual({
      reply: '你好 ',
      outcome: 'completed',
      receiptId: null,
    });
  });

  it('rejects unknown, duplicate, out-of-order, malformed, missing-terminal, and post-terminal frames', () => {
    const cases = [
      'event: private\ndata: {"sequence":1}\n\n',
      token + token,
      'event: token\ndata: {"sequence":2,"text":"x"}\n\n',
      'event: token\ndata: []\n\n',
    ];
    for (const value of cases) {
      const parser = new SseParser();
      expect(() => parser.push(encode(value))).toThrow(ApiFailure);
    }
    const incomplete = new SseParser();
    incomplete.push(encode(token));
    expect(() => incomplete.finish()).toThrow(ApiFailure);
    const after = new SseParser();
    after.push(encode(token + done));
    expect(() => after.push(encode(token))).toThrow(ApiFailure);
  });

  it('rejects duplicate decoded keys inside event data', () => {
    const parser = new SseParser();
    expect(() =>
      parser.push(
        encode(
          'event: token\ndata: {"sequence":1,"text":"safe","\\u0074ext":"shadow"}\n\n',
        ),
      ),
    ).toThrow(ApiFailure);
  });

  it('carries one validated action_receipt through to the committed terminal', () => {
    const parser = new SseParser();
    parser.push(
      encode(
        `event: action_receipt\ndata: {"sequence":1,"receiptId":"${UUID}","status":"SUCCEEDED"}\n\n`,
      ),
    );
    parser.push(encode('event: token\ndata: {"sequence":2,"text":"done"}\n\n'));
    parser.push(
      encode(
        `event: done\ndata: {"sequence":3,"conversationId":"${UUID}","traceId":"${UUID}","turnId":"${UUID}","outcome":"action_completed"}\n\n`,
      ),
    );

    expect(parser.finish()).toEqual({
      reply: 'done',
      outcome: 'action_completed',
      receiptId: UUID,
    });
  });

  it('refuses a committed terminal that arrives without its receipt', () => {
    const parser = new SseParser();
    parser.push(encode('event: token\ndata: {"sequence":1,"text":"done"}\n\n'));
    expect(() =>
      parser.push(
        encode(
          `event: done\ndata: {"sequence":2,"conversationId":"${UUID}","traceId":"${UUID}","turnId":"${UUID}","outcome":"action_completed"}\n\n`,
        ),
      ),
    ).toThrow(ApiFailure);
  });

  it('refuses a receipt that arrives without a committed terminal', () => {
    const parser = new SseParser();
    parser.push(
      encode(
        `event: action_receipt\ndata: {"sequence":1,"receiptId":"${UUID}","status":"SUCCEEDED"}\n\n`,
      ),
    );
    parser.push(encode('event: token\ndata: {"sequence":2,"text":"done"}\n\n'));
    expect(() =>
      parser.push(
        encode(
          `event: done\ndata: {"sequence":3,"conversationId":"${UUID}","traceId":"${UUID}","turnId":"${UUID}","outcome":"completed"}\n\n`,
        ),
      ),
    ).toThrow(ApiFailure);
  });

  it('refuses a second receipt in one stream', () => {
    const parser = new SseParser();
    const frame = `event: action_receipt\ndata: {"sequence":1,"receiptId":"${UUID}","status":"SUCCEEDED"}\n\n`;
    parser.push(encode(frame));
    expect(() => parser.push(encode(frame))).toThrow(ApiFailure);
  });

  it('accepts a rejected terminal without inventing a receipt', () => {
    const parser = new SseParser();
    parser.push(
      encode(
        'event: token\ndata: {"sequence":1,"text":"Commerce rejected the action."}\n\n',
      ),
    );
    parser.push(
      encode(
        `event: done\ndata: {"sequence":2,"conversationId":"${UUID}","traceId":"${UUID}","turnId":"${UUID}","outcome":"action_rejected"}\n\n`,
      ),
    );

    expect(parser.finish()).toEqual({
      reply: 'Commerce rejected the action.',
      outcome: 'action_rejected',
      receiptId: null,
    });
  });

  it('allows exactly one public error terminal and maps it to a bounded dependency failure', () => {
    const parser = new SseParser();
    parser.push(
      encode('event: error\ndata: {"sequence":1,"code":"unsafe_output"}\n\n'),
    );
    expect(() => parser.finish()).toThrowError(
      expect.objectContaining({ kind: 'dependency' }),
    );
  });
});
