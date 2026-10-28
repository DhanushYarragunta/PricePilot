import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { AuthProvider } from './lib/auth.jsx';
import { CapabilitiesProvider } from './lib/capabilities.jsx';
import { ToastProvider } from './lib/toast.jsx';
import './styles/tokens.css';
import './styles/base.css';
import './styles/app.css';

/**
 * Entry point.
 *
 * The provider order is load-bearing. `ToastProvider` is outermost so anything inside it — including
 * an auth failure — can report itself; `CapabilitiesProvider` comes next because it queries a public
 * endpoint and must work before anyone signs in; `AuthProvider` is innermost of the three because
 * its 401 handler needs no other context. `BrowserRouter` wraps all of them so a provider is free to
 * navigate.
 *
 * StrictMode is on, and will double-invoke effects in development. That is kept rather than
 * disabled: it is precisely what surfaces a fetch effect that forgets to abort, and this app makes
 * network requests on nearly every mount.
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <CapabilitiesProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </CapabilitiesProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>
);
