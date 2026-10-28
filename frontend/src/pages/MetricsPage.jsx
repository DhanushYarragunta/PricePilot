import { useState } from 'react';
import { api } from '../lib/api.js';
import { useAsync } from '../lib/useAsync.js';
import Working from '../components/Working.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { formatInteger, formatPercent, formatRate } from '../lib/money.js';
import { formatAbsolute, formatInterval, formatLatency, formatRelative } from '../lib/time.js';

const WINDOWS = [7, 30, 90];

/**
 * The page that makes the claims checkable.
 *
 * Deliberately public and unauthenticated. Three numbers on a résumé — how many prices were judged
 * good value, how often an extraction succeeded, how much the adaptive schedule cut the request
 * volume — are assertions until someone can open a URL and see them computed from the rows that
 * produced them. Every figure here is recomputed on request by OpsMetricsService; none of them is
 * stored, and none of them is rounded in a flattering direction.
 *
 * The denominators are printed next to the percentages for the same reason. A success rate is
 * meaningless without knowing what counted as an attempt, and the most common way these numbers get
 * inflated is by quietly excluding the attempts that failed.
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
              Every number on this page is computed from the database when you load it. Nothing is
              cached, hard-coded or hand-written. If a figure looks poor, it is poor.
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
  return (
    <div className="metric-grid">
      <div className="headline-metric">
        <div className="headline-metric-value">
          {m.extraction.networkRequests === 0
            ? '—'
            : formatPercent(m.extraction.successRatePercent, { sign: false })}
        </div>
        <div className="headline-metric-label">of page reads returned a price</div>
        <p className="headline-metric-basis">
          {formatInteger(m.extraction.successes)} successes from{' '}
          {formatInteger(m.extraction.networkRequests)} requests that actually went out over the
          network. Requests skipped because a recent price was already on file are excluded from both
          halves — counting them as successes is the easiest way to manufacture a high number here.
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
          {formatInteger(m.verdicts.analysed)} analyses by Claude. A verdict counts as favourable
          only if the model returned good value or better — the server decides that, not this page.
        </p>
      </div>

      <div className="headline-metric">
        <div className="headline-metric-value">
          {m.load.actualRequestsPerDay === 0
            ? '—'
            : formatPercent(m.load.loadReductionPercent, { sign: false })}
        </div>
        <div className="headline-metric-label">less scraping than fixed hourly checks</div>
        <p className="headline-metric-basis">
          {formatRate(m.load.actualRequestsPerDay)} a day against{' '}
          {formatRate(m.load.baselineRequestsPerDay)} if every listing were checked every{' '}
          {formatInterval(m.load.baselineIntervalMinutes)}, which is the baseline this replaced.
        </p>
      </div>
    </div>
  );
}

/** Extraction, broken down by the method that read the price. */
function Extraction({ m }) {
  return (
    <section className="panel" style={{ marginBottom: 'var(--s-5)' }}>
      <header className="panel-head">
        <h2 className="panel-title">How prices get read</h2>
        <span className="small muted">
          average {formatLatency(m.extraction.averageLatencyMs)} per request
        </span>
      </header>
      <div className="panel-body">
        <p className="small muted" style={{ marginBottom: 'var(--s-4)' }}>
          {/*
           * The order is the actual runtime order of the chain, and saying so is the point: the
           * cheap structured sources are tried first and the brittle per-site selectors last, which
           * is why a retailer redesigning its markup usually costs nothing.
           */}
          Each page is tried against a chain of strategies in order — structured product data first,
          then page metadata, then embedded JSON, then selectors written for that specific retailer.
          The first one that yields a plausible price wins, so the rows near the top of this table
          doing most of the work is the design functioning as intended.
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
        <h2 className="panel-title">Request volume</h2>
      </header>
      <div className="panel-body stack">
        <p className="small muted">
          Checking everything hourly is the obvious design and it is wasteful: most products sit at
          the same price for weeks. Each listing here carries its own interval, which stretches by
          about 60% each time a check finds no change and collapses when a price moves. A product in
          the middle of a sale gets watched closely; a wall mount nobody has repriced since March
          gets looked at once a day.
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
            <div className="stat-label">Requests a day</div>
            <div className="stat-value">{formatRate(m.load.actualRequestsPerDay)}</div>
            <div className="stat-note">
              hourly checks would be {formatRate(m.load.baselineRequestsPerDay)}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Saved by sharing</div>
            <div className="stat-value">{formatInteger(m.load.dedupedRequests)}</div>
            <div className="stat-note">
              {formatPercent(m.load.dedupSharePercent, { sign: false })} of requests avoided because
              two people track the same URL
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
          <Notice tone="info" title="This is currently negative.">
            Intervals begin at the baseline and only widen after checks come back unchanged, and
            manual checks count too. On a young deployment, or just after someone has pressed Check
            prices now a few times, the adaptive schedule really is making more requests than a fixed
            hourly one would. It crosses over once listings have had a few quiet cycles.
          </Notice>
        ) : null}
      </div>
    </section>
  );
}

/** What Claude concluded, including the discounts it called fake. */
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
          within the recorded history, which is what makes a 62% off badge meaningless. Claude is
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
  const total = m.spend.reduce((sum, row) => sum + Number(row.costUsd || 0), 0);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">What the APIs cost</h2>
        <span className="small muted">${total.toFixed(2)} over {m.windowDays} days</span>
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
                <th className="table-num">Cost</th>
              </tr>
            </thead>
            <tbody>
              {m.spend.map((row) => (
                <tr key={row.provider}>
                  <td className="machine">{row.provider}</td>
                  <td className="table-num">{formatInteger(row.calls)}</td>
                  <td className="table-num">{formatInteger(row.inputTokens)}</td>
                  <td className="table-num">{formatInteger(row.outputTokens)}</td>
                  <td className="table-num">${Number(row.costUsd || 0).toFixed(4)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="panel-body">
        <p className="small muted">
          {/*
           * Tracked per call from the start, because the alternative is discovering the number on a
           * credit-card statement. It is also the figure that justified the adaptive scheduler: the
           * saving in the third headline metric above is money, not just politeness.
           */}
          Recorded per call as the application makes them, not read from a billing dashboard. Token
          counts come back in each API response, and the rates are configured on the server, so this
          is what the usage cost at the prices in force.
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
