export const ASSISTANT_MESSAGE_LIMIT = 2000;
export const ASSISTANT_REQUIREMENTS_LIMIT = 1200;

/** Only conversational text is sent back; cards and their prices stay server-sourced. */
export function assistantHistory(messages) {
  return messages
    .filter((message) => ['user', 'assistant'].includes(message.role) && typeof message.content === 'string')
    .slice(-8)
    .map(({ role, content }) => ({ role, content: content.slice(0, ASSISTANT_MESSAGE_LIMIT) }));
}

export function parseShoppingBudget(raw) {
  const text = raw.trim();
  if (text === '') return null;
  const value = Number(text);
  if (!/^\d+(?:\.\d{1,2})?$/.test(text) || !Number.isFinite(value) || value < 1 || value > 5000000) {
    throw new Error('Enter an amount from ₹1 to ₹50,00,000 with up to two decimal places.');
  }
  return value;
}

/** Provider output is never allowed to create an executable or credential-bearing link. */
export function safeRetailerUrl(value) {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || !url.hostname) {
      return null;
    }
    return url.href;
  } catch {
    return null;
  }
}

export function hasSourcedPrice(candidate) {
  return candidate.price !== null && candidate.price !== undefined
    && Number.isFinite(Number(candidate.price)) && Number(candidate.price) > 0
    && candidate.currency === 'INR' && typeof candidate.capturedAt === 'string'
    && Number.isFinite(Date.parse(candidate.capturedAt));
}
