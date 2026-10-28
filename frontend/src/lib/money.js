/**
 * Money formatting, for India.
 *
 * ── Why this file exists at all ──
 * `toLocaleString()` scattered through components would be shorter, and wrong in two ways that both
 * matter here. First, the grouping: Indian digit grouping is lakh-style — ₹1,29,900, not ₹129,900 —
 * and a price tracker for Indian retailers that renders Western grouping looks immediately foreign
 * to the only people who will use it. `en-IN` gets this right natively. Second, consistency with the
 * backend: `PriceFormatter` on the server uses `Locale.forLanguageTag("en-IN")` for the same figures
 * in alert emails, so a price in an email and the same price on screen must read identically or the
 * alert looks like it is about a different product.
 *
 * ── Why no decimals by default ──
 * Indian retail prices are effectively integral: ₹1,29,900 rather than ₹1,29,900.00. The database
 * stores numeric(12,2) because a discount calculation can land on paise and truncating in storage
 * would be lossy, but showing ".00" on every price adds three characters of noise to the most
 * important number on the page. Non-integral amounts keep their paise, so nothing is ever displayed
 * as a rounded version of what was charged.
 */

const WHOLE = new Intl.NumberFormat('en-IN', {
  maximumFractionDigits: 0,
});

const PAISE = new Intl.NumberFormat('en-IN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** The digits of an amount, grouped Indian-style, with no currency symbol. */
export function formatAmount(value) {
  const n = toNumber(value);
  if (n === null) {
    return null;
  }
  return Number.isInteger(n) ? WHOLE.format(n) : PAISE.format(n);
}

/**
 * A full price as one string, symbol included.
 *
 * Used for titles, alt text, aria-labels and anywhere a string is needed rather than markup. The
 * `<Money>` component is preferred in rendered output, because it can set the symbol at its own
 * optical size.
 */
export function formatPrice(value, currency = 'INR') {
  const digits = formatAmount(value);
  return digits === null ? '—' : symbolFor(currency) + digits;
}

/** The glyph for a currency code. Falls back to the code itself rather than guessing. */
export function symbolFor(currency) {
  switch (currency) {
    case 'INR':
    case undefined:
    case null:
      return '₹';
    case 'USD':
      return '$';
    case 'EUR':
      return '€';
    case 'GBP':
      return '£';
    default:
      return currency + ' ';
  }
}

/**
 * A signed percentage, as words and a number.
 *
 * Returns `null` for a missing input rather than 0, because "no movement" and "we do not know yet"
 * are different facts and rendering the second as the first is how a chart with no history ends up
 * claiming a price is flat.
 */
export function formatPercent(value, { sign = true } = {}) {
  const n = toNumber(value);
  if (n === null) {
    return null;
  }
  const rounded = Math.round(n * 10) / 10;
  const body = (Number.isInteger(rounded) ? rounded : rounded.toFixed(1)) + '%';
  if (!sign || rounded <= 0) {
    return body;
  }
  return '+' + body;
}

/** A plain rounded percentage for rates, where a sign would be meaningless. */
export function formatRate(value) {
  const n = toNumber(value);
  if (n === null) {
    return '—';
  }
  return (Math.round(n * 10) / 10).toFixed(1) + '%';
}

/** A count with its noun pluralised. `formatCount(1, 'retailer')` → "1 retailer". */
export function formatCount(n, singular, plural) {
  const count = toNumber(n) ?? 0;
  const noun = count === 1 ? singular : plural || singular + 's';
  return count + ' ' + noun;
}

/** Large integers with Indian grouping — observation counts, request totals. */
export function formatInteger(value) {
  const n = toNumber(value);
  return n === null ? '—' : WHOLE.format(Math.round(n));
}

/**
 * The difference between two prices, as a ready-to-render object.
 *
 * Direction is returned as a string rather than a sign, because every consumer needs to choose both
 * a colour and an arrow, and deriving those from `Math.sign` in three components is how one of them
 * ends up with the arrow inverted. `null` for direction means there is nothing to compare against.
 */
export function priceDelta(current, previous) {
  const now = toNumber(current);
  const before = toNumber(previous);
  if (now === null || before === null || before === 0) {
    return { direction: null, amount: null, percent: null };
  }
  const amount = now - before;
  if (Math.abs(amount) < 0.005) {
    return { direction: 'flat', amount: 0, percent: 0 };
  }
  return {
    direction: amount < 0 ? 'fall' : 'rise',
    amount: Math.abs(amount),
    percent: Math.abs((amount / before) * 100),
  };
}

/**
 * Coerces the server's JSON into a number.
 *
 * Jackson serialises `BigDecimal` as a JSON number, so these arrive as numbers already — but a
 * `BigDecimal` large enough to lose precision in a double would arrive as a string under some
 * configurations, and `Number('')` is 0, which would render an empty field as a free product. Hence
 * the explicit blank check.
 */
function toNumber(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}
