import { useState } from 'react';
import { api } from '../lib/api.js';
import { useAsync } from '../lib/useAsync.js';
import Working from '../components/Working.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { formatInteger, formatPercent, formatRate, formatUsdEstimate, summarizeSpend } from '../lib/money.js';
import { formatAbsolute, formatInterval, formatLatency, formatRelative } from '../lib/time.js';
import { summarizeScraping } from '../lib/scrapeMetrics.js';

const WINDOWS = [7, 30, 90];

/**
 * Public aggregate telemetry with explicit windows and denominators. Process counters describe
 * actual BrowserAct submissions; historical database check rows remain labeled separately.
 */
export default function MetricsPage() {
  const [windowDays, setWindowDays] = useState(30);
  const metrics = useAsync((options) => api.metrics(windowDays, options), [windowDays]);
  const m = metrics.data;

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-row">
          <div>
            <h1>How this is actually performing</h1>
            <p className="page-sub">
              Verified price updates, shared requests and scheduling results. Each section states
              its measurement window and what its counters include.
            </p>
          </div>
          <div className="stack">
            <div className="window-switch" role="group" aria-label="Measurement window">
              {WINDOWS.map((days) => (
                <button
                  key={days}
                  type="button"
                  aria-pressed={windowDays === days}
                  onClick={() => setWindowDays(days)}
                >
                  {days} days
                </button>
              ))}
            </div>
            {metrics.reloading ? <Working label="Recomputing" inline /> : null}
          </div>
        </div>
      </header>

      <ErrorNotice
        error={metrics.error}
        what="The metrics could not be computed"
        onRetry={metrics.reload}
      />

      {metrics.loading ? <Working label="Computing the metrics" /> : null}

      {m ? (
        <>
          <Headlines m={m} />

          <p className="small faint" style={{ marginBottom: 'var(--s-6)' }}>
            Measured over the {m.windowDays} days ending {formatAbsolute(m.generatedAt)}.
          </p>

          {m.optimization ? <Optimization metrics={m.optimization} /> : null}
          <Extraction m={m} />
          <Load m={m} />
          {m.verdicts.analysed > 0 ? <VerdictBreakdown m={m} /> : null}
          <Reliability m={m} />
          <Scale m={m} />
          <Spend m={m} />
        </>
      ) : null}
    </div>
  );
}

/**
 * The three figures the project is judged on.
 *
 * Each one carries its basis underneath, in the smallest type on the page but present: the
 * percentage is the claim and the basis is the receipt.
 */
function Headlines({ m }) {
  const current = summarizeScraping(m.optimization);
  return (
    <div className="metric-grid">
      <div className="headline-metric">
        <div className="headline-metric-value">
          {current?.successPercent == null
            ? '—'
            : formatPercent(current.successPercent, { sign: false })}
        </div>
        <div className="headline-metric-label">of completed BrowserAct checks verified a price</div>
        <p className="headline-metric-basis">
          {formatInteger(m.optimization?.successfulRequests || 0)} verified results from{' '}
          {formatInteger(current?.completed || 0)} completed workflows since this backend started.
          Cache hits, joined requests and pending workflows are excluded.
        </p>
      </div>

      <div className="headline-metric">
        <div className="headline-metric-value">
          {m.verdicts.analysed === 0
            ? '—'
            : formatPercent(m.verdicts.favourablePercent, { sign: false })}
        </div>
        <div className="headline-metric-label">of prices judged good value</div>
        <p className="headline-metric-basis">
          {formatInteger(m.verdicts.favourable)} favourable out of{' '}
          {formatInteger(m.verdicts.analysed)} AI analyses. A verdict counts as favourable
          only if the model returned good value or better — the server decides that, not this page.
        </p>
      </div>

      <div className="headline-metric">
        <div className="headline-metric-value">
          {current?.onTimePercent == null
            ? '—'
            : formatPercent(current.onTimePercent, { sign: false })}
        </div>
        <div className="headline-metric-label">of observed due checks completed on time</div>
        <p className="headline-metric-basis">
          {formatInteger(m.optimization?.scheduledChecksCompletedOnTime || 0)} on-time completions from{' '}
          {formatInteger(m.optimization?.scheduledChecksDue || 0)} distinct due checks observed since
          this backend started. Pending, failed and late checks remain in the denominator.
        </p>
      </div>
    </div>
  );
}

