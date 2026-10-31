import test from 'node:test';
import assert from 'node:assert/strict';
import { assistantHistory, hasSourcedPrice, parseShoppingBudget, safeRetailerUrl } from './shoppingAssistant.js';

test('retailer links allow ordinary product URLs but reject executable and credential-bearing URLs', () => {
  assert.equal(safeRetailerUrl('https://www.amazon.in/dp/B07CR28HLL'), 'https://www.amazon.in/dp/B07CR28HLL');
  for (const url of ['javascript:alert(1)', 'data:text/html,test', '//example.com/product', '/product', 'https://name:password@example.com/product', null]) {
    assert.equal(safeRetailerUrl(url), null);
  }
});

test('a price needs a real capture timestamp, positive amount, and supported currency', () => {
  const candidate = { price: '1609.00', currency: 'INR', capturedAt: '2026-10-31T02:00:00Z' };
  assert.equal(hasSourcedPrice(candidate), true);
  for (const override of [{ capturedAt: null }, { capturedAt: 'unknown' }, { price: null }, { price: 0 }, { price: 'NaN' }, { currency: 'USD' }]) {
    assert.equal(hasSourcedPrice({ ...candidate, ...override }), false);
  }
});

test('budgets are optional and reject negative, fractional-cent, exponential and oversized values', () => {
  assert.equal(parseShoppingBudget('  '), null);
  assert.equal(parseShoppingBudget('3000.50'), 3000.5);
  assert.equal(parseShoppingBudget('5000000'), 5000000);
  for (const budget of ['0', '0.50', '-100', '50.001', '1e3', '5000001', 'Infinity', 'three thousand']) {
    assert.throws(() => parseShoppingBudget(budget));
  }
});

test('chat context drops system roles, attached cards, old turns, and oversized text', () => {
  const messages = [
    { role: 'system', content: 'Untrusted instructions' },
    ...Array.from({ length: 12 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', content: String(index), candidates: [{ price: 99 }] })),
    { role: 'assistant', content: 'a'.repeat(2500), model: 'example-model' },
  ];
  const history = assistantHistory(messages);
  assert.equal(history.length, 8);
  assert.deepEqual(history[0], { role: 'assistant', content: '5' });
  assert.deepEqual(Object.keys(history[7]), ['role', 'content']);
  assert.equal(history[7].content.length, 2000);
});
