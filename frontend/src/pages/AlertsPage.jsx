import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api.js';
import { useAsync, useAction } from '../lib/useAsync.js';
import Money from '../components/Money.jsx';
import Working from '../components/Working.jsx';
import EmptyState from '../components/EmptyState.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { formatCount } from '../lib/money.js';
import { formatAbsolute, formatDay, formatRelative } from '../lib/time.js';

const LIMIT = 100;

/**
 * Everything the tracker has raised for this person, newest first.
 *
 * Grouped by day rather than paged. An alert's value decays fast — "this dropped below your target"
 * matters today and is history next month — so the shape that fits is a feed with dated headings,
 * not a table with a page control.
 */
export default function AlertsPage() {
  const alerts = useAsync((options) => api.alerts(LIMIT, options), []);

  const mail = useAsync((options) => api.mailStatus(options), []);
  const test = useAction(() => api.testEmail());
  const [testResult, setTestResult] = useState(null);

  const groups = alerts.data ? groupByDay(alerts.data) : [];

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-row">
          <div>
            <h1>Alerts</h1>
            <p className="page-sub">
              {alerts.data
                ? alerts.data.length === 0
                  ? 'Nothing has triggered yet.'
                  : formatCount(alerts.data.length, 'alert') + ', most recent first.'
                : 'Loading.'}
            </p>
          </div>
          {alerts.reloading ? <Working label="Reloading alerts" /> : null}
        </div>
      </header>

      <section className="panel advisor-panel">
        <header className="panel-head"><h2 className="panel-title">Email delivery</h2></header>
        <div className="panel-body stack">
          <p>{mail.data?.message || 'Check delivery to the email address saved on your signed-in account.'}</p>
          {mail.data?.schedulerEnabled === false && <Notice tone="info" title="Automatic checks are disabled locally.">Price checks and buying-plan reassessments run when you request them. Automatic price alerts require background checks to be enabled.</Notice>}
          <div><button type="button" className="btn btn-ghost" disabled={test.busy} onClick={() => test.run().then(setTestResult).catch(() => {})}>{test.busy ? 'Testing email delivery…' : 'Send one test email to my account'}</button></div>
          {testResult && <Notice tone="info" title={testResult.accepted ? 'Mail server accepted the test message.' : 'Test email was not accepted.'}>{testResult.message}</Notice>}
          <ErrorNotice error={test.error || mail.error} what="Email could not be checked" />
          <p className="small muted">A mail server accepting a message does not guarantee inbox delivery. Check Spam and Promotions too.</p>
        </div>
      </section>

      <ErrorNotice
        error={alerts.error}
        what="Your alerts could not be loaded"
        onRetry={alerts.reload}
      />

      {alerts.loading ? <Working label="Loading your alerts" /> : null}

      {alerts.data && alerts.data.length === 0 ? (
        <EmptyState
          title="No alerts yet"
          action={
            <Link to="/products" className="btn btn-ghost btn-sm">
              Set a target price
            </Link>
          }
        >
          An alert is raised when a price reaches the target you set, when a product hits the lowest
          price we have ever recorded for it, when it comes back into stock, or — if the analysis is
          switched on — when the AI decides a price has become genuinely good. Nothing here means
          nothing has happened yet, which is itself worth knowing.
        </EmptyState>
      ) : null}

      {groups.map((group) => (
        <section key={group.key} className="feed-group">
          <h2 className="feed-day">{group.label}</h2>
          <div className="panel">
            {group.items.map((alert) => (
              <AlertRow key={alert.id} alert={alert} />
            ))}
          </div>
        </section>
      ))}

      {alerts.data && alerts.data.length >= LIMIT ? (
        <p className="small faint" style={{ marginTop: 'var(--s-5)' }}>
          Showing the most recent {LIMIT}. Older alerts stay in the database but are not listed here.
        </p>
      ) : null}
    </div>
  );
}

/** One alert. */
function AlertRow({ alert }) {
  return (
    <article className="feed-item">
      <div className={'feed-mark ' + markClass(alert.kind)} aria-hidden="true" />

      <div>
        {/*
         * `kindLabel` comes from the server rather than being mapped here. The enum name is the
         * backend's business; the sentence shown to a person is written once, next to the code that
         * decided to raise the alert, so the label and the reason cannot drift apart.
         */}
        <p className="feed-kind">{alert.kindLabel}</p>
        <h3 className="feed-product">
          <Link to={'/products/' + alert.productId}>{alert.productTitle}</Link>
        </h3>
        <p className="feed-message">{alert.message}</p>

        {alert.deliveryError ? (
          <p className="feed-undelivered">
            Email failed: {alert.deliveryError}. The alert itself is safe — this record is the
            authoritative copy, which is why delivery failures do not discard it.
          </p>
        ) : null}
      </div>

      <div>
        {alert.price ? (
          <div className="feed-price">
            <Money value={alert.price} />
          </div>
        ) : null}
        <time
          className="feed-when"
          dateTime={alert.createdAt}
          title={formatAbsolute(alert.createdAt)}
        >
          {formatRelative(alert.createdAt)}
        </time>
        {alert.delivered ? <div className="feed-when">emailed</div> : null}
      </div>
    </article>
  );
}

/**
 * Which of the three meanings this alert carries.
 *
 * The palette only has vocabulary for direction and for "lowest ever", so every alert kind maps onto
 * one of those rather than getting a colour of its own. A target being hit and a price being judged
 * good are the same news to a shopper, so they share the jade; an all-time low is the one case that
 * earns the reserved accent.
 */
function markClass(kind) {
  switch (kind) {
    case 'ALL_TIME_LOW':
      return 'feed-mark-low';
    case 'TARGET_HIT':
    case 'DEAL_DETECTED':
      return 'feed-mark-good';
    case 'BACK_IN_STOCK':
    default:
      return '';
  }
}

/**
 * Buckets alerts into days, preserving the order the server sent them in.
 *
 * Grouping by the calendar date rather than by elapsed time, so a heading change always lines up
 * with midnight in the reader's own timezone. Bucketing by "within 24 hours" instead produces a
 * Today heading containing yesterday evening, which reads as a bug.
 */
function groupByDay(alerts) {
  const groups = [];
  let current = null;

  alerts.forEach((alert) => {
    const date = new Date(alert.createdAt);
    const key = Number.isNaN(date.getTime())
      ? 'unknown'
      : date.getFullYear() + '-' + (date.getMonth() + 1) + '-' + date.getDate();

    if (!current || current.key !== key) {
      current = { key, label: labelFor(date), items: [] };
      groups.push(current);
    }
    current.items.push(alert);
  });

  return groups;
}

function labelFor(date) {
  if (Number.isNaN(date.getTime())) {
    return 'Undated';
  }
  const now = new Date();
  if (isSameDay(date, now)) {
    return 'Today';
  }

  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (isSameDay(date, yesterday)) {
    return 'Yesterday';
  }

  return formatDay(date.toISOString(), { withYear: date.getFullYear() !== now.getFullYear() });
}

function isSameDay(a, b) {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}
