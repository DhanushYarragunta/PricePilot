import { useState } from 'react';
import { api } from '../lib/api.js';
import { useAsync, useAction } from '../lib/useAsync.js';
import { useToast } from '../lib/toast.jsx';
import Field from '../components/Field.jsx';
import Working from '../components/Working.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { formatInteger, formatPercent } from '../lib/money.js';
import { formatAbsolute, formatInterval, formatLatency, formatRelative, formatUntil } from '../lib/time.js';

/**
 * The repair bench.
 *
 * This page exists because of one scenario: the app is live, a recruiter has the link, and
 * Flipkart has changed its markup overnight. Without somewhere to look, the only response is to
 * read server logs over SSH and guess; without somewhere to act, the only fix is a redeploy.
 *
 * So the four things needed to get out of that hole are here, in the order they are actually used:
 * confirm the loop is still turning, find out which host and which strategy broke, edit the rules
 * file on the box and load it without a restart, and bring back the listings that retired themselves
 * while the selector was wrong.
 */
export default function AdminPage() {
  const toast = useToast();
  const scheduler = useAsync((options) => api.schedulerState(options), []);
  const diagnostics = useAsync((options) => api.diagnostics(7, options), []);

  /*
   * The reload result is held here rather than inside the panel, and `useAction` deliberately does
   * not keep it. An action hook that remembered its last return value would have to decide when to
   * forget it, and the right answer differs per caller — a form wants it cleared on the next edit,
   * this panel wants it to persist until the page is left.
   */
  const [rulesResult, setRulesResult] = useState(null);

  const reload = useAction(async () => {
    const result = await api.reloadRules();
    setRulesResult(result);
    toast.notify(result.summary, 'Rules reloaded');
    diagnostics.reload();
    return result;
  });

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-row">
          <div>
            <h1>Operations</h1>
            <p className="page-sub">
              For when a retailer changes its pages and prices stop being read. Everything here
              works on the running process — none of it needs a restart or a redeploy.
            </p>
          </div>
          {scheduler.reloading || diagnostics.reloading ? (
            <Working label="Refreshing" inline />
          ) : null}
        </div>
      </header>

      <div className="admin-grid">
        <Scheduler state={scheduler} />
        <RulesReload action={reload} result={rulesResult} />
      </div>

      <ListingControls
        onChanged={() => {
          scheduler.reload();
          diagnostics.reload();
        }}
      />

      <Diagnostics source={diagnostics} />
    </div>
  );
}

/** Is the loop turning? The first question, and the cheapest to answer. */
function Scheduler({ state }) {
  const s = state.data;

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">Scheduler</h2>
        <button type="button" className="btn btn-sm btn-quiet" onClick={state.reload}>
          Refresh
        </button>
      </header>
      <div className="panel-body stack">
        <ErrorNotice error={state.error} what="Scheduler state unavailable" onRetry={state.reload} />
        {state.loading ? <Working label="Reading scheduler state" /> : null}

        {s ? (
          <>
            {/*
             * The stopped case is called out rather than left as a word in a table. A stopped
             * scheduler means every price on the site is frozen at whatever it was, and the symptom
             * — stale prices — looks identical to a broken selector. Separating those two causes is
             * most of the diagnostic value of this panel.
             */}
            {s.enabled ? null : (
              <Notice tone="bad" title="Automatic checks are off.">
                Nothing is being re-read on a schedule, so every price shown on the site is as old
                as its last manual check. This is the{' '}
                <span className="machine">pricepilot.scheduler.enabled</span> setting, not a crash.
              </Notice>
            )}

            <div className="side-rows">
              <Row label="State" value={s.enabled ? 'Running' : 'Stopped'} />
              <Row label="Ticks since start" value={formatInteger(s.ticks)} />
              <Row
                label="Last tick"
                value={formatRelative(s.lastTickAt)}
                title={formatAbsolute(s.lastTickAt)}
              />
              <Row label="Fetches in flight" value={formatInteger(s.inFlight)} />
              <Row label="Active listings" value={formatInteger(s.activeListings)} />
              <Row label="Paused" value={formatInteger(s.pausedListings)} />
              <Row label="Blocked" value={formatInteger(s.blockedListings)} />
              <Row label="Retired" value={formatInteger(s.deadListings)} />
            </div>

            {s.enabled && s.ticks > 0 && isStale(s.lastTickAt) ? (
              <Notice tone="bad" title="The last tick was a while ago.">
                The scheduler reports itself enabled but has not woken recently. That usually means a
                fetch is wedged past its timeout, or the pool is exhausted — check the in-flight
                count above and the container logs.
              </Notice>
            ) : null}
          </>
        ) : null}
      </div>
    </section>
  );
}

