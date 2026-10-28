import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

/**
 * Transient messages, bottom-right.
 *
 * Used for the outcome of an action the user triggered and then stopped watching — "Checked 3
 * retailers just now", "Added 1 more retailer", "Amazon.in blocked this check". The backend writes
 * these sentences itself (see `ProductController.refreshMessage`), and they are passed through
 * unchanged rather than re-worded here, so the message in the toast is the same one the API would
 * show any other client.
 *
 * ── What does not go in a toast ──
 * Anything the user needs in order to act. A toast disappears, so it cannot carry the only copy of
 * an error that has to be fixed — a bad URL belongs under the field, and a failed load belongs above
 * the panel that failed. Toasts here are confirmations and incidental failures only.
 */
const ToastContext = createContext(null);

const LIFETIME_MS = 6000;
const FAILURE_LIFETIME_MS = 11000;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());
  const nextId = useRef(1);

  const dismiss = useCallback((id) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (toast) => {
      const id = nextId.current++;
      const tone = toast.tone || 'ok';
      setToasts((current) => {
        // Three at a time, oldest dropped. A refresh of six listings that all fail would otherwise
        // stack six toasts up the side of the screen and cover the thing being refreshed.
        const next = [...current, { ...toast, id, tone }];
        return next.slice(-3);
      });
      const lifetime = tone === 'bad' ? FAILURE_LIFETIME_MS : LIFETIME_MS;
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), lifetime)
      );
      return id;
    },
    [dismiss]
  );

  useEffect(
    () => () => {
      timers.current.forEach(clearTimeout);
      timers.current.clear();
    },
    []
  );

  const value = useMemo(
    () => ({
      /** A confirmation. `notify('Checked 3 retailers just now.')` */
      notify: (message, title) => push({ tone: 'ok', message, title }),
      /** A failure the user does not have to act on. */
      warn: (message, title) => push({ tone: 'bad', message, title }),
      /**
       * An ApiError, rendered with its reference id when it has one.
       *
       * The reference is the whole point of including it: on a 500 the user sees eight characters
       * that appear verbatim in the server log beside the stack trace, which turns "it broke" into
       * a single grep.
       */
      report: (error, fallback) =>
        push({
          tone: 'bad',
          title: error?.reference ? 'Reference ' + error.reference : undefined,
          message: error?.message || fallback || 'Something went wrong.',
        }),
      dismiss,
    }),
    [push, dismiss]
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastShelf toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

function ToastShelf({ toasts, onDismiss }) {
  if (toasts.length === 0) {
    return null;
  }
  return (
    /*
     * aria-live="polite" so a screen reader announces the outcome of an action without interrupting
     * what it is currently reading. "assertive" would be wrong: none of these messages is urgent
     * enough to cut someone off mid-sentence.
     */
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={'toast toast-' + toast.tone}>
          <div className="toast-body">
            {toast.title ? <div className="toast-title">{toast.title}</div> : null}
            <div>{toast.message}</div>
          </div>
          <button
            type="button"
            className="toast-close"
            onClick={() => onDismiss(toast.id)}
            aria-label="Dismiss"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast was called outside ToastProvider');
  }
  return context;
}
