import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken, setUnauthorizedHandler } from './api.js';

/**
 * Who is signed in.
 *
 * ── The startup sequence, and why it matters ──
 * On every page load, if a token exists in sessionStorage, this provider calls `GET /api/auth/me`
 * before rendering anything that depends on a user. It would be faster to trust the stored token and
 * render immediately, and that is the version that breaks: a token survives a database reset, a user
 * deletion, and a change of JWT secret, so "I have a token" is not the same claim as "I am signed
 * in". Asking the server costs one request and makes the difference impossible to get wrong.
 *
 * Until that request settles, `status` is `'checking'` and the router renders nothing — not a
 * sign-in page. Flashing the sign-in screen to someone who is already signed in is the single most
 * irritating bug in a token-based frontend, and it is caused entirely by treating "not yet known" as
 * "not signed in".
 */
const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [status, setStatus] = useState(() => (getToken() ? 'checking' : 'anonymous'));

  const signOut = useCallback(() => {
    setToken(null);
    setUser(null);
    setStatus('anonymous');
  }, []);

  /*
   * Any 401 from any endpoint ends the session, wherever it came from.
   *
   * This is what makes an expired token a clean trip to the sign-in page rather than a dashboard
   * full of failed panels. Registered once, here, because the fetch layer has no view of React
   * state and the alternative is every call site remembering to check for 401.
   */
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setToken(null);
      setUser(null);
      setStatus('anonymous');
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  useEffect(() => {
    if (status !== 'checking') {
      return undefined;
    }
    const abort = new AbortController();
    api
      .me({ signal: abort.signal })
      .then((me) => {
        setUser(me);
        setStatus('signed-in');
      })
      .catch((e) => {
        if (e.name === 'AbortError') {
          return;
        }
        // Any failure here means the stored token is not usable, including a network failure —
        // in which case nothing else will work either and the sign-in screen is where the user
        // should be, with the real error shown when they try.
        setToken(null);
        setUser(null);
        setStatus('anonymous');
      });
    return () => abort.abort();
  }, [status]);

  const signIn = useCallback(async ({ email, password }) => {
    const session = await api.login(email, password);
    setToken(session.token);
    setUser(session.user);
    setStatus('signed-in');
    return session.user;
  }, []);

  const register = useCallback(async (payload) => {
    const session = await api.register(payload);
    setToken(session.token);
    setUser(session.user);
    setStatus('signed-in');
    return session.user;
  }, []);

  const value = useMemo(
    () => ({
      user,
      status,
      signedIn: status === 'signed-in',
      checking: status === 'checking',
      isAdmin: Boolean(user?.admin),
      signIn,
      register,
      signOut,
    }),
    [user, status, signIn, register, signOut]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    // A thrown error rather than a silent null, because the failure mode of a missing provider is
    // "the whole app renders as anonymous" — which looks like a logic bug a long way from its cause.
    throw new Error('useAuth was called outside AuthProvider');
  }
  return context;
}
