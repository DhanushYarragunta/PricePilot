import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import { createProductRefreshSession } from './productRefresh.js';

export function useProductRefresh(productId, onCompleted) {
  const [state, setState] = useState({ job: null, busy: false, error: null, resumable: false });
  const session = useRef(null);
  const completed = useRef({ productId, onCompleted });
  completed.current = { productId, onCompleted };
  useEffect(() => {
    let storage;
    try { storage = window.sessionStorage; } catch { /* Private browsing fallback. */ }
    const current = createProductRefreshSession({ productId, storage,
      startJob: (options) => api.refresh(productId, options),
      readJob: (id, options) => api.refreshStatus(productId, id, options),
      onChange: (value) => { if (completed.current.productId === productId) setState(value); },
      onCompleted: (job, options) => {
        if (completed.current.productId === productId) return completed.current.onCompleted(job, options);
        throw new DOMException('Product changed.', 'AbortError');
      },
    });
    session.current = current;
    setState(current.snapshot());
    current.resume();
    return () => { current.dispose(); if (session.current === current) session.current = null; };
  }, [productId]);
  const run = useCallback(() => session.current?.start() ?? Promise.resolve(null), []);
  return { ...state, run };
}
