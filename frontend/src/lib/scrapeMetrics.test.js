import test from 'node:test';
import assert from 'node:assert/strict';
import { summarizeScraping } from './scrapeMetrics.js';

test('cache and duplicate reuse never inflate extraction success or hide pending requests', () => {
  const result = summarizeScraping({ retailerRequests: 10, successfulRequests: 6, failedRequests: 2,
    cacheHits: 1000, duplicateRequestsSkipped: 500, scheduledChecksDue: 20,
    scheduledChecksCompletedOnTime: 10, scheduledChecksCompleted: 15 });
  assert.equal(result.completed, 8);
  assert.equal(result.pending, 2);
  assert.equal(result.successPercent, 75);
  assert.equal(result.onTimePercent, 50);
});

test('no observations or due checks cannot be reported as perfect performance', () => {
  assert.equal(summarizeScraping(null), null);
  const result = summarizeScraping({ retailerRequests: 0, successfulRequests: 0, failedRequests: 0,
    scheduledChecksDue: 0, scheduledChecksCompletedOnTime: 0 });
  assert.equal(result.successPercent, null);
  assert.equal(result.onTimePercent, null);
});
