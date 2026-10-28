import { formatPercent } from '../lib/money.js';

/**
 * A price movement: direction, arrow, and figure.
 *
 * ── On the direction of "good" ──
 * Jade for a fall and oxblood for a rise, which is the opposite of a stock chart. This is a
 * shopping tool: a price going down is the good news, and colouring a drop red because that is what
 * a trading screen does would invert the meaning of the most-read element in the interface.
 *
 * ── Why the arrow is not optional ──
 * Colour alone would carry the whole message, which fails for anyone with red-green colour vision
 * deficiency and in a greyscale screenshot. The ▼/▲ and the words make the direction readable three
 * ways, of which colour is the fastest but never the only one.
 */
export default function Delta({ percent, direction, label, className = '' }) {
  const resolved = direction || inferDirection(percent);

  if (resolved === null) {
    return (
      <span className={'delta delta-flat ' + className} title="Not enough history to compare">
        no history yet
      </span>
    );
  }

  if (resolved === 'flat') {
    return <span className={'delta delta-flat ' + className}>unchanged</span>;
  }

  const figure = formatPercent(Math.abs(Number(percent)), { sign: false });
  const fell = resolved === 'fall';

  return (
    <span className={'delta delta-' + resolved + ' ' + className}>
      <span className="delta-arrow" aria-hidden="true">
        {fell ? '▼' : '▲'}
      </span>
      <span>
        {figure}
        <span className="sr-only">{fell ? ' lower' : ' higher'}</span>
      </span>
      {label ? <span className="muted small">{label}</span> : null}
    </span>
  );
}

/**
 * A missing percentage is `null`, not zero.
 *
 * The backend sends `windowChangePercent` as a boxed `Integer` precisely so that "we have one
 * reading and nothing to compare it with" is distinguishable from "the price has not moved". Both
 * are shown, differently: "no history yet" and "unchanged" are different claims.
 */
function inferDirection(percent) {
  if (percent === null || percent === undefined || percent === '') {
    return null;
  }
  const n = Number(percent);
  if (!Number.isFinite(n)) {
    return null;
  }
  if (n === 0) {
    return 'flat';
  }
  return n < 0 ? 'fall' : 'rise';
}
