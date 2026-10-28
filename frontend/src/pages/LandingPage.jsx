import { Link } from 'react-router-dom';
import { api } from '../lib/api.js';
import { useAsync } from '../lib/useAsync.js';
import { useCapabilities } from '../lib/capabilities.jsx';
import { formatInteger, formatPercent, formatRate } from '../lib/money.js';
import { formatInterval } from '../lib/time.js';

/**
 * The front door.
 *
 * ── Why the hero is a measurement and not a mockup ──
 * The conventional move is a styled screenshot with a plausible price in it. That price would be
 * invented, and this application's single governing rule is that no price is ever shown unless it
 * was read from a retailer's own page a moment ago. A fabricated number in the hero would break
 * that rule in the first thing anyone sees.
 *
 * So the hero shows the one number that is both characteristic of the product and true: the share of
 * page reads that actually returned a price, computed live from the same public endpoint the metrics
 * page uses. A visitor sees the system's real output before reading a word of copy, and the figure
 * is checkable in the next click.
 */
export default function LandingPage() {
  const caps = useCapabilities();
  // Unauthenticated: this endpoint is public by design, which is what lets the landing page make a
  // measured claim to someone who has not signed up.
  const metrics = useAsync((options) => api.metrics(30, options), []);
  const m = metrics.data;

  const retailers =
    caps.supportedRetailers.length > 0
      ? caps.supportedRetailers
      : ['Amazon.in', 'Flipkart', 'Croma', 'Reliance Digital'];

  return (
    <div className="page">
      <section className="hero">
        <div className="hero-grid">
          <div>
            <h1 className="hero-title">
              Watch the price,
              <br />
              not the badge.
            </h1>
            <p className="hero-sub">
              Paste a product link. PricePilot reads the retailer&apos;s own page, keeps reading it
              on a schedule that tightens when the price moves, and tells you when it reaches the
              number you had in mind — or when a &ldquo;62% off&rdquo; turns out to be measured
              against a price the product has never sold at.
            </p>
            <div className="hero-actions">
              <Link to="/create-account" className="btn">
                Start tracking something
              </Link>
              <Link to="/metrics" className="btn btn-ghost">
                See how well it works
              </Link>
            </div>
            <p className="small faint" style={{ marginTop: 'var(--s-5)' }}>
              Works with {retailers.join(', ')}.
            </p>
          </div>

          <div className="hero-demo">
            <p className="hero-demo-title">Prices read straight from the retailer</p>
            <div className="hero-demo-price">
              <span className="hero-demo-now">
                {m && m.extraction.networkRequests > 0
                  ? formatPercent(m.extraction.successRatePercent, { sign: false })
                  : '—'}
              </span>
              <span className="muted small">
                of page reads returned a price
                <br />
                in the last {m ? m.windowDays : 30} days
              </span>
            </div>
            <p className="small muted">
              {m
                ? formatInteger(m.extraction.successes) +
                  ' successful reads from ' +
                  formatInteger(m.extraction.networkRequests) +
                  ' requests, across ' +
                  formatInteger(m.coverage.supportedSites) +
                  ' retailers. ' +
                  formatInteger(m.coverage.priceObservations) +
                  ' prices recorded so far, every one of them fetched.'
                : 'Measuring…'}
            </p>
            <div className="hero-demo-foot">
              <span>computed when you loaded this page</span>
              <Link to="/metrics">the full breakdown</Link>
            </div>
          </div>
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">What it actually does</h2>
        <div className="claims">
          <div className="claim">
            <h3 className="claim-head">Every price is fetched, never inferred</h3>
            <p className="claim-body">
              There is a tempting shortcut here: ask a search API what a product costs and read the
              number out of the snippet. It is fast, it never gets blocked, and it is frequently
              wrong — snippets go stale, quote a different variant, or quote a marketplace seller. So
              every figure in this app comes from an actual request to the actual product page, run
              through a chain of extraction strategies that starts with the structured data retailers
              publish for Google and falls back to selectors written for that specific site. Search
              is used for one thing only: finding which other retailers sell the same product.
            </p>
          </div>

          <div className="claim">
            <h3 className="claim-head">It checks more often when that is worth doing</h3>
            <p className="claim-body">
              Most products sit at the same price for weeks, so checking everything hourly spends
              most of its requests confirming that nothing happened — which is both rude to the
              retailer and the fastest way to get blocked. Each listing here carries its own
              interval: it stretches by about 60% every time a check finds no change, and collapses
              the moment a price moves.{' '}
              {m
                ? 'Right now that is ' +
                  formatRate(m.load.actualRequestsPerDay) +
                  ' requests a day against ' +
                  formatRate(m.load.baselineRequestsPerDay) +
                  ' for fixed ' +
                  formatInterval(m.load.baselineIntervalMinutes) +
                  ' checks.'
                : ''}
            </p>
          </div>

          <div className="claim">
            <h3 className="claim-head">
              {caps.aiEnabled
                ? 'A verdict you can argue with'
                : 'A price history you can read'}
            </h3>
            <p className="claim-body">
              {caps.aiEnabled ? (
                <>
                  Claude is given the whole recorded price series for a product, not just today&apos;s
                  figure, and asked whether this is a good moment to buy. It returns a verdict, a
                  confidence, and its reasoning — all three are shown, because a verdict without its
                  reasoning is an oracle and nobody can sensibly disagree with an oracle. Having the
                  history is also what lets it spot an inflated list price: a discount measured
                  against a number the product has never sold at is not a discount.
                  {m && m.verdicts.analysed > 0
                    ? ' Of ' +
                      formatInteger(m.verdicts.analysed) +
                      ' analyses so far, ' +
                      formatPercent(m.verdicts.favourablePercent, { sign: false }) +
                      ' came back as good value or better.'
                    : ''}
                </>
              ) : (
                <>
                  Every reading is kept, so each product has a step chart of what it has actually
                  cost — drawn as steps rather than a smooth line, because a price does not drift
                  between two figures, it jumps. Alongside it sit the lowest and highest prices ever
                  recorded and where today sits between them, which is the comparison that decides
                  whether to buy now or wait.
                </>
              )}
            </p>
          </div>

          <div className="claim">
            <h3 className="claim-head">It shows you its working</h3>
            <p className="claim-body">
              Every product page ends with a log of every check ever made against it: when, which
              retailer, which extraction strategy read the price, the HTTP status, how long it took,
              and what went wrong when something did. Failures are listed, not hidden. A price
              tracker that only displays its successes is asking to be trusted; this one would rather
              be checked.
            </p>
          </div>
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">Built to stay up</h2>
        <p style={{ maxWidth: 'var(--measure)' }}>
          A listing that keeps failing pauses itself rather than hammering a retailer that has
          started refusing it. One that returns a permanent error retires itself. Integrations that
          are not configured are hidden rather than offered as buttons that error. Errors carry the
          server&apos;s own sentence and a reference you can quote. None of that is visible when
          everything works, which is rather the point.
        </p>
        <p style={{ marginTop: 'var(--s-5)' }}>
          <Link to="/metrics" className="btn btn-ghost">
            Read the measurements
          </Link>
        </p>
      </section>
    </div>
  );
}
