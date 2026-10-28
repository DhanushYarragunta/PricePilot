import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth.jsx';
import { useCapabilities } from '../lib/capabilities.jsx';
import { useAction } from '../lib/useAsync.js';
import Field from '../components/Field.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';

/**
 * Sign in and create an account, from one component.
 *
 * Two routes, one file, because the forms differ by exactly two fields and keeping them apart
 * guarantees that a change to the password rules or the error handling lands in one of them and not
 * the other. `mode` is passed by the route rather than read from the pathname, so the routing table
 * in App.jsx stays the single place the URLs are written down.
 */
export default function SignInPage({ mode = 'sign-in' }) {
  const registering = mode === 'register';
  const { signIn, register, signedIn, checking } = useAuth();
  const caps = useCapabilities();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  /*
   * Where to go afterwards.
   *
   * RequireUser stores the page it bounced the visitor away from in `location.state.from`. Sending
   * them back there instead of to the dashboard is the difference between a bookmarked product link
   * working and a bookmarked product link dumping them on a list.
   */
  const destination = location.state?.from || '/products';

  const submit = useAction(async () => {
    setFieldErrors({});
    const trimmedEmail = email.trim();

    if (registering) {
      if (password.length < 8) {
        setFieldErrors({ password: 'Use at least 8 characters.' });
        throw new Error('password too short');
      }
      await register({
        email: trimmedEmail,
        password,
        displayName: displayName.trim() === '' ? undefined : displayName.trim(),
        inviteCode: inviteCode.trim() === '' ? undefined : inviteCode.trim(),
      });
    } else {
      await signIn({ email: trimmedEmail, password });
    }

    // `replace`, so the back button does not return to a sign-in form that would immediately
    // redirect forward again.
    navigate(destination, { replace: true });
  });

  if (signedIn && !checking) {
    return <Navigate to={destination} replace />;
  }

  return (
    <div className="auth">
      <div className="auth-panel stack">
        <div>
          <h1>{registering ? 'Create an account' : 'Sign in'}</h1>
          <p className="page-sub">
            {registering
              ? 'One account holds your tracked products, your target prices and your alerts.'
              : 'Your tracked products and alerts are waiting.'}
          </p>
        </div>

        {registering && caps.inviteRequired ? (
          <Notice tone="info" title="This deployment is invite-only.">
            It runs on a single small server, and every account adds retailer requests to the same
            polite budget. An invite code is needed to register.
          </Notice>
        ) : null}

        <form
          className="stack"
          onSubmit={(event) => {
            event.preventDefault();
            submit.run().catch((error) => {
              if (error.fields) {
                setFieldErrors(error.fields);
              }
            });
          }}
        >
          <Field
            label="Email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={setEmail}
            error={fieldErrors.email}
            required
          />

          {registering ? (
            <Field
              label="Your name"
              autoComplete="name"
              value={displayName}
              onChange={setDisplayName}
              error={fieldErrors.displayName}
              hint="Optional. Used to address alert emails."
            />
          ) : null}

          <Field
            label="Password"
            type="password"
            /*
             * The autoComplete value differs by mode and it matters: `new-password` is what makes a
             * password manager offer to generate and save one, `current-password` is what makes it
             * fill the existing one. Using the same token for both means the manager guesses, and it
             * guesses wrong about half the time.
             */
            autoComplete={registering ? 'new-password' : 'current-password'}
            value={password}
            onChange={setPassword}
            error={fieldErrors.password}
            hint={registering ? 'At least 8 characters.' : undefined}
            required
          />

          {registering && caps.inviteRequired ? (
            <Field
              label="Invite code"
              value={inviteCode}
              onChange={setInviteCode}
              error={fieldErrors.inviteCode}
              required
            />
          ) : null}

          <button type="submit" className="btn" disabled={submit.busy}>
            {submit.busy
              ? registering
                ? 'Creating your account…'
                : 'Signing in…'
              : registering
                ? 'Create account'
                : 'Sign in'}
          </button>

          {/*
           * Field-level errors are already rendered under their fields, so this only fires for the
           * rest: wrong password, email already registered, a bad invite code, or the server being
           * unreachable. The backend's own sentence is shown rather than a rewritten one.
           */}
          {submit.error && !submit.error.fields ? (
            <ErrorNotice
              error={submit.error}
              what={registering ? 'Account not created' : 'Not signed in'}
            />
          ) : null}
        </form>

        <p className="auth-switch">
          {registering ? (
            <>
              Already have an account? <Link to="/sign-in">Sign in</Link>
            </>
          ) : (
            <>
              No account yet? <Link to="/create-account">Create one</Link>
            </>
          )}
          <br />
          <span className="faint">
            Your password is stored as a bcrypt hash and never leaves this server. Your session is
            held in this browser tab only, so closing the tab signs you out.
          </span>
        </p>
      </div>
    </div>
  );
}
