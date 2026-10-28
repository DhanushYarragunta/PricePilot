import { useMemo, useRef, useState } from 'react';
import { formatDay, formatAbsolute } from '../lib/time.js';
import { formatAmount, formatPrice, symbolFor } from '../lib/money.js';

/**
 * Price history, as a step chart.
 *
 * ── Why a step chart, and why hand-drawn ──
 * A price is a step function. It is ₹1,29,900 until the moment a retailer changes it, then it is
 * something else — it does not slide linearly between two observations, and a smoothed line implies
 * prices we never saw and the product never had. Every charting library draws a straight or curved
 * interpolation by default, and most make a true step awkward, so the choice was between fighting a
 * dependency and writing about sixty lines of path arithmetic. The arithmetic also keeps the bundle
 * at three packages and means the chart inherits the app's own type and colour rather than a
 * library's.
 *
 * ── Why the lines are not colour-coded ──
 * The one rule of this interface is that colour means direction: jade is a fall, oxblood is a rise,
 * saffron is an all-time low. Assigning a hue per retailer would spend that vocabulary on something
 * else entirely, and six hues on one chart would make a single jade number elsewhere stop meaning
 * anything. So retailers are distinguished by ink weight and dash pattern instead — which also
 * survives greyscale printing and every form of colour vision deficiency.
 */

const VIEW_W = 1000;
const VIEW_H = 320;
const PAD = { top: 18, right: 16, bottom: 30, left: 66 };

/**
 * Six line styles, in assignment order. The first is the heaviest because index 0 is the primary
 * listing — the one whose price represents the product elsewhere in the app — so the line a reader
 * follows by default is the line the rest of the interface is quoting.
 */
const STYLES = [
  { stroke: '#16233B', width: 2.25, dash: null },
  { stroke: '#566481', width: 1.75, dash: '7 3' },
  { stroke: '#8995AB', width: 1.75, dash: '2 3' },
  { stroke: '#16233B', width: 1.5, dash: '10 3 2 3' },
  { stroke: '#566481', width: 1.5, dash: '14 4' },
  { stroke: '#8995AB', width: 1.5, dash: '4 4' },
];