/** Edit the YAML on the box, press this, done. */
function RulesReload({ action, result }) {
  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">Extraction rules</h2>
      </header>
      <div className="panel-body stack">
        <p className="small muted">
          The per-retailer selectors live in a YAML file on disk, outside the jar. When a retailer
          changes its markup the fix is to edit that file over SSH and press this button — the new
          rules take effect on the next check, with no restart, no rebuild and no downtime.
        </p>

        <div className="row">
          <button type="button" className="btn" onClick={() => action.run().catch(() => {})} disabled={action.busy}>
            {action.busy ? 'Loading from disk…' : 'Reload rules from disk'}
          </button>
        </div>

        <ErrorNotice error={action.error} what="The rules were not reloaded" />

        {result ? (
          <div className="side-rows">
            <Row label="Loaded from" value={<span className="machine">{result.rulesPath}</span>} />
            <Row label="Host rules" value={formatInteger(result.hostRules)} />
            <Row label="Retailers" value={formatInteger(result.retailers)} />
          </div>
        ) : null}

        {result ? <p className="small">{result.summary}</p> : null}
      </div>
    </section>
  );
}

/**
 * Bring a listing back, or take one out of rotation.
 *
 * Driven by a listing id typed in by hand, which is deliberate: the ids come from the diagnostics
 * table below or from the check log on a product page, so using this requires having looked at the
 * evidence first. A dropdown of every listing would be friendlier and would invite reviving things
 * at random.
 */
function ListingControls({ onChanged }) {
  const toast = useToast();
  const [listingId, setListingId] = useState('');
  const [last, setLast] = useState(null);

  const revive = useAction(async () => {
    const state = await api.reviveListing(listingId.trim());
    setLast(state);
    onChanged();
    toast.notify(
      state.retailer + ' is back to ' + state.status.toLowerCase() + ', next check ' +
        formatUntil(state.nextCheckAt) + '.',
      'Revived'
    );
    return state;
  });

  const pause = useAction(async () => {
    const state = await api.pauseListing(listingId.trim());
    setLast(state);
    onChanged();
    toast.notify(state.retailer + ' will not be checked until it is revived.', 'Paused');
    return state;
  });

  const busy = revive.busy || pause.busy;
  const valid = /^[0-9]+$/.test(listingId.trim());

  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">Listing repair</h2>
      </header>
      <div className="panel-body stack">
        <p className="small muted">
          A listing that fails repeatedly pauses itself, and one that keeps returning 404 retires
          itself, so a broken selector quietly empties the schedule. Once the rules are fixed, those
          listings need bringing back — they will not return on their own, because &ldquo;it failed
          ten times&rdquo; is not information that expires.
        </p>

        <div className="admin-actions">
          <div className="admin-id-input">
            <Field
              label="Listing id"
              inputMode="numeric"
              value={listingId}
              onChange={setListingId}
              placeholder="e.g. 42"
              hint="From the diagnostics below, or the check log on a product page."
            />
          </div>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => revive.run().catch(() => {})}
            disabled={busy || !valid}
          >
            {revive.busy ? 'Reviving…' : 'Revive'}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-quiet"
            onClick={() => pause.run().catch(() => {})}
            disabled={busy || !valid}
          >
            {pause.busy ? 'Pausing…' : 'Pause'}
          </button>
        </div>

        <ErrorNotice error={revive.error || pause.error} what="That listing was not changed" />

        {last ? (
          <div className="side-rows">
            <Row
              label="Listing"
              value={
                <>
                  {last.retailer} <span className="machine">#{last.listingId}</span>
                </>
              }
            />
            <Row
              label="Status"
              value={
                <>
                  <span className="faint">{last.previousStatus} → </span>
                  <StatusBadge
                    status={last.status}
                    consecutiveFailures={last.consecutiveFailures}
                  />
                </>
              }
            />
            <Row label="Failure count" value={formatInteger(last.consecutiveFailures)} />
            <Row label="Interval" value={formatInterval(last.checkIntervalMinutes)} />
            <Row
              label="Next check"
              value={formatUntil(last.nextCheckAt)}
              title={formatAbsolute(last.nextCheckAt)}
            />
            <Row
              label="URL"
              value={
                <a href={last.url} target="_blank" rel="noopener noreferrer" className="machine">
                  open ↗
                </a>
              }
            />
          </div>
        ) : null}
      </div>
    </section>
  );
}

