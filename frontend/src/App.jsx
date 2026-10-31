import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './lib/auth.jsx';
import { useCapabilities } from './lib/capabilities.jsx';
import Working from './components/Working.jsx';
import LandingPage from './pages/LandingPage.jsx';
import SignInPage from './pages/SignInPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import ProductPage from './pages/ProductPage.jsx';
import AlertsPage from './pages/AlertsPage.jsx';
import MetricsPage from './pages/MetricsPage.jsx';
import AdminPage from './pages/AdminPage.jsx';

/**
 * The shell and the routes.
 *
 * Four of the seven screens need a signed-in user, two are public, and one additionally needs an
 * admin. Rather than check inside each page — where a missing check is invisible — the requirement
 * is expressed once per route by wrapping it, so adding a route without deciding its access level
 * is not possible by omission.
 */
export default function App() {
  const { checking, signedIn } = useAuth();

  /*
   * Nothing renders until the stored token has been checked. The alternative — render the public
   * landing page while `GET /api/auth/me` is in flight — would show the marketing page to a signed-in
   * user for a few hundred milliseconds on every reload, then replace it with their dashboard.
   */
  if (checking) {
    return (
      <div className="shell">
        <Masthead />
        <main className="main">
          <div className="page">
            <Working label="Checking your session" />
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="shell">
      <Masthead />
      <main className="main">
        <Routes>
          <Route
            path="/"
            element={signedIn ? <Navigate to="/products" replace /> : <LandingPage />}
          />
          <Route path="/sign-in" element={<SignInPage mode="sign-in" />} />
          <Route path="/create-account" element={<SignInPage mode="register" />} />
          <Route path="/metrics" element={<MetricsPage />} />

          <Route
            path="/products"
            element={
              <RequireUser>
                <DashboardPage />
              </RequireUser>
            }
          />
          <Route
            path="/products/:productId"
            element={
              <RequireUser>
                <ProductPage />
              </RequireUser>
            }
          />
          <Route
            path="/alerts"
            element={
              <RequireUser>
                <AlertsPage />
              </RequireUser>
            }
          />
          <Route
            path="/admin"
            element={
              <RequireAdmin>
                <AdminPage />
              </RequireAdmin>
            }
          />

          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <SiteFooter />
    </div>
  );
}

/**
 * Sends an anonymous visitor to sign in, remembering where they were going.
 *
 * The `from` state is what makes a shared product link work: click a link to a product while signed
 * out, sign in, and land on that product rather than on a generic dashboard.
 */
function RequireUser({ children }) {
  const { signedIn } = useAuth();
  const location = useLocation();
  if (!signedIn) {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname + location.search }} />;
  }
  return children;
}

/**
 * Admin-only.
 *
 * This is a convenience, not the security boundary — the real check is `hasRole('ADMIN')` on the
 * `/api/admin/**` path rules in SecurityConfig, which no amount of client editing can get past.
 * Hiding the page here just stops a non-admin from reaching a screen where every panel 403s.
 */
function RequireAdmin({ children }) {
  const { signedIn, isAdmin } = useAuth();
  const location = useLocation();
  if (!signedIn) {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname }} />;
  }
  if (!isAdmin) {
    return <Navigate to="/products" replace />;
  }
  return children;
}

function Masthead() {
  const { signedIn, isAdmin, user, signOut } = useAuth();

  return (
    <header className="masthead">
      <div className="page masthead-inner">
        <Link to={signedIn ? '/products' : '/'} className="wordmark">
          <span className="wordmark-mark">₹</span>
          <span>PricePilot</span>
        </Link>

        <nav className="nav" aria-label="Main">
          {signedIn ? (
            <>
              <NavLink to="/products" className={navClass}>
                Products
              </NavLink>
              <NavLink to="/alerts" className={navClass}>
                Alerts
              </NavLink>
              <NavLink to="/metrics" className={navClass}>
                How it performs
              </NavLink>
              {isAdmin ? (
                <NavLink to="/admin" className={navClass}>
                  Operations
                </NavLink>
              ) : null}
              <button type="button" className="nav-link" onClick={signOut}>
                Sign out{user?.displayName ? ' (' + user.displayName + ')' : ''}
              </button>
            </>
          ) : (
            <>
              <NavLink to="/metrics" className={navClass}>
                How it performs
              </NavLink>
              <NavLink to="/sign-in" className={navClass}>
                Sign in
              </NavLink>
              <Link to="/create-account" className="btn btn-sm">
                Create an account
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}

const navClass = ({ isActive }) => (isActive ? 'nav-link nav-link-on' : 'nav-link');

function SiteFooter() {
  const { supportedRetailers, aiEnabled, emailEnabled } = useCapabilities();

  /*
   * The footer states what this deployment is actually doing, in a sentence. On a public demo the
   * most common question is "are these prices real?", and the honest answer belongs where it can be
   * read without signing in.
   */
  return (
    <footer className="footer">
      <div className="page footer-inner">
        <span>
          Prices are read from each retailer&apos;s own product page, by this server, at the time
          shown.
        </span>
        {supportedRetailers.length > 0 ? (
          <span className="faint">{supportedRetailers.join(', ')}</span>
        ) : null}
        {aiEnabled ? <span className="faint">AI deal analysis enabled</span> : null}
        {emailEnabled ? null : <span className="faint">Email alerts are off on this deployment</span>}
      </div>
    </footer>
  );
}

function NotFound() {
  return (
    <div className="page">
      <div className="empty">
        <h1 className="empty-title">That page does not exist</h1>
        <p className="empty-body">
          The link may be out of date, or the product may have been removed from your list.
        </p>
        <Link to="/products" className="btn">
          Go to your products
        </Link>
      </div>
    </div>
  );
}
