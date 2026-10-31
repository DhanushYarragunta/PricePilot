import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loading something from the API, with the three states a real screen needs.
 *
 * Every page in this app loads data on mount, and each one has to handle four situations: still
 * loading, loaded, failed, and reloading-while-already-showing-something. The last is the one that
 * hand-rolled fetch effects usually get wrong — they clear the data before refetching, so a manual
 * refresh blanks the screen and then repaints it, which looks like a crash and back.
 *
 * `useAsync` keeps the previous data visible during a reload and exposes `reloading` separately, so
 * a page can dim a panel or show a progress mark without losing what the user was reading.
 *
 * ── On aborting ──
 * Every request is given an AbortController tied to the effect, and a superseded request is
 * aborted. This is not a performance nicety: without it, two overlapping loads can settle out of
 * order and the older response wins, putting stale prices on screen with no indication anything is
 * wrong. In a price tracker that is the one bug class worth engineering against directly.
 */
export function useAsync(loader, deps = [], { enabled = true } = {}) {
  const [state, setState] = useState({ data: null, error: null, loading: enabled });
  const [reloading, setReloading] = useState(false);
  const [nonce, setNonce] = useState(0);

  // The loader is a new closure on every render, so it cannot be an effect dependency without
  // re-running forever. A ref keeps the latest one reachable while the effect depends only on the
  // caller's declared deps.
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!enabled) {
      setState({ data: null, error: null, loading: false });
      return undefined;
    }
    const abort = new AbortController();
    let cancelled = false;

    setState((previous) => ({ ...previous, loading: previous.data === null, error: null }));
    setReloading((previous) => previous || nonce > 0);

    loaderRef
      .current({ signal: abort.signal })
      .then((data) => {
        if (cancelled || !mounted.current) {
          return;
        }
        setState({ data, error: null, loading: false });
        setReloading(false);
      })
      .catch((e) => {
        if (cancelled || !mounted.current || e.name === 'AbortError') {
          return;
        }
        // Previous data is deliberately kept on failure. If a reload fails, showing the last known
        // prices with an error banner above them is more useful than replacing a working screen
        // with an error page — and the banner is what says the figures may have moved since.
        setState((previous) => ({ data: previous.data, error: e, loading: false }));
        setReloading(false);
      });

    return () => {
      cancelled = true;
      abort.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, enabled, nonce]);

  /** Re-runs the loader, keeping whatever is on screen until the new data arrives. */
  const reload = useCallback(() => setNonce((n) => n + 1), []);

  /**
   * Replaces the data locally without a request.
   *
   * Used after an action whose response already contains the new truth — a PATCH on a tracker
   * returns the stored settings, so re-fetching the whole dashboard to see them would be a second
   * round trip for information already in hand.
   */
  const patch = useCallback((updater) => {
    setState((previous) => ({
      ...previous,
      data: typeof updater === 'function' ? updater(previous.data) : updater,
    }));
  }, []);

  return {
    data: state.data,
    error: state.error,
    loading: state.loading,
    reloading,
    reload,
    patch,
  };
}

/**
 * Running an action — a refresh, an analysis, a form submit.
 *
 * Separate from `useAsync` because the shapes are genuinely different: an action is triggered by a
 * person, runs once, and its failure belongs next to the button that caused it rather than in place
 * of the page. It also has to be impossible to fire twice, since the three actions this wraps all
 * cost money — a double-submitted refresh is two sets of requests to a retailer, and a
 * double-submitted analysis is twice the AI tokens.
 */
export function useAction(action) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const inFlight = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const actionRef = useRef(action);
  actionRef.current = action;

  const run = useCallback(async (...args) => {
    // The ref, not the state, guards re-entry. State updates are asynchronous, so two clicks in the
    // same tick would both see `busy === false` and both send a request.
    if (inFlight.current) {
      return undefined;
    }
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      return await actionRef.current(...args);
    } catch (e) {
      if (mounted.current) {
        setError(e);
      }
      // Rethrown so the caller can decide whether this was fatal for the flow it is in — a form
      // needs to not navigate away, while a refresh button only needs the message.
      throw e;
    } finally {
      inFlight.current = false;
      if (mounted.current) {
        setBusy(false);
      }
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);

  return { run, busy, error, clearError };
}
