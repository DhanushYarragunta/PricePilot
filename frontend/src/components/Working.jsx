/**
 * "Working on it", with the reason named.
 *
 * A bare spinner says only "wait". Every slow thing in this app is slow for a reason the user would
 * want to know — reading a page at Amazon.in takes several seconds, and a Claude analysis takes
 * longer — so the label is required rather than optional. Knowing that the delay is a live request
 * to a retailer, rather than this app being slow, is most of the difference between patience and a
 * second click.
 */
export default function Working({ label, inline = false }) {
  return (
    <span className="working" role="status" aria-live="polite">
      <span className="working-bar" aria-hidden="true" />
      <span>{label}</span>
      {inline ? null : <span className="sr-only">Please wait.</span>}
    </span>
  );
}
