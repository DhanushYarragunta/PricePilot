/** Ratios have explicit denominators; no due work or completed requests means no rate yet. */
export function summarizeScraping(metrics) {
  if (!metrics) return null;
  const completed = metrics.successfulRequests + metrics.failedRequests;
  return {
    completed,
    pending: Math.max(0, metrics.retailerRequests - completed),
    successPercent: completed > 0 ? metrics.successfulRequests * 100 / completed : null,
    onTimePercent: metrics.scheduledChecksDue > 0
      ? metrics.scheduledChecksCompletedOnTime * 100 / metrics.scheduledChecksDue : null,
  };
}