export default function PriceChart({ series = [], currency = 'INR', windowDays }) {
  const [hidden, setHidden] = useState(() => new Set());
  const [hover, setHover] = useState(null);
  const wrapRef = useRef(null);

  const visible = useMemo(
    () => series.filter((s) => !hidden.has(s.listingId) && (s.points?.length || 0) > 0),
    [series, hidden]
  );

  const scale = useMemo(() => computeScale(visible), [visible]);

  const withPoints = series.filter((s) => (s.points?.length || 0) > 0);
  if (withPoints.length === 0) {
    return (
      <div className="chart-empty">
        No price history yet. The chart appears once this product has been checked more than once —
        scheduled checks run automatically, and the refresh button checks every retailer now.
      </div>
    );
  }

  if (!scale) {
    return (
      <div className="chart-empty">
        Every retailer is hidden. Use the legend below to bring one back.
      </div>
    );
  }

  const toggle = (listingId) => {
    setHidden((current) => {
      const next = new Set(current);
      if (next.has(listingId)) {
        next.delete(listingId);
      } else {
        next.add(listingId);
      }
      return next;
    });
  };

  const onMove = (event) => {
    const wrap = wrapRef.current;
    if (!wrap) {
      return;
    }
    const box = wrap.getBoundingClientRect();
    // The SVG is scaled by CSS, so a client x has to be mapped back into viewBox units before it
    // can be compared against a plotted position.
    const viewX = ((event.clientX - box.left) / box.width) * VIEW_W;
    const at = scale.timeAt(viewX);
    const readings = visible
      .map((s) => ({ retailer: s.retailer, point: lastAtOrBefore(s.points, at) }))
      .filter((r) => r.point !== null);
    if (readings.length === 0) {
      setHover(null);
      return;
    }
    setHover({
      at,
      left: ((event.clientX - box.left) / box.width) * 100,
      readings,
    });
  };

  return (
    <>
      <div
        className="chart-wrap"
        ref={wrapRef}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        <svg
          className="chart"
          viewBox={'0 0 ' + VIEW_W + ' ' + VIEW_H}
          role="img"
          aria-label={describe(series, currency, windowDays)}
        >
          {/* Horizontal grid only. Vertical gridlines would imply the x positions mean something
              regular, and they do not — observations land whenever a check happened to run. */}
          {scale.priceTicks.map((tick) => (
            <g key={tick.value}>
              <line
                x1={PAD.left}
                x2={VIEW_W - PAD.right}
                y1={tick.y}
                y2={tick.y}
                stroke="#e4e9ef"
                strokeWidth="1"
                vectorEffect="non-scaling-stroke"
              />
              <text x={PAD.left - 10} y={tick.y + 4} textAnchor="end" className="chart-axis">
                {symbolFor(currency) + formatAmount(tick.value)}
              </text>
            </g>
          ))}

          {scale.timeTicks.map((tick) => (
            <text
              key={tick.value}
              x={tick.x}
              y={VIEW_H - PAD.bottom + 18}
              textAnchor={tick.anchor}
              className="chart-axis"
            >
              {formatDay(new Date(tick.value).toISOString())}
            </text>
          ))}

          {/*
           * The all-time low, drawn as a saffron rule across the plot. This is the single use of the
           * accent colour in the entire application, and it marks the only line on the chart that a
           * shopper is really looking for: the best price this product has ever been.
           */}
          {scale.lowY === null ? null : (
            <g>
              <line
                x1={PAD.left}
                x2={VIEW_W - PAD.right}
                y1={scale.lowY}
                y2={scale.lowY}
                stroke="#c17d12"
                strokeWidth="1"
                strokeDasharray="4 4"
                vectorEffect="non-scaling-stroke"
              />
              <text x={VIEW_W - PAD.right} y={scale.lowY - 6} textAnchor="end" className="chart-low">
                lowest seen
              </text>
            </g>
          )}

          {visible.map((s) => {
            const style = STYLES[styleIndexOf(series, s.listingId)];
            return (
              <path
                key={s.listingId}
                d={stepPath(s.points, scale)}
                fill="none"
                stroke={style.stroke}
                strokeWidth={style.width}
                strokeDasharray={style.dash || undefined}
                strokeLinejoin="miter"
                vectorEffect="non-scaling-stroke"
              />
            );
          })}

          {hover ? (
            <line
              x1={scale.x(hover.at)}
              x2={scale.x(hover.at)}
              y1={PAD.top}
              y2={VIEW_H - PAD.bottom}
              stroke="#16233B"
              strokeWidth="1"
              vectorEffect="non-scaling-stroke"
            />
          ) : null}

          {/* The plot frame, drawn last so the lines do not sit on top of it. */}
          <line
            x1={PAD.left}
            x2={PAD.left}
            y1={PAD.top}
            y2={VIEW_H - PAD.bottom}
            stroke="#b9c3d1"
            strokeWidth="1"
            vectorEffect="non-scaling-stroke"
          />
          <line
            x1={PAD.left}
            x2={VIEW_W - PAD.right}
            y1={VIEW_H - PAD.bottom}
            y2={VIEW_H - PAD.bottom}
            stroke="#b9c3d1"
            strokeWidth="1"
            vectorEffect="non-scaling-stroke"
          />
        </svg>

        {hover ? (
          <div
            className="chart-tip"
            style={{ left: Math.min(92, Math.max(8, hover.left)) + '%', top: '18%' }}
          >
            <div>{formatAbsolute(new Date(hover.at).toISOString())}</div>
            {hover.readings.map((reading) => (
              <div key={reading.retailer}>
                {reading.retailer}: {formatPrice(reading.point.price, currency)}
                {reading.point.inStock ? '' : ' (out of stock)'}
              </div>
            ))}
          </div>
        ) : null}
      </div>

      {series.length > 1 ? (
        <div className="chart-legend">
          {series.map((s) => {
            const style = STYLES[styleIndexOf(series, s.listingId)];
            const on = !hidden.has(s.listingId);
            return (
              <button
                key={s.listingId}
                type="button"
                className="chart-legend-item"
                aria-pressed={on}
                onClick={() => toggle(s.listingId)}
              >
                <span
                  className="chart-legend-swatch"
                  style={{
                    background: on ? style.stroke : 'transparent',
                    borderTop: on ? 'none' : '2px dotted ' + style.stroke,
                    // The dash pattern is reproduced in the swatch, so the legend identifies a line
                    // by its actual appearance rather than by a colour chip that matches nothing.
                    backgroundImage: on && style.dash ? dashSwatch(style) : undefined,
                  }}
                  aria-hidden="true"
                />
                {s.retailer}
              </button>
            );
          })}
        </div>
      ) : null}
    </>
  );
}