function Optimization({ metrics }) {
  const summary = summarizeScraping(metrics);
  const counters = [
    ['BrowserAct submissions', metrics.retailerRequests],
    ['BrowserAct API calls, including polls', metrics.browserActApiCalls],
    ['Fresh cache hits', metrics.cacheHits],
    ['Requests sharing work in progress', metrics.duplicateRequestsSkipped],
    ['Failed submitted workflows', metrics.failedRequests],
    ['Submitted workflows still pending', summary.pending],
    ['Scheduled checks completed', metrics.scheduledChecksCompleted],
    ['Scheduled checks without a fresh result', metrics.scheduledChecksFailed],
    ['Due backlog at last scheduler scan', metrics.dueBacklog],
    ['Backlog past its deadline', metrics.overdueBacklog],
    ['Queue submissions deferred', metrics.scheduledChecksRejected],
  ];
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head"><h2 className="panel-title">Shared checks and freshness</h2></header>
      <div className="panel-body stack">
        <p className="small muted">
          Counters since {formatAbsolute(metrics.since)}. They reset when this backend restarts;
          the history window above does not change them. One submission starts a BrowserAct workflow.
          Its internal requests to a retailer cannot be measured here. Polling is counted separately.
        </p>
        <div className="stats">
          {counters.map(([label, value]) => (
            <div className="stat" key={label}>
              <div className="stat-label">{label}</div>
              <div className="stat-value">{formatInteger(value)}</div>
            </div>
          ))}
        </div>
        <p className="small muted">
          A due check counts as completed only when a verified observation is recorded at or after
          its scheduled time. On-time completion also meets the configured deadline (five minutes
          after the due time by default). Failures keep the previous price and its timestamp.
          On-time percentages are provisional while checks are pending. Requests queued more than
          once can increase the queue counter without increasing the number of distinct due checks.
        </p>
        <p className="small muted">
          Request reduction requires two controlled runs with the same products, schedule and traffic.
          No measured before-and-after saving is claimed by these counters alone.
        </p>
      </div>
    </section>
  );
}

