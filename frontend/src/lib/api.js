/**
 * The one place that talks to the backend.
 *
 * Three jobs, and nothing else: attach the bearer token, turn a non-2xx response into a thrown
 * ApiError carrying the server's own message, and expose one named function per endpoint. Having a
 * single module means the error shape is parsed once rather than in thirty call sites, and it means
 * a change to the contract breaks in one file instead of leaking into components.
 *
 * ── Why relative URLs ──
 * Every path here starts with `/api`. In development Vite proxies that to localhost:8080; in
 * production nginx proxies it to the container on the same host. No base URL, no environment
 * variable, no `import.meta.env` branch — so there is no configuration that can be wrong in one
 * environment and right in the other, which is the commonest cause of "it worked locally".
 */

/** The server's error body, as thrown. */
export class ApiError extends Error {
  constructor(status, payload) {
    // The server's message is written for a person to read, so it is the message. The fallbacks
    // below only cover the cases where there is no JSON body at all.
    super(payload?.message || fallbackMessage(status));
    this.name = 'ApiError';
    this.status = status;
    this.code = payload?.code || 'unknown';
    /** Per-field validation messages, keyed by field name. Present only on a validation failure. */
    this.fields = payload?.fields || null;
    /** The eight-character id the server logged alongside the stack trace, on a 500. */
    this.reference = payload?.reference || null;
  }

  /** True when re-sending the same request later could plausibly work. */
  get retryable() {
    return this.status === 429 || this.status === 503 || this.status >= 500;
  }
}

function fallbackMessage(status) {
  if (status === 0) {
    return 'Could not reach the server. Check your connection and try again.';
  }
  if (status === 401) {
    return 'Your session has expired. Please sign in again.';
  }
  if (status === 404) {
    return 'That is no longer there.';
  }
  return 'The request failed (HTTP ' + status + ').';
}

// ──────────────────────────────────────────────────────────────────── the token

const TOKEN_KEY = 'pricepilot.token';

/*
 * sessionStorage, deliberately — not localStorage, and not a cookie.
 *
 * Against localStorage: the token outlives the tab, so a shared or public browser keeps someone
 * signed in indefinitely. sessionStorage still survives a page refresh, which is what matters for a
 * demo, and clears when the tab closes.
 *
 * Against a cookie: a cookie is attached by the browser to every request automatically, which is
 * what makes CSRF possible in the first place. SecurityConfig disables CSRF protection and its
 * javadoc justifies that by saying the token is a header bearer token never stored in a cookie —
 * so storing it in one here would quietly invalidate the reasoning recorded in the backend.
 *
 * The trade-off being accepted: script running on this origin can read sessionStorage, so a
 * successful XSS can steal the token. That is true of any client-readable storage; the mitigation is
 * that this app renders no user-supplied HTML anywhere — React escapes text by default and
 * dangerouslySetInnerHTML appears nowhere in this codebase.
 */
let memoryToken = null;

export function getToken() {
  if (memoryToken) {
    return memoryToken;
  }
  try {
    memoryToken = sessionStorage.getItem(TOKEN_KEY);
  } catch {
    // Private-mode Safari throws on access. An in-memory token still gets the user through this
    // tab, so this is a degradation rather than a failure.
    memoryToken = null;
  }
  return memoryToken;
}

export function setToken(token) {
  memoryToken = token || null;
  try {
    if (token) {
      sessionStorage.setItem(TOKEN_KEY, token);
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    /* in-memory only; see above */
  }
}

/**
 * Called when any request comes back 401, so the shell can drop to the sign-in screen.
 *
 * A callback registered by the auth provider rather than an import of it, because importing React
 * state into the fetch layer would make this module unusable from anywhere else and would create a
 * cycle: auth.jsx already imports this file.
 */
let onUnauthorized = null;

export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

// ──────────────────────────────────────────────────────────────────── the fetch

async function request(method, path, body, options = {}) {
  const headers = {};
  const token = getToken();
  if (token) {
    headers.Authorization = 'Bearer ' + token;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: options.signal,
    });
  } catch (e) {
    // An aborted request is not a failure — it is a component that unmounted, or a second refresh
    // superseding the first. Rethrowing as an ApiError would show the user a toast about it.
    if (e.name === 'AbortError') {
      throw e;
    }
    throw new ApiError(0, null);
  }

  if (response.status === 204) {
    return null;
  }

  // Read the body before branching on status: an error body is the useful half of an error, and a
  // response that is both non-2xx and non-JSON (an nginx 502 page, say) must not throw while
  // parsing and lose the status code.
  let payload = null;
  const type = response.headers.get('Content-Type') || '';
  if (type.includes('application/json')) {
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }
  }

  if (!response.ok) {
    if (response.status === 401 && onUnauthorized) {
      onUnauthorized();
    }
    throw new ApiError(response.status, payload);
  }
  return payload;
}

const get = (path, options) => request('GET', path, undefined, options);
const post = (path, body, options) => request('POST', path, body ?? {}, options);
const patch = (path, body, options) => request('PATCH', path, body ?? {}, options);
const del = (path, options) => request('DELETE', path, undefined, options);

// ──────────────────────────────────────────────────────────────────── endpoints

/*
 * One function per route, named for what the user is doing rather than for the HTTP verb. Keeping
 * them grouped and flat makes the whole API surface readable in one screen, which is the quickest
 * way to spot that a component is calling something that no longer exists.
 */
export const api = {
  // auth
  register: (payload) => post('/api/auth/register', payload),
  login: (email, password) => post('/api/auth/login', { email, password }),
  me: (options) => get('/api/auth/me', options),

  // products
  products: (options) => get('/api/products', options),
  product: (id, options) => get('/api/products/' + id, options),
  track: (payload) => post('/api/products', payload),
  addRetailer: (productId, url) => post('/api/products/' + productId + '/listings', { url }),
  makePrimary: (productId, listingId) =>
    post('/api/products/' + productId + '/listings/' + listingId + '/primary'),
  refresh: (productId) => post('/api/products/' + productId + '/refresh'),
  analyse: (productId) => post('/api/products/' + productId + '/analyse'),
  discover: (productId) => post('/api/products/' + productId + '/discover'),

  // trackers
  updateTracker: (trackerId, payload) => patch('/api/trackers/' + trackerId, payload),
  untrack: (trackerId) => del('/api/trackers/' + trackerId),

  // alerts
  alerts: (limit, options) => get('/api/alerts?limit=' + (limit || 50), options),

  // public
  metrics: (windowDays, options) => get('/api/metrics?windowDays=' + (windowDays || 0), options),
  capabilities: (options) => get('/api/meta/capabilities', options),

  // admin
  schedulerState: (options) => get('/api/admin/scheduler', options),
  diagnostics: (windowDays, options) =>
    get('/api/admin/diagnostics?windowDays=' + (windowDays || 30), options),
  reloadRules: () => post('/api/admin/rules/reload'),
  reviveListing: (listingId) => post('/api/admin/listings/' + listingId + '/revive'),
  pauseListing: (listingId) => post('/api/admin/listings/' + listingId + '/pause'),
};
