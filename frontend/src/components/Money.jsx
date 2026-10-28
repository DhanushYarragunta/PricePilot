import { formatAmount, formatPrice, symbolFor } from '../lib/money.js';

/**
 * A price.
 *
 * Split into symbol and digits so the ₹ can be set smaller and lighter than the numerals it
 * precedes — see `.money-symbol` in app.css. At 52px an equally weighted rupee sign competes with
 * the number for first glance, which is backwards: the figure is the information, the symbol is
 * context the reader already has.
 *
 * `title` carries the unabbreviated string, which matters for the struck-through MRP: an inflated
 * list price is evidence, and hovering should give the exact figure.
 */
export default function Money({ value, currency = 'INR', struck = false, className = '' }) {
  const digits = formatAmount(value);
  if (digits === null) {
    // An em dash, not "₹0" and not an empty cell. A missing price means we have not read one — a
    // page that was blocked, or a listing added moments ago — and rendering that as zero would be
    // a lie about a number the whole app exists to get right.
    return (
      <span className={'money faint ' + className} title="No price has been read yet">
        —
      </span>
    );
  }
  return (
    <span
      className={'money ' + (struck ? 'money-struck ' : '') + className}
      title={formatPrice(value, currency)}
    >
      <span className="money-symbol">{symbolFor(currency)}</span>
      {digits}
    </span>
  );
}
