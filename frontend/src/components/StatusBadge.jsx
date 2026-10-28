/**
 * A listing's scraping health, as a badge.
 *
 * ── Why this is shown to users at all ──
 * Listing status is operational detail, and the instinct is to keep it in an admin view. But the
 * main way a price tracker misleads people is by showing a number that stopped updating three weeks
 * ago, and the only honest fix is to say so where the number appears. "Amazon.in has blocked our
 * last three checks, next attempt in four hours" is a worse-looking interface and a much better
 * one — it lets someone decide to check the page themselves instead of trusting a stale figure.
 */
export default function StatusBadge({ status, consecutiveFailures = 0, note }) {
  const { className, label, title } = describe(status, consecutiveFailures);
  return (
    <span className={'badge ' + className} title={note || title}>
      {label}
    </span>
  );
}

function describe(status, failures) {
  switch (status) {
    case 'ACTIVE':
      // An active listing with recent failures is the interesting case: still being checked, but
      // the last few did not work. Silently calling that "active" is what produces a stale price
      // with a healthy badge beside it.
      if (failures > 0) {
        return {
          className: 'badge-warn',
          label: failures === 1 ? 'Last check failed' : failures + ' checks failed',
          title:
            'Still being checked, but the last ' +
            failures +
            ' attempt' +
            (failures === 1 ? '' : 's') +
            ' did not return a price. The interval backs off after each failure.',
        };
      }
      return {
        className: 'badge-ok',
        label: 'Checking',
        title: 'Being checked on schedule, and the last check worked.',
      };
    case 'PAUSED':
      return {
        className: '',
        label: 'Paused',
        title: 'Not being checked. Prices shown are from the last successful check.',
      };
    case 'BLOCKED':
      return {
        className: 'badge-bad',
        label: 'Blocked by retailer',
        title:
          'This retailer is currently refusing our requests. Checks back off hard rather than ' +
          'hammering the site, and resume on their own — the price shown is the last one read.',
      };
    case 'DEAD':
      return {
        className: 'badge-bad',
        label: 'Retired',
        title:
          'Checked too many times without success, so it has been retired to avoid pointless ' +
          'requests. Usually a removed product page or a changed URL.',
      };
    default:
      return { className: '', label: status || 'Unknown', title: '' };
  }
}
