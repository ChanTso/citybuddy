import { describe, expect, it } from 'vitest';

import { parseStrictJson } from './strictJson';

describe('strict JSON boundary', () => {
  it('accepts nested JSON with distinct keys', () => {
    expect(
      parseStrictJson('{"a":1,"nested":{"a":2},"list":[true,null]}'),
    ).toEqual({ a: 1, nested: { a: 2 }, list: [true, null] });
  });

  it.each(['{"a":1,"a":2}', '{"a":1,"\\u0061":2}', '{"nested":{"x":1,"x":2}}'])(
    'rejects duplicate decoded object keys in %s',
    (text) => {
      expect(() => parseStrictJson(text)).toThrow(SyntaxError);
    },
  );

  it.each(['', '{"a":}', '[1,]', '{"a":1} trailing'])(
    'rejects malformed JSON in %s',
    (text) => {
      expect(() => parseStrictJson(text)).toThrow(SyntaxError);
    },
  );

  it('rejects structures beyond the bounded nesting depth', () => {
    const text = `${'['.repeat(66)}null${']'.repeat(66)}`;
    expect(() => parseStrictJson(text)).toThrow(SyntaxError);
  });
});
