/** Keep unknown observations and inferred forecasts out of the historical chart. */
export function observedPoints(series) {
  return (series?.observations || []).filter((point) =>
    point.price !== null && point.price !== undefined && Number(point.price) > 0
    && Number.isFinite(Number(point.price)) && Number.isFinite(Date.parse(point.capturedAt))
  ).map((point) => ({ ...point, value: Number(point.price), time: Date.parse(point.capturedAt) }));
}

export function evidenceUrl(value) {
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function preferenceForm(preferences = {}) {
  return {
    listingId: preferences.listingId ?? '', budget: preferences.budget ?? '',
    hardBudget: preferences.hardBudget ?? true, deadlineDate: preferences.deadlineDate ?? '',
    deadlineKind: preferences.deadlineKind ?? 'HARD', earliestDate: preferences.earliestDate ?? '',
    targetPrice: preferences.targetPrice ?? '', desiredSavingsPercent: preferences.desiredSavingsPercent ?? '',
    allowAlternatives: preferences.allowAlternatives ?? true, urgency: preferences.urgency ?? '',
    reason: preferences.reason ?? '',
  };
}

export function preferencePayload(form) {
  const payload = { ...form };
  for (const key of ['budget', 'targetPrice', 'desiredSavingsPercent', 'listingId']) {
    if (form[key] === '' || form[key] === null) payload[key] = null;
    else {
      const value = Number(form[key]);
      if (!Number.isFinite(value) || value <= 0 || (key === 'desiredSavingsPercent' && value > 99.99)) {
        throw new Error('Enter a positive ' + key.replace(/([A-Z])/g, ' $1').toLowerCase() + '.');
      }
      payload[key] = value;
    }
  }
  for (const key of ['deadlineDate', 'earliestDate', 'urgency', 'reason']) payload[key] = form[key] || null;
  if (payload.deadlineKind === 'HARD' && payload.earliestDate && payload.deadlineDate && payload.earliestDate > payload.deadlineDate) {
    throw new Error('The earliest purchase date must be on or before your deadline.');
  }
  return payload;
}

export const outcomeLabel = (value) => ({
  BUY_NOW: 'Buy now', WAIT_UNTIL_DATE: 'Wait and reassess', BUY_AT_TARGET_PRICE: 'Watch for your target price',
  COMPARE_ALTERNATIVES: 'Compare alternatives', INSUFFICIENT_EVIDENCE: 'More evidence needed',
}[value] || 'Start your buying plan');
