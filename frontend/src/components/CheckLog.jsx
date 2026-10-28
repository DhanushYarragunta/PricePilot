import { formatClock, formatLatency, formatRelative } from '../lib/time.js';

/**
 * Every check we made, as a table.
 *
 * This panel is the reason to trust the rest of the app. The claim everywhere else is that prices
 * are read from each retailer's own page by this server — and a claim like that is worth nothing
 * unless it can be checked. So each row is one `scrape_attempt` row as recorded: when the request
 * went out, which host it went to, how it ended, which extraction strategy read the price, the HTTP
 * status, and how long it took.
 *
 * ── Why the failures are not filtered out ──
 * A log that only shows successes is advertising. The failure shape is the useful part: a column of
 * "Blocked by the retailer" against one host says the politeness settings for that host need
 * attention, and a run of "No price on the page" after months of success says the retailer changed
 * its markup. Both are visible here before anyone complains that a price looks wrong.
 *
 * ── Why `SKIPPED_FRESH` rows are shown too ──
 * They are proof of the caching that keeps the request count low. A check that reused a price read
 * forty seconds earlier sent nothing, and showing it as a skip rather than hiding it is what makes
 * the gap between "checks" and "requests" legible — the same gap the metrics page reports as the
 * deduplication share.
 */
export default function CheckLog({ checks = [] }) {
  if (checks.length === 0) {
    return (
      <p className="checklog-note">
        No checks recorded yet. The first one runs as soon as this product is added, and the log
        fills from there.
      </p>
    );
  }

  return (
    <>
      <div className="table-scroll">
        <table className="checklog">
          <thead>
            <tr>
              <th aria-label="Outcome" />
              <th>When</th>
              <th>Retailer</th>
              <th>Outcome</th>
              <th>Read by</th>
              <th>HTTP</th>
              <th className="table-num">Took</th>
              <th>Triggered by</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {checks.map((check, index) => (
              <tr key={check.at + '-' + check.host + '-' + index}>
                <td className={'checklog-mark ' + markClass(check.outcome)} />
                <td title={formatRelative(check.at)}>{formatClock(check.at)}</td>
                <td>{check.retailer}</td>
                <td>{check.outcomeLabel}</td>
                {/*
                 * The strategy name is a machine string from the extraction chain — JSON_LD,
                 * MICRODATA, OPEN_GRAPH, EMBEDDED_JSON, CSS_RULE. It is shown raw, in the
                 * machine-string style, because translating it into prose would lose the thing it is
                 * useful for: matching a failure here against a rule in domain-rules.yml.
                 */}
                <td className="machine">{check.strategy || '—'}</td>
                <td className="machine">{check.httpStatus ?? '—'}</td>
                <td className="table-num">{formatLatency(check.latencyMs)}</td>
                <td className="machine">{triggerLabel(check.trigger)}</td>
                <td className="checklog-error" title={check.error || ''}>
                  {check.error || ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="checklog-note">
        Every row is one request this server made, or one it deliberately did not make because a
        price read moments earlier was still current.
      </p>
    </>
  );
}

function markClass(outcome) {
  if (outcome === 'SUCCESS') {
    return 'checklog-mark-ok';
  }
  if (outcome === 'SKIPPED_FRESH') {
    return 'checklog-mark-skip';
  }
  return 'checklog-mark-bad';
}

/**
 * The trigger, in a word: who caused this request.
 *
 * The cases are the names of `TriggerSource` on the server, so this switch has to track that enum
 * exactly — hence the `default` returning the raw value lowercased rather than "unknown". A new
 * trigger added on the backend then shows up as itself in the log instead of disappearing, which is
 * the difference between a visible gap and a silent one.
 */
function triggerLabel(trigger) {
  switch (trigger) {
    case 'SCHEDULER':
      return 'schedule';
    case 'MANUAL':
      return 'you';
    case 'ONBOARD':
      return 'first check';
    case 'DISCOVERY':
      return 'retailer search';
    default:
      return trigger ? String(trigger).toLowerCase() : '—';
  }
}
