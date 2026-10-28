import { formatPrice } from '../lib/money.js';

/**
 * Where today's price sits between the lowest and highest ever recorded.
 *
 * ── Why this and not a sparkline ──
 * The obvious thing in this slot is a sparkline, and the dashboard endpoint cannot support one:
 * `ApiViews.TrackedProduct` carries `fromPrice`, `allTimeLow` and `allTimeHigh`, but no series. The
 * options were to add a series to every dashboard row — hundreds of points per product, on the one
 * request that loads on every visit — or to draw something the existing data actually supports.
 *
 * This is the second, and it turns out to answer the better question. A sparkline shows the shape of
 * the past; a shopper deciding whether to buy today wants to know where today sits in the range, and
 * that is one number against two bounds. A marker near the left edge means this is close to the best
 * price ever seen, which is the fact the whole product exists to deliver.
 *
 * The full history is one click away on the product page, where the series is loaded because that
 * page is about the history.
 */
export default function RangeMark({ low, high, current, currency = 'INR', atLow = false }) {
  const min = num(low);
  const max = num(high);
  const now = num(current);

  if (min === null || max === null || now === null) {
    return <div className="rangemark-empty" aria-hidden="true" />;
  }

  // A product observed once has low == high == current, so the span is zero and the position is
  // undefined. Showing the marker centred would imply a mid-range price on no evidence; the flat
  // rule says "one price, no range yet" without claiming anything.
  const span = max - min;
  const position = span <= 0 ? null : clamp((now - min) / span);

  const label =
    span <= 0
      ? 'Only one price recorded so far: ' + formatPrice(now, currency)
      : 'Now ' +
        formatPrice(now, currency) +
        '. Lowest ever ' +
        formatPrice(min, currency) +
        ', highest ' +
        formatPrice(max, currency) +
        '.';

  return (
    <div className="rangemark" title={label} role="img" aria-label={label}>
      <div className="rangemark-track">
        {position === null ? null : (
          <span
            className={'rangemark-dot' + (atLow ? ' rangemark-dot-low' : '')}
            style={{ left: (position * 100).toFixed(1) + '%' }}
          />
        )}
      </div>
      <div className="rangemark-ends">
        <span>low</span>
        <span>high</span>
      </div>
    </div>
  );
}

function num(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function clamp(n) {
  return Math.max(0, Math.min(1, n));
}