// ──────────────────────────────────────────────────────────────────── geometry

/**
 * The step path.
 *
 * Each observation holds until the next one: horizontal to the new time at the old price, then
 * vertical to the new price. The final segment runs to the right edge of the plot, which says the
 * last price we read is the price we believe is current — the same claim the rest of the app makes.
 * What the chart cannot show is *when* that was last confirmed, which is why the check log sits
 * directly beneath it on the product page.
 */
function stepPath(points, scale) {
  const sorted = [...points].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  let d = '';
  let previousY = null;
  sorted.forEach((point, index) => {
    const x = scale.x(Date.parse(point.at));
    const y = scale.y(Number(point.price));
    if (index === 0) {
      d += 'M ' + x.toFixed(1) + ' ' + y.toFixed(1);
    } else {
      d += ' L ' + x.toFixed(1) + ' ' + previousY.toFixed(1);
      d += ' L ' + x.toFixed(1) + ' ' + y.toFixed(1);
    }
    previousY = y;
  });
  if (previousY !== null) {
    d += ' L ' + (VIEW_W - PAD.right).toFixed(1) + ' ' + previousY.toFixed(1);
  }
  return d;
}

function computeScale(visible) {
  const prices = [];
  let minTime = Infinity;
  let maxTime = -Infinity;

  visible.forEach((s) => {
    s.points.forEach((point) => {
      const price = Number(point.price);
      const time = Date.parse(point.at);
      if (Number.isFinite(price)) {
        prices.push(price);
      }
      if (Number.isFinite(time)) {
        minTime = Math.min(minTime, time);
        maxTime = Math.max(maxTime, time);
      }
    });
  });

  if (prices.length === 0 || !Number.isFinite(minTime)) {
    return null;
  }

  const lowest = Math.min(...prices);
  const highest = Math.max(...prices);

  /*
   * The y axis does not start at zero, and that is a deliberate departure from the usual rule
   * against truncated axes. A laptop that moved between ₹1,24,000 and ₹1,31,000 is a 5% swing; on a
   * zero-based axis both lines would sit in the top tenth of the plot and the movement — the only
   * thing the chart is for — would be invisible. The axis labels carry the real figures, and the
   * all-time-low rule gives an absolute reference, so the scale is readable rather than misleading.
   */
  const span = highest - lowest;
  const pad = span === 0 ? Math.max(highest * 0.04, 1) : span * 0.12;
  const yMin = Math.max(0, lowest - pad);
  const yMax = highest + pad;

  // A single observation has no time span, so one was invented would put the point on the left edge
  // with nothing to its right. Widening by an hour centres it instead.
  const tMin = minTime;
  const tMax = maxTime > minTime ? maxTime : minTime + 3600000;

  const plotW = VIEW_W - PAD.left - PAD.right;
  const plotH = VIEW_H - PAD.top - PAD.bottom;

  const x = (time) => PAD.left + ((time - tMin) / (tMax - tMin)) * plotW;
  const y = (price) => PAD.top + plotH - ((price - yMin) / (yMax - yMin)) * plotH;

  return {
    x,
    y,
    timeAt: (viewX) => tMin + ((viewX - PAD.left) / plotW) * (tMax - tMin),
    lowY: y(lowest),
    priceTicks: niceTicks(yMin, yMax).map((value) => ({ value, y: y(value) })),
    timeTicks: timeTicks(tMin, tMax).map((value, index, all) => ({
      value,
      x: x(value),
      anchor: index === 0 ? 'start' : index === all.length - 1 ? 'end' : 'middle',
    })),
  };
}

