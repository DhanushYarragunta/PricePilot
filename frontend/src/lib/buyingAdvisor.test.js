import test from 'node:test';
import assert from 'node:assert/strict';
import { formatDateOnly } from './time.js';
import { evidenceUrl, observedPoints, preferenceForm, preferencePayload } from './buyingAdvisor.js';

test('historical chart only includes actual valid points, never forecasts or missing prices', () => {
  const observations = [{price:100,capturedAt:'2026-01-01T00:00:00Z'}, {price:null,capturedAt:'2026-01-02'}, {price:-2,capturedAt:'2026-01-03'}, {price:1,capturedAt:'invalid'}];
  assert.deepEqual(observedPoints({observations, forecast:{price:20}}).map(p => p.value), [100]);
  assert.equal(observedPoints({}).length, 0);
});
test('sources reject script and credential URLs', () => {
  assert.equal(evidenceUrl('javascript:alert(1)'), null);
  assert.equal(evidenceUrl('https://user:secret@example.com'), null);
  assert.equal(evidenceUrl('https://example.com/specs'), 'https://example.com/specs');
});
test('edited preferences explicitly clear previous dates and retain hard limits', () => {
  const form = preferenceForm({ budget:3000,hardBudget:true,deadlineDate:'2026-12-15',deadlineKind:'HARD' });
  form.deadlineDate=''; form.deadlineKind='FLEXIBLE';
  const payload=preferencePayload(form);
  assert.equal(payload.deadlineDate,null);
  assert.equal(payload.deadlineKind,'FLEXIBLE');
  assert.equal(payload.budget,3000);
  assert.equal(payload.hardBudget,true);
});
test('invalid budget, savings and date order cannot be submitted', () => {
  assert.throws(() => preferencePayload({...preferenceForm(),budget:'abc'}));
  assert.throws(() => preferencePayload({...preferenceForm(),desiredSavingsPercent:101}));
  assert.throws(() => preferencePayload({...preferenceForm(),deadlineDate:'2026-01-01',earliestDate:'2026-02-01'}));
});

test('calendar deadlines retain their day and flexible dates can precede an earliest preference', () => {
  assert.equal(formatDateOnly('2026-12-15'), '15 Dec 2026');
  assert.equal(formatDateOnly('2026-02-30'), '—');
  assert.doesNotThrow(() => preferencePayload({...preferenceForm(), deadlineKind:'FLEXIBLE', deadlineDate:'2026-01-01',earliestDate:'2026-02-01'}));
  assert.throws(() => preferencePayload({...preferenceForm(),desiredSavingsPercent:100}));
});
