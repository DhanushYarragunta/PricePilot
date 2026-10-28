/**
 * An empty screen, treated as an invitation rather than an absence.
 *
 * Every empty state in this app names the next action and says what will happen. "No products yet"
 * on its own is a dead end; "Paste a link from Amazon.in, Flipkart or Croma above — the first price
 * is read while you wait" tells someone what to do and sets the expectation that it will take a few
 * seconds, which is the thing they would otherwise wonder about.
 */
export default function EmptyState({ title, children, action }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children ? <p className="empty-body">{children}</p> : null}
      {action || null}
    </div>
  );
}
