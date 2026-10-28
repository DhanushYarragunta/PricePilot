import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { api } from './api.js';

/**
 * What this deployment can actually do.
 *
 * Fetched once, before the app renders its first page, from the public
 * `GET /api/meta/capabilities`. Every paid integration in this project is optional — no Claude key
 * means no verdicts, no Tavily key means no "find other retailers", no SMTP means alerts appear in
 * the app but never in an inbox — and the backend is built to degrade rather than fail in each case.
 *
 * This provider is what turns that into something a user can see. A button that calls a disabled
 * feature and returns a 503 is worse than no button: it reads as a broken app rather than as an
 * unconfigured one. So the UI asks first and hides what is switched off, with a sentence saying why.
 *
 * ── Why a failure here is not fatal ──
 * If this request fails, the app renders with `defaults` below: everything assumed off, the limits
 * set to the backend's own defaults. That means a visitor on a deployment whose capabilities
 * endpoint is briefly unreachable gets a conservative but working price tracker, rather than a blank
 * screen. The one thing never assumed is a capability — claiming AI is available when the key is
 * missing would produce exactly the broken-button experience this exists to prevent.
 */
const CapabilitiesContext = createContext(null);

const defaults = {
  aiEnabled: false,
  searchEnabled: false,
  emailEnabled: false,
  inviteRequired: false,
  supportedRetailers: [],
  // Mirrors `pricepilot.limits` in the backend's application.yml. These are only ever used for the
  // moment before the real values arrive, or if that request fails, so they are copied from there
  // rather than guessed — a fallback that quotes a limit the server does not enforce is a sentence
  // on screen that is simply untrue.
  maxTrackedProducts: 40,
  maxRetailersPerProduct: 6,
  manualRefreshPerHour: 30,
  historyWindowDays: 30,
};

export function CapabilitiesProvider({ children }) {
  const [state, setState] = useState({ loaded: false, data: defaults, failed: false });

  useEffect(() => {
    const abort = new AbortController();
    api
      .capabilities({ signal: abort.signal })
      .then((data) => setState({ loaded: true, data: { ...defaults, ...data }, failed: false }))
      .catch((e) => {
        if (e.name === 'AbortError') {
          return;
        }
        setState({ loaded: true, data: defaults, failed: true });
      });
    return () => abort.abort();
  }, []);

  const value = useMemo(
    () => ({ ...state.data, loaded: state.loaded, unreachable: state.failed }),
    [state]
  );

  return <CapabilitiesContext.Provider value={value}>{children}</CapabilitiesContext.Provider>;
}

export function useCapabilities() {
  const context = useContext(CapabilitiesContext);
  if (!context) {
    throw new Error('useCapabilities was called outside CapabilitiesProvider');
  }
  return context;
}