/** Extraction, broken down by the method that read the price. */
function Extraction({ m }) {
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">Recorded check history</h2>
        <span className="small muted">
          average {formatLatency(m.extraction.averageLatencyMs)} per request
        </span>
      </header>
      <div className="panel-body">
        <p className="small muted" style={{ marginBottom: 'var(--s-4)' }}>
          BrowserAct is the only live price provider. Historical rows may include older strategies
          and failures before a workflow was submitted. These are recorded checks, not a count of
          outbound retailer requests. The current-run counters above measure actual submissions.
        </p>
      </div>
      <div className="panel-body panel-body-flush">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Strategy</th>
                <th className="table-num">Attempts</th>
                <th className="table-num">Succeeded</th>
                <th className="table-num">Rate</th>
                <th>Share</th>
                <th className="table-num">Average time</th>
              </tr>
            </thead>
            <tbody>
              {m.strategies.map((strategy) => (
                <tr key={strategy.strategy}>
                  <td className="machine">{strategy.strategy}</td>
                  <td className="table-num">{formatInteger(strategy.attempts)}</td>
                  <td className="table-num">{formatInteger(strategy.successes)}</td>
                  <td className="table-num">
                    {formatPercent(strategy.successRatePercent, { sign: false })}
                  </td>
                  <td>
                    <Bar percent={strategy.successRatePercent} />
                  </td>
                  <td className="table-num">{formatLatency(strategy.averageLatencyMs)}</td>
                </tr>
              ))}
              {m.strategies.length === 0 ? (
                <tr>
                  <td colSpan={6} className="faint">
                    No extraction attempts in this window yet.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}

/** The scheduling argument, spelled out. */
function Load({ m }) {
  const negative = m.load.loadReductionPercent < 0;

  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">Schedule and historical check volume</h2>
      </header>
      <div className="panel-body stack">
        <p className="small muted">
          Each shared retailer listing has one adaptive check interval. Unchanged prices can lengthen
          it; price changes and alert requirements can shorten it. Page visits read stored observations
          without starting a scrape. The hourly figure below is an estimate for today's active listings,
          not a measured baseline from an earlier version.
        </p>

        <div className="stats">
          <div className="stat">
            <div className="stat-label">Average interval now</div>
            <div className="stat-value">{formatInterval(m.load.averageIntervalMinutes)}</div>
            <div className="stat-note">
              across every active listing, versus {formatInterval(m.load.baselineIntervalMinutes)}{' '}
              fixed
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Recorded checks a day</div>
            <div className="stat-value">{formatRate(m.load.actualRequestsPerDay)}</div>
            <div className="stat-note">
              estimated hourly checks: {formatRate(m.load.baselineRequestsPerDay)}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Recorded freshness skips</div>
            <div className="stat-value">{formatInteger(m.load.dedupedRequests)}</div>
            <div className="stat-note">
              {formatPercent(m.load.dedupSharePercent, { sign: false })} of recorded checks reused
              an observation; these do not measure concurrent deduplication
            </div>
          </div>
        </div>

        {/*
         * The honest branch. Early in a deployment, or right after a burst of manual checks, the
         * adaptive schedule can genuinely be busier than a fixed hourly one — intervals start at the
         * baseline and only stretch once checks come back unchanged. Printing a negative number and
         * explaining it is the whole reason this page is worth reading; clamping it at zero would
         * make every other figure here suspect too.
         */}
        {negative ? (
          <Notice tone="info" title="Recorded checks exceed the hourly estimate.">
            Manual checks and failures before submission are included in these historical rows.
            This estimate uses today's listing count and cannot establish actual request savings.
          </Notice>
        ) : null}
      </div>
    </section>
  );
}

/** What the AI concluded, including the discounts it called fake. */
function VerdictBreakdown({ m }) {
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">What the analysis concluded</h2>
        <span className="small muted">{formatInteger(m.verdicts.analysed)} analyses</span>
      </header>
      <div className="panel-body panel-body-flush">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Verdict</th>
                <th className="table-num">Count</th>
                <th className="table-num">Share</th>
                <th>&nbsp;</th>
              </tr>
            </thead>
            <tbody>
              {m.verdicts.distribution.map((entry) => (
                <tr key={entry.label}>
                  <td>{entry.label}</td>
                  <td className="table-num">{formatInteger(entry.count)}</td>
                  <td className="table-num">{formatPercent(entry.sharePercent, { sign: false })}</td>
                  <td>
                    <Bar percent={entry.sharePercent} neutral />
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
           * The one metric here with no résumé bullet behind it, kept because it is the most
           * interesting thing the analysis does. An inflated MRP is the standard trick in Indian
           * e-commerce, and catching it needs the price history that this app happens to have.
           */}
          <strong>{formatInteger(m.verdicts.fakeDiscountsFound)}</strong> of those analyses found a
          discount that does not hold up — a listed price the product has never actually sold at
          within the recorded history, which is what makes a 62% off badge meaningless. The AI is
          given the price series rather than just the current figure, which is the only way that
          judgement can be made.
        </p>
      </div>
    </section>
  );
}

/** Per-host health and the scheduler's own vital signs. */
function Reliability({ m }) {
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">Which retailers answer</h2>
      </header>
      <div className="panel-body panel-body-flush">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Host</th>
                <th className="table-num">Attempts</th>
                <th className="table-num">Succeeded</th>
                <th className="table-num">Rate</th>
                <th>&nbsp;</th>
              </tr>
            </thead>
            <tbody>
              {m.hosts.map((host) => (
                <tr key={host.host}>
                  <td className="machine">{host.host}</td>
                  <td className="table-num">{formatInteger(host.attempts)}</td>
                  <td className="table-num">{formatInteger(host.successes)}</td>
                  <td className="table-num">
                    {formatPercent(host.successRatePercent, { sign: false })}
                  </td>
                  <td>
                    <Bar percent={host.successRatePercent} />
                  </td>
                </tr>
              ))}
              {m.hosts.length === 0 ? (
                <tr>
                  <td colSpan={5} className="faint">
                    Nothing fetched in this window yet.
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
                <th>Outcome</th>
                <th className="table-num">Count</th>
                <th className="table-num">Share</th>
                <th>&nbsp;</th>
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
                  <td>
                    <Bar percent={outcome.sharePercent} neutral />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel-body">
        <div className="stats">
          <div className="stat">
            <div className="stat-label">Scheduler</div>
            <div className="stat-value">{m.scheduler.enabled ? 'Running' : 'Stopped'}</div>
            <div className="stat-note">
              {m.scheduler.enabled
                ? 'wakes every ' +
                  m.scheduler.tickSeconds +
                  's, up to ' +
                  m.scheduler.batchSize +
                  ' listings a tick'
                : 'automatic checks are switched off on this deployment'}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Last tick</div>
            <div className="stat-value">{formatRelative(m.scheduler.lastTickAt)}</div>
            <div className="stat-note">
              {formatInteger(m.scheduler.ticksSinceStart)} ticks since the process started
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Due right now</div>
            <div className="stat-value">{formatInteger(m.scheduler.dueNow)}</div>
            <div className="stat-note">{formatInteger(m.scheduler.inFlight)} being fetched</div>
          </div>
          <div className="stat">
            <div className="stat-label">Alerts delivered</div>
            <div className="stat-value">
              {m.alerts.raised === 0
                ? '—'
                : formatPercent(m.alerts.deliveryRatePercent, { sign: false })}
            </div>
            <div className="stat-note">
              {formatInteger(m.alerts.delivered)} of {formatInteger(m.alerts.raised)} raised
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/** How much there is to measure in the first place. */
function Scale({ m }) {
  const c = m.coverage;
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">What is being watched</h2>
      </header>
      <div className="panel-body">
        <div className="stats">
          <div className="stat">
            <div className="stat-label">Retailers supported</div>
            <div className="stat-value">{formatInteger(c.supportedSites)}</div>
            <div className="stat-note">each with its own extraction rules</div>
          </div>
          <div className="stat">
            <div className="stat-label">Products</div>
            <div className="stat-value">{formatInteger(c.trackedProducts)}</div>
            <div className="stat-note">{formatInteger(c.activeTrackers)} active trackers on them</div>
          </div>
          <div className="stat">
            <div className="stat-label">Listings</div>
            <div className="stat-value">{formatInteger(c.activeListings)}</div>
            <div className="stat-note">
              of {formatInteger(c.totalListings)} — {formatInteger(c.pausedListings)} paused,{' '}
              {formatInteger(c.blockedListings)} blocked, {formatInteger(c.deadListings)} retired
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Prices recorded</div>
            <div className="stat-value">{formatInteger(c.priceObservations)}</div>
            <div className="stat-note">every one read from a retailer&apos;s own page</div>
          </div>
        </div>
      </div>
    </section>
  );
}

/** What it costs to run, in dollars. */
function Spend({ m }) {
  if (!m.spend || m.spend.length === 0) {
    return null;
  }
  const estimate = summarizeSpend(m.spend);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">What the APIs cost</h2>
        <span className="small muted">Recorded estimate: {formatUsdEstimate(estimate.total, 2)} over {m.windowDays} days</span>
      </header>
      <div className="panel-body panel-body-flush">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Provider</th>
                <th className="table-num">Calls</th>
                <th className="table-num">Input tokens</th>
                <th className="table-num">Output tokens</th>
                <th className="table-num">Estimated cost</th>
              </tr>
            </thead>
            <tbody>
              {m.spend.map((row) => (
                <tr key={row.provider}>
                  <td className="machine">{row.provider}</td>
                  <td className="table-num">{formatInteger(row.calls)}</td>
                  <td className="table-num">{formatInteger(row.inputTokens)}</td>
                  <td className="table-num">{formatInteger(row.outputTokens)}</td>
                  <td className="table-num">{formatUsdEstimate(row.costUsd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="panel-body">
        <p className="small muted">
          Estimates use recorded usage and the rates configured on the server. Your provider&apos;s billing dashboard shows the actual charges.
          {estimate.hasUnknown ? ' The total excludes providers whose costs have not been estimated; those calls may still incur charges.' : ''}
        </p>
      </div>
    </section>
  );
}

/**
 * A success rate as a bar.
 *
 * Colour follows the meaning rather than the magnitude: a high rate is good news, which is the same
 * direction as a falling price, so it gets the jade already in the palette. Share-of-total rows get
 * `neutral`, because a 40% share is neither good nor bad and colouring it would imply otherwise.
 */
function Bar({ percent, neutral = false }) {
  const value = Number(percent);
  const width = Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  const tone = neutral
    ? ''
    : width >= 85
      ? ' bar-fill-ok'
      : width >= 60
        ? ' bar-fill-warn'
        : ' bar-fill-bad';

  return (
    <div
      className="bar"
      role="img"
      aria-label={formatPercent(value, { sign: false }) || 'no data'}
      title={formatPercent(value, { sign: false }) || undefined}
    >
      <div className={'bar-fill' + tone} style={{ width: width + '%' }} />
    </div>
  );
}
