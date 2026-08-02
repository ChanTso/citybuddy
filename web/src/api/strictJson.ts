const MAX_DEPTH = 64;

function syntaxError(): never {
  throw new SyntaxError('Invalid JSON');
}

function skipWhitespace(text: string, start: number): number {
  let index = start;
  while (
    index < text.length &&
    (text[index] === ' ' ||
      text[index] === '\n' ||
      text[index] === '\r' ||
      text[index] === '\t')
  ) {
    index += 1;
  }
  return index;
}

function scanString(text: string, start: number): number {
  if (text[start] !== '"') return syntaxError();
  for (let index = start + 1; index < text.length; index += 1) {
    if (text[index] === '"') return index + 1;
    if (text[index] === '\\') index += 1;
  }
  return syntaxError();
}

function decodedKey(text: string, start: number, end: number): string {
  try {
    const value: unknown = JSON.parse(text.slice(start, end));
    if (typeof value !== 'string') return syntaxError();
    return value;
  } catch {
    return syntaxError();
  }
}

function scanValue(text: string, start: number, depth: number): number {
  if (depth > MAX_DEPTH) return syntaxError();
  const index = skipWhitespace(text, start);
  const first = text[index];
  if (first === '"') return scanString(text, index);
  if (first === '{') return scanObject(text, index, depth + 1);
  if (first === '[') return scanArray(text, index, depth + 1);
  for (const literal of ['true', 'false', 'null']) {
    if (text.startsWith(literal, index)) return index + literal.length;
  }
  const number = text
    .slice(index)
    .match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/u);
  if (number) return index + number[0].length;
  return syntaxError();
}

function scanObject(text: string, start: number, depth: number): number {
  const keys = new Set<string>();
  let index = skipWhitespace(text, start + 1);
  if (text[index] === '}') return index + 1;
  while (index < text.length) {
    const keyStart = index;
    const keyEnd = scanString(text, keyStart);
    const key = decodedKey(text, keyStart, keyEnd);
    if (keys.has(key)) return syntaxError();
    keys.add(key);
    index = skipWhitespace(text, keyEnd);
    if (text[index] !== ':') return syntaxError();
    index = skipWhitespace(text, scanValue(text, index + 1, depth));
    if (text[index] === '}') return index + 1;
    if (text[index] !== ',') return syntaxError();
    index = skipWhitespace(text, index + 1);
  }
  return syntaxError();
}

function scanArray(text: string, start: number, depth: number): number {
  let index = skipWhitespace(text, start + 1);
  if (text[index] === ']') return index + 1;
  while (index < text.length) {
    index = skipWhitespace(text, scanValue(text, index, depth));
    if (text[index] === ']') return index + 1;
    if (text[index] !== ',') return syntaxError();
    index = skipWhitespace(text, index + 1);
  }
  return syntaxError();
}

export function parseStrictJson(text: string): unknown {
  const end = skipWhitespace(text, scanValue(text, 0, 0));
  if (end !== text.length) return syntaxError();
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return syntaxError();
  }
}