/**
 * The last seven days, narrowed to what identifies a break.
 *
 * The public metrics page shows the same OpsMetrics shape as a record of performance. This shows it
 * as a fault-finding instrument, over a short window, sorted so the worst host is the first row —
 * because when something is broken the question is never "how are we doing" but "which one".
 */
function Diagnostics({ source }) {
  const m = source.data;

  const hosts = m ? [...m.hosts].sort((a, b) => a.successRatePercent - b.successRatePercent) : [];
  const strategies = m
    ? [...m.strategies].sort((a, b) => a.successRatePercent - b.successRatePercent)
    : [];

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">Last 7 days, worst first</h2>
        <button type="button" className="btn btn-sm btn-quiet" onClick={source.reload}>
          Refresh
        </button>
      </header>

      <div className="panel-body">
        <ErrorNotice
          error={source.error}
          what="Diagnostics unavailable"
          onRetry={source.reload}
        />
        {source.loading ? <Working label="Computing diagnostics" /> : null}
      </div>

      {m ? (
        <>
          <div className="panel-body panel-body-flush">
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Host</th>
                    <th className="table-num">Attempts</th>
                    <th className="table-num">Succeeded</th>
                    <th className="table-num">Rate</th>
                  </tr>
                </thead>
                <tbody>
                  {hosts.map((host) => (
                    <tr key={host.host}>
                      <td className="machine">{host.host}</td>
                      <td className="table-num">{formatInteger(host.attempts)}</td>
                      <td className="table-num">{formatInteger(host.successes)}</td>
                      <td className="table-num">
                        {formatPercent(host.successRatePercent, { sign: false })}
                      </td>
                    </tr>
                  ))}
                  {hosts.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="faint">
                        Nothing fetched in the last 7 days.
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel-body panel-body-flush">
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Strategy</th>
                    <th className="table-num">Attempts</th>
                    <th className="table-num">Rate</th>
                    <th className="table-num">Average time</th>
                  </tr>
                </thead>
                <tbody>
                  {strategies.map((strategy) => (
                    <tr key={strategy.strategy}>
                      <td className="machine">{strategy.strategy}</td>
                      <td className="table-num">{formatInteger(strategy.attempts)}</td>
                      <td className="table-num">
                        {formatPercent(strategy.successRatePercent, { sign: false })}
                      </td>
                      <td className="table-num">{formatLatency(strategy.averageLatencyMs)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel-body panel-body-flush">
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Outcome</th>
                    <th className="table-num">Count</th>
                    <th className="table-num">Share</th>
                  </tr>
                </thead>
                <tbody>
                  {m.outcomes.map((outcome) => (
                    <tr key={outcome.outcome}>
                      <td className="machine">{outcome.outcome}</td>
                      <td className="table-num">{formatInteger(outcome.count)}</td>
                      <td className="table-num">
                        {formatPercent(outcome.sharePercent, { sign: false })}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="panel-body">
            <p className="small muted">
              {/*
               * The reading guide, because the outcome names are the actual diagnosis and knowing
               * what each one implies is the difference between fixing this in ten minutes and
               * guessing for an hour.
               */}
              A host at a low rate with mostly{' '}
              <span className="machine">BLOCKED</span> outcomes is refusing the fetcher, which is a
              politeness or proxy problem. A host answering fine but showing{' '}
              <span className="machine">NO_PRICE_FOUND</span> has changed its markup, which is a
              rules problem — fix the YAML and reload it above. Mostly{' '}
              <span className="machine">TIMEOUT</span> is usually the network on the box, not the
              retailer.
            </p>
          </div>
        </>
      ) : null}
    </section>
  );
}

function Row({ label, value, title }) {
  return (
    <div className="side-row" title={title}>
      <span className="side-row-label">{label}</span>
      <span className="side-row-value">{value}</span>
    </div>
  );
}

/** More than four minutes since the last tick, against a tick interval measured in seconds. */
function isStale(lastTickAt) {
  if (!lastTickAt) {
    return false;
  }
  const at = new Date(lastTickAt).getTime();
  if (Number.isNaN(at)) {
    return false;
  }
  return Date.now() - at > 4 * 60 * 1000;
}