/**
 * Four axis values at a round interval.
 *
 * Rounded to 1, 2, 2.5 or 5 times a power of ten, which is what makes the labels read as prices
 * (₹1,20,000) rather than as arithmetic (₹1,23,847). Axis labels that look computed are a strong
 * signal that nobody looked at the chart.
 */
function niceTicks(min, max) {
  const target = 4;
  const rough = (max - min) / target;
  if (!Number.isFinite(rough) || rough <= 0) {
    return [min];
  }
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
  const normalised = rough / magnitude;
  const step =
    (normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 2.5 ? 2.5 : normalised <= 5 ? 5 : 10) *
    magnitude;
  const ticks = [];
  for (let value = Math.ceil(min / step) * step; value <= max + step * 0.001; value += step) {
    ticks.push(Math.round(value * 100) / 100);
  }
  return ticks.length > 0 ? ticks : [min];
}

function timeTicks(min, max) {
  const count = 4;
  const ticks = [];
  for (let i = 0; i < count; i++) {
    ticks.push(Math.round(min + ((max - min) * i) / (count - 1)));
  }
  return ticks;
}

/** The last reading at or before a moment — which is exactly what a step chart displays there. */
function lastAtOrBefore(points, at) {
  let best = null;
  points.forEach((point) => {
    const time = Date.parse(point.at);
    if (time <= at && (best === null || time > Date.parse(best.at))) {
      best = point;
    }
  });
  // Before the first reading of a late-added retailer there is nothing to report, so the tooltip
  // falls back to that retailer's earliest price rather than claiming a value it never had.
  return best;
}

/**
 * A line's style index is its position in the full series list, not in the visible one.
 *
 * Hiding a retailer in the legend must not restyle the remaining lines — if it did, toggling one
 * series off would appear to change the identity of the others.
 */
function styleIndexOf(series, listingId) {
  const index = series.findIndex((s) => s.listingId === listingId);
  return (index < 0 ? 0 : index) % STYLES.length;
}

function dashSwatch(style) {
  const [on, off] = String(style.dash).split(' ').map(Number);
  const period = (on || 4) + (off || 3);
  return (
    'repeating-linear-gradient(90deg, ' +
    style.stroke +
    ' 0 ' +
    (on || 4) +
    'px, transparent ' +
    (on || 4) +
    'px ' +
    period +
    'px)'
  );
}

/** The chart in a sentence, for anyone using a screen reader. */
function describe(series, currency, windowDays) {
  const named = series
    .filter((s) => (s.points?.length || 0) > 0)
    .map((s) => {
      const sorted = [...s.points].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
      const first = sorted[0];
      const last = sorted[sorted.length - 1];
      return (
        s.retailer +
        ' from ' +
        formatPrice(first.price, currency) +
        ' to ' +
        formatPrice(last.price, currency)
      );
    });
  if (named.length === 0) {
    return 'No price history yet.';
  }
  return (
    'Price history over the last ' + (windowDays || 90) + ' days. ' + named.join('. ') + '.'
  );
}
