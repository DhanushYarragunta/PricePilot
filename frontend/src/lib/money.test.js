import test from 'node:test';
import assert from 'node:assert/strict';
import { formatUsdEstimate, summarizeSpend } from './money.js';

test('API cost formatting distinguishes explicitly recorded zero from an unknown rate', () => {
  assert.equal(formatUsdEstimate(0), '$0.0000');
  assert.equal(formatUsdEstimate('0.0012'), '$0.0012');
  for (const cost of [null, undefined, '', 'unknown', -1]) {
    assert.equal(formatUsdEstimate(cost), 'Not estimated');
  }
});

test('API totals include only known estimates and disclose missing costs', () => {
  assert.deepEqual(summarizeSpend([{ costUsd: '1.25' }, { costUsd: null }, { costUsd: 0 }]), { total: 1.25, hasUnknown: true });
  assert.deepEqual(summarizeSpend([{ costUsd: null }]), { total: null, hasUnknown: true });
  assert.deepEqual(summarizeSpend([{ costUsd: 0 }]), { total: 0, hasUnknown: false });
});
