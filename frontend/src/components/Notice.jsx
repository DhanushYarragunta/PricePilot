/**
 * A persistent message: an error above a panel, or a note explaining why something is switched off.
 *
 * Distinct from a toast, and the distinction is the rule. A toast disappears, so it can only carry
 * information the user does not need to act on. Anything that explains why the screen looks the way
 * it does — a failed load, a disabled feature, a rate limit that has been reached — has to stay on
 * screen next to the thing it is about.
 */
export default function Notice({ tone = 'info', title, children, action }) {
  return (
    <div className={'notice notice-' + tone} role={tone === 'bad' ? 'alert' : undefined}>
      {title ? <strong>{title}</strong> : null}
      {title ? ' ' : null}
      {children}
      {action ? <div className="row" style={{ marginTop: 'var(--s-3)' }}>{action}</div> : null}
    </div>
  );
}

/**
 * A failed request, rendered from the error the server sent.
 *
 * `ApiError.message` is the backend's own sentence — written for a person, in
 * `ApiExceptionHandler` — so it is shown as-is rather than replaced with something generic. The
 * reference id is appended on a 500, because it is the eight characters that connect what the user
 * saw to the stack trace in the server log.
 */
export function ErrorNotice({ error, what, onRetry }) {
  if (!error) {
    return null;
  }
  return (
    <Notice tone="bad" title={what ? what + ':' : undefined}>
      {error.message}
      {error.reference ? ' (reference ' + error.reference + ')' : ''}
      {error.retryable && onRetry ? (
        <>
          {' '}
          <button type="button" className="btn btn-sm btn-ghost" onClick={onRetry}>
            Try again
          </button>
        </>
      ) : null}
    </Notice>
  );
}
