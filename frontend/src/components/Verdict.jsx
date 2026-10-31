import Money from './Money.jsx';
import { formatRelative } from '../lib/time.js';

/**
 * The AI's read on a price — the compact chip, and the full block.
 *
 * ── The one thing this component must never do ──
 * Hide a stale verdict. `ApiViews.Verdict.stale` is true when the price has moved since the verdict
 * was formed, and the temptation is to suppress it so the page always shows something confident.
 * That is exactly backwards: a verdict formed at ₹1,31,000 is still useful when the price is now
 * ₹1,24,000 — it says an analysis thought the higher figure was fair — but presenting it as current
 * would be a claim about a price the AI never saw. So it is shown, marked, and the price it was
 * formed on is stated beside it.
 *
 * ── Why the reasoning is never truncated ──
 * An unexplained verdict is an oracle, and nobody can sensibly disagree with an oracle. The whole
 * value of the AI layer here is that a shopper can read the argument — "this has sat at ₹1,29,900
 * for six weeks and the struck-through ₹1,69,900 does not appear anywhere in its history" — and
 * decide the argument is wrong. A "read more" link on a three-sentence paragraph would get in the
 * way of the only part worth reading.
 */

/** The chip, for a dashboard row. */
export function VerdictChip({ verdict }) {
  if (!verdict) {
    return <span className="verdict-chip verdict-chip-flat">Not analysed</span>;
  }
  return (
    <span
      className={
        'verdict-chip ' +
        toneClass(verdict) +
        (verdict.stale ? ' verdict-chip-stale' : '')
      }
      title={
        verdict.stale
          ? 'Formed when the price was different, so it may no longer apply'
          : verdict.headline || verdict.label
      }
    >
      {verdict.label}
      {verdict.stale ? ' (price has moved)' : ''}
    </span>
  );
}

/** The full block, for a product page. */
export function VerdictBlock({ verdict, currency = 'INR', currentPrice }) {
  if (!verdict) {
    return (
      <p className="muted">
        No verdict yet. An analysis needs a few price readings to compare against, so it runs once
        this product has some history — or immediately if you ask for one.
      </p>
    );
  }

  return (
    <div className="verdict-block">
      {verdict.stale ? (
        <p className="verdict-stale-note">
          This verdict was formed when the price was{' '}
          <Money value={verdict.analysedPrice} currency={currency} />, and the price is now{' '}
          <Money value={currentPrice} currency={currency} />. Ask for a new analysis to have it
          reconsidered.
        </p>
      ) : null}

      <div className="row">
        <VerdictChip verdict={verdict} />
        {verdict.confidencePercent === null || verdict.confidencePercent === undefined ? null : (
          <span className="small muted">{verdict.confidencePercent}% confident</span>
        )}
      </div>

      {verdict.headline ? <p className="verdict-headline">{verdict.headline}</p> : null}
      {verdict.reasoning ? <p className="verdict-reasoning">{verdict.reasoning}</p> : null}

      {/*
       * The fake-discount warning.
       *
       * Indian listing pages routinely show a struck-through "M.R.P." far above anything the product
       * has ever sold for, which makes a 55%-off badge out of a price that has not moved in months.
       * The analysis is given the real price history and asked whether the claimed list price appears
       * in it; when it does not, that is the single most useful thing this app can tell a shopper,
       * and it gets its own block rather than a clause inside the reasoning.
       */}
      {verdict.fakeDiscount ? (
        <div className="verdict-warning">
          <div className="verdict-warning-title">The advertised discount looks inflated</div>
          <p className="small">
            The struck-through price on this listing does not appear anywhere in the price history we
            have recorded, so the discount is measured against a figure the product has not actually
            sold at.
          </p>
        </div>
      ) : null}

      <div className="verdict-facts">
        {verdict.suggestedPrice ? (
          <span>
            <span className="verdict-fact-label">Worth buying at</span>
            <Money value={verdict.suggestedPrice} currency={currency} />
          </span>
        ) : null}
        <span>
          <span className="verdict-fact-label">Analysed at</span>
          <Money value={verdict.analysedPrice} currency={currency} />
        </span>
        <span>
          <span className="verdict-fact-label">Formed</span>
          <span title={verdict.createdAt}>{formatRelative(verdict.createdAt)}</span>
        </span>
        {/* The model name, because a verdict is only reproducible if you know what produced it. */}
        {verdict.model ? (
          <span className="machine" title="The model that produced this verdict">
            {verdict.model}
          </span>
        ) : null}
      </div>
    </div>
  );
}

/** Earlier verdicts, as a strip. "This was a WAIT last week" is context a single verdict lacks. */
export function VerdictHistory({ entries = [], currency = 'INR' }) {
  if (entries.length === 0) {
    return null;
  }
  return (
    <div className="verdict-history">
      {entries.map((entry) => (
        <div key={entry.createdAt} className="verdict-history-item" title={entry.headline || ''}>
          <span className="faint">{formatRelative(entry.createdAt)}</span>
          <strong>{entry.label}</strong>
          <Money value={entry.analysedPrice} currency={currency} />
        </div>
      ))}
    </div>
  );
}

/**
 * The chip's tone comes from `favourable`, not from the verdict name.
 *
 * `favourable` is computed on the server by the same rule the metrics page counts with, so the
 * colour on a dashboard row and the published "N% of deals rated good value" figure can never
 * disagree. Re-deriving it here from a list of enum names is how those two numbers drift apart.
 */
function toneClass(verdict) {
  if (verdict.verdict === 'INSUFFICIENT_DATA') {
    return 'verdict-chip-flat';
  }
  if (verdict.favourable) {
    return 'verdict-chip-good';
  }
  if (verdict.verdict === 'WAIT' || verdict.verdict === 'OVERPRICED') {
    return 'verdict-chip-wait';
  }
  return 'verdict-chip-flat';
}
