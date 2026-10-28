import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../lib/api.js';
import { useAsync, useAction } from '../lib/useAsync.js';
import { useCapabilities } from '../lib/capabilities.jsx';
import { useToast } from '../lib/toast.jsx';
import Money from '../components/Money.jsx';
import Delta from '../components/Delta.jsx';
import Field, { CheckField } from '../components/Field.jsx';
import Working from '../components/Working.jsx';
import PriceChart from '../components/PriceChart.jsx';
import CheckLog from '../components/CheckLog.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { VerdictBlock, VerdictHistory } from '../components/Verdict.jsx';
import { formatCount, formatInteger, formatPercent } from '../lib/money.js';
import { formatAbsolute, formatDay, formatInterval, formatRelative, formatUntil } from '../lib/time.js';

/**
 * One product, in full.
 *
 * The page is ordered by what a shopper actually wants, in order: what does it cost now, how has it
 * moved, where else is it sold, what does the analysis think, and — last, and unusually — every
 * check we have ever made. That final panel is the one that makes the rest credible.
 */
export default function ProductPage() {
  const { productId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const caps = useCapabilities();

  const product = useAsync((options) => api.product(productId, options), [productId]);
  const detail = product.data;

  /*
   * The one animated moment in the application.
   *
   * `priceMoved` is set only when a refresh returns a figure different from the one already on
   * screen, so the price animates when a retailer's price genuinely changed in the last second —
   * and at no other time. Nothing here animates on mount, on scroll or on hover, which is what
   * leaves this motion with a meaning.
   */
  const [priceMoved, setPriceMoved] = useState(false);
  const shownPrice = useRef(null);

  useEffect(() => {
    if (detail?.stats?.current !== undefined) {
      shownPrice.current = detail.stats.current;
    }
  }, [detail]);

  useEffect(() => {
    if (!priceMoved) {
      return undefined;
    }
    const timer = setTimeout(() => setPriceMoved(false), 600);
    return () => clearTimeout(timer);
  }, [priceMoved]);

  const refresh = useAction(async () => {
    const before = shownPrice.current;
    const summary = await api.refresh(productId);
    const changed = summary.results.some(
      (report) => report.success && report.previousPrice && report.dropPercent
    );
    await reloadAndCompare(before, changed);
    if (summary.succeeded === 0 && summary.requestsMade > 0) {
      toast.warn(summary.message, 'Nothing could be read');
    } else {
      toast.notify(summary.message, 'Checked');
    }
    return summary;
  });

  const analyse = useAction(async () => {
    const verdict = await api.analyse(productId);
    product.patch((current) => ({ ...current, verdict }));
    toast.notify(verdict.headline || verdict.label, 'Claude has looked again');
    return verdict;
  });

  const discover = useAction(async () => {
    const outcome = await api.discover(productId);
    if (outcome.added > 0) {
      product.reload();
      toast.notify(outcome.message, 'Found another retailer');
    } else {
      toast.notify(outcome.message, 'Nothing new');
    }
    return outcome;
  });

  async function reloadAndCompare(before, changed) {
    const fresh = await api.product(productId);
    product.patch(fresh);
    const after = fresh?.stats?.current;
    if (changed || (before !== null && after !== undefined && Number(before) !== Number(after))) {
      setPriceMoved(true);
    }
    shownPrice.current = after ?? before;
  }

  if (product.loading) {
    return (
      <div className="page">
        <Working label="Loading this product" />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="page">
        <ErrorNotice error={product.error} what="This product could not be loaded" onRetry={product.reload} />
        <p style={{ marginTop: 'var(--s-4)' }}>
          <Link to="/products" className="btn btn-ghost">
            Back to your products
          </Link>
        </p>
      </div>
    );
  }

  const primary = detail.listings.find((listing) => listing.primary) || detail.listings[0];
  const cheapest = cheapestOf(detail.listings);

  return (
    <div className="page">
      <Link to="/products" className="back-link">
        ← Your products
      </Link>

      <ErrorNotice error={product.error} what="The latest data could not be loaded" onRetry={product.reload} />

      <section className="product-hero">
        <div className="product-hero-image">
          {detail.imageUrl ? <img src={detail.imageUrl} alt="" /> : <span className="faint">₹</span>}
        </div>
        <div className="product-hero-body">
          <h1 className="product-title">{detail.title}</h1>

          <div className="product-price">
            <span className={'product-price-now' + (priceMoved ? ' product-price-changed' : '')}>
              <Money value={detail.stats.current} currency={detail.currency} />
            </span>
            <span className="product-price-meta">
              {cheapest ? (
                <span className="muted small">
                  at {cheapest.retailer}
                  {cheapest.inStock ? '' : ', out of stock'}
                </span>
              ) : null}
              {detail.stats.vsAveragePercent === null ||
              detail.stats.vsAveragePercent === undefined ? null : (
                <Delta
                  percent={-detail.stats.vsAveragePercent}
                  label={'vs its ' + detail.stats.windowDays + '-day average'}
                />
              )}
              {detail.targetMet ? <span className="targetflag">target met</span> : null}
              {isAtLow(detail) ? <span className="lowflag">lowest yet</span> : null}
            </span>
          </div>

          <div className="product-actions">
            <button type="button" className="btn" onClick={() => refresh.run().catch(noop)} disabled={refresh.busy}>
              {refresh.busy ? 'Checking every retailer…' : 'Check prices now'}
            </button>

            {/*
             * Both optional buttons are hidden rather than disabled when their integration is not
             * configured. A greyed-out button invites a click and then explains itself; an absent
             * one is simply an app that does not have that feature, which is the truth.
             */}
            {caps.aiEnabled && detail.aiAvailable ? (
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => analyse.run().catch(noop)}
                disabled={analyse.busy}
              >
                {analyse.busy ? 'Asking Claude…' : 'Ask Claude again'}
              </button>
            ) : null}

            {caps.searchEnabled && detail.discoveryAvailable ? (
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => discover.run().catch(noop)}
                disabled={discover.busy}
              >
                {discover.busy ? 'Searching…' : 'Find other retailers'}
              </button>
            ) : null}

            {refresh.busy ? <Working label="Reading the retailers' pages" inline /> : null}

            <span className="product-allowance">
              {primary
                ? 'Checked automatically ' +
                  formatInterval(primary.checkIntervalMinutes) +
                  ', next ' +
                  formatUntil(primary.nextCheckAt) +
                  '. Manual checks are limited to ' +
                  caps.manualRefreshPerHour +
                  ' an hour, which is what keeps this polite enough that retailers keep answering.'
                : null}
            </span>
          </div>

          {refresh.error ? <ErrorNotice error={refresh.error} what="The check failed" /> : null}
          {analyse.error ? <ErrorNotice error={analyse.error} what="The analysis failed" /> : null}
          {discover.error ? <ErrorNotice error={discover.error} what="The search failed" /> : null}
        </div>
      </section>

      <Stats stats={detail.stats} currency={detail.currency} />

      <div className="product-grid">
        <div className="stack">
          <section className="panel">
            <header className="panel-head">
              <h2 className="panel-title">Price history</h2>
              <span className="small muted">last {detail.seriesDays} days</span>
            </header>
            <div className="panel-body panel-body-flush">
              <PriceChart
                series={detail.series}
                currency={detail.currency}
                windowDays={detail.seriesDays}
              />
            </div>
          </section>

          <section className="panel">
            <header className="panel-head">
              <h2 className="panel-title">Where it&apos;s sold</h2>
              <span className="small muted">
                {formatCount(detail.listings.length, 'retailer')} of {caps.maxRetailersPerProduct}
              </span>
            </header>
            <div className="panel-body panel-body-flush">
              <Listings
                detail={detail}
                onChanged={product.reload}
                onPrimaryChanged={() => {
                  product.reload();
                  toast.notify('That retailer now represents this product in your list.', 'Changed');
                }}
              />
            </div>
          </section>

          {caps.aiEnabled ? (
            <section className="panel">
              <header className="panel-head">
                <h2 className="panel-title">Claude&apos;s read</h2>
                {analyse.busy ? <Working label="Thinking" inline /> : null}
              </header>
              <div className="panel-body stack">
                <VerdictBlock
                  verdict={detail.verdict}
                  currency={detail.currency}
                  currentPrice={detail.stats.current}
                />
                {detail.verdictHistory?.length > 0 ? (
                  <>
                    <h3 className="small muted">Earlier verdicts</h3>
                    <VerdictHistory entries={detail.verdictHistory} currency={detail.currency} />
                  </>
                ) : null}
              </div>
            </section>
          ) : null}

          {/*
           * The receipt. Last on the page because it is the least glamorous and the most important:
           * everything above it is a claim about prices, and this is the evidence for the claim.
           */}
          <section className="panel">
            <header className="panel-head">
              <h2 className="panel-title">Every check we made</h2>
              <span className="small muted">most recent first</span>
            </header>
            <div className="panel-body panel-body-flush">
              <CheckLog checks={detail.recentChecks} />
            </div>
          </section>
        </div>

        <aside className="side">
          <TrackerSettings
            detail={detail}
            onSaved={(settings) => {
              product.patch((current) => ({
                ...current,
                targetPrice: settings.targetPrice,
                notifyOnDeal: settings.notifyOnDeal,
                note: settings.note,
              }));
              toast.notify('Your alert settings for this product are saved.', 'Saved');
            }}
            onRemoved={() => {
              toast.notify('Removed from your list.', 'Done');
              navigate('/products');
            }}
          />

          <AddRetailer
            productId={detail.productId}
            atLimit={detail.listings.length >= caps.maxRetailersPerProduct}
            onAdded={(added) => {
              product.reload();
              toast.notify(added.notice || 'Added ' + added.retailer + '.', 'Added');
            }}
          />
        </aside>
      </div>
    </div>
  );
}

/** The statistics strip. */
function Stats({ stats, currency }) {
  return (
    <section className="stats" aria-label="Price statistics">
      <div className="stat">
        <div className="stat-label">Lowest ever seen</div>
        <div className="stat-value">
          <Money value={stats.allTimeLow} currency={currency} />
        </div>
        <div className="stat-note">
          {stats.vsLowPercent === null || stats.vsLowPercent === undefined
            ? 'nothing to compare yet'
            : stats.vsLowPercent <= 0
              ? 'this is it'
              : formatPercent(stats.vsLowPercent, { sign: false }) + ' above it now'}
        </div>
      </div>
      <div className="stat">
        <div className="stat-label">Highest ever seen</div>
        <div className="stat-value">
          <Money value={stats.allTimeHigh} currency={currency} />
        </div>
        <div className="stat-note">over the whole time we have watched it</div>
      </div>
      <div className="stat">
        <div className="stat-label">Usual price</div>
        <div className="stat-value">
          <Money value={stats.averageInWindow} currency={currency} />
        </div>
        <div className="stat-note">mean over {stats.windowDays} days</div>
      </div>
      <div className="stat">
        <div className="stat-label">Readings behind these</div>
        {/*
         * The observation count sits in the strip rather than in a tooltip, because it is what
         * tells a reader how much the three figures beside it are worth. An average over four
         * readings and an average over four hundred look identical until this number is visible.
         */}
        <div className="stat-value">{formatInteger(stats.observations)}</div>
        <div className="stat-note">
          since {formatDay(stats.trackingSince, { withYear: true })}
        </div>
      </div>
    </section>
  );
}

/** The retailer table. */
function Listings({ detail, onPrimaryChanged }) {
  const setPrimary = useAction(async (listingId) => {
    await api.makePrimary(detail.productId, listingId);
    onPrimaryChanged();
  });

  return (
    <div className="table-scroll">
      <table className="table">
        <thead>
          <tr>
            <th>Retailer</th>
            <th className="table-num">Price</th>
            <th className="table-num">Listed at</th>
            <th>Health</th>
            <th>Last read</th>
            <th>Next check</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {detail.listings.map((listing) => (
            <tr key={listing.listingId} className={listing.primary ? 'table-row-primary' : undefined}>
              <td>
                <div className="retailer-name">{listing.retailer}</div>
                <a
                  className="retailer-link"
                  href={listing.url}
                  target="_blank"
                  // noreferrer alongside noopener: the retailer has no business knowing which page
                  // sent the visitor, and this is the link a user clicks to verify a price.
                  rel="noopener noreferrer"
                >
                  open the page we read ↗
                </a>
              </td>
              <td className="table-num">
                <Money value={listing.price} currency={detail.currency} />
                {listing.inStock ? null : <div className="micro">out of stock</div>}
              </td>
              <td className="table-num">
                {listing.listPrice ? (
                  <>
                    <Money value={listing.listPrice} currency={detail.currency} struck />
                    {listing.claimedDiscountPercent ? (
                      <div className="micro">claims {listing.claimedDiscountPercent}% off</div>
                    ) : null}
                  </>
                ) : (
                  <span className="faint">—</span>
                )}
              </td>
              <td>
                <StatusBadge
                  status={listing.status}
                  consecutiveFailures={listing.consecutiveFailures}
                  note={listing.statusNote}
                />
                {listing.lastStrategy ? (
                  <div className="machine" title="Which extraction method read this price">
                    {listing.lastStrategy}
                  </div>
                ) : null}
              </td>
              <td title={formatAbsolute(listing.observedAt)}>
                {formatRelative(listing.lastSuccessAt || listing.observedAt)}
              </td>
              <td title={formatAbsolute(listing.nextCheckAt)}>
                {formatUntil(listing.nextCheckAt)}
                <div className="micro">{formatInterval(listing.checkIntervalMinutes)}</div>
              </td>
              <td>
                {listing.primary ? (
                  <span className="micro">represents this product</span>
                ) : (
                  <button
                    type="button"
                    className="btn btn-sm btn-quiet"
                    onClick={() => setPrimary.run(listing.listingId).catch(noop)}
                    disabled={setPrimary.busy}
                  >
                    Use this one
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {setPrimary.error ? (
        <div style={{ padding: 'var(--s-4)' }}>
          <ErrorNotice error={setPrimary.error} what="That could not be changed" />
        </div>
      ) : null}
    </div>
  );
}

/** Target price, deal alerts, note, and removal. */
function TrackerSettings({ detail, onSaved, onRemoved }) {
  const caps = useCapabilities();
  const [target, setTarget] = useState(detail.targetPrice ? String(detail.targetPrice) : '');
  const [notify, setNotify] = useState(detail.notifyOnDeal);
  const [note, setNote] = useState(detail.note || '');
  const [fieldErrors, setFieldErrors] = useState({});
  const [confirming, setConfirming] = useState(false);

  const save = useAction(async () => {
    setFieldErrors({});
    const payload = { notifyOnDeal: notify, note };
    const trimmed = target.trim();
    if (trimmed === '') {
      // The explicit flag, not `targetPrice: null`. A record deserialises an absent field and a
      // null field identically, so without this there is no way to remove a target once set — and
      // a target that cannot be removed keeps sending alerts the user has stopped wanting.
      payload.clearTarget = true;
    } else {
      const parsed = Number(trimmed);
      if (!Number.isFinite(parsed) || parsed <= 0) {
        setFieldErrors({ targetPrice: 'Enter a price like 129900, or clear the box.' });
        throw new Error('invalid target');
      }
      payload.targetPrice = parsed;
    }
    const settings = await api.updateTracker(detail.trackerId, payload);
    onSaved(settings);
    return settings;
  });

  const remove = useAction(async () => {
    await api.untrack(detail.trackerId);
    onRemoved();
  });

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">Tell me when</h2>
      </header>
      <form
        className="panel-body stack"
        onSubmit={(event) => {
          event.preventDefault();
          save.run().catch((e) => {
            if (e.fields) {
              setFieldErrors(e.fields);
            }
          });
        }}
      >
        <Field
          label="Price drops to"
          inputMode="decimal"
          placeholder="129900"
          value={target}
          onChange={setTarget}
          error={fieldErrors.targetPrice}
          hint="Leave blank for no target."
        />

        {caps.aiEnabled ? (
          <CheckField
            label="Also tell me when the verdict improves"
            hint="When an analysis moves this product to good value or better."
            checked={notify}
            onChange={setNotify}
          />
        ) : null}

        <Field
          label="Note"
          as="textarea"
          value={note}
          onChange={setNote}
          error={fieldErrors.note}
          placeholder="Why you are watching this"
        />

        {caps.emailEnabled ? null : (
          <Notice tone="info">
            Email is not configured on this deployment, so alerts appear in your alerts feed but are
            not sent to your inbox.
          </Notice>
        )}

        <div className="row">
          <button type="submit" className="btn" disabled={save.busy}>
            {save.busy ? 'Saving…' : 'Save'}
          </button>
        </div>

        {save.error && !save.error.fields ? (
          <ErrorNotice error={save.error} what="Not saved" />
        ) : null}

        <div style={{ borderTop: 'var(--line)', paddingTop: 'var(--s-4)' }}>
          {confirming ? (
            <div className="stack">
              {/*
               * What is actually being deleted, said plainly. The tracker is this person's
               * relationship to a shared product: the price history survives, which matters
               * because re-adding the same URL later picks the history back up rather than
               * starting from nothing.
               */}
              <p className="small">
                This removes the product from your list and stops your alerts. The price history
                stays, so adding the same link again later picks up where this left off.
              </p>
              <div className="row">
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  onClick={() => remove.run().catch(noop)}
                  disabled={remove.busy}
                >
                  {remove.busy ? 'Removing…' : 'Remove it'}
                </button>
                <button
                  type="button"
                  className="btn btn-quiet btn-sm"
                  onClick={() => setConfirming(false)}
                >
                  Keep tracking
                </button>
              </div>
              {remove.error ? <ErrorNotice error={remove.error} what="Not removed" /> : null}
            </div>
          ) : (
            <button
              type="button"
              className="btn btn-quiet btn-sm"
              onClick={() => setConfirming(true)}
            >
              Stop tracking this
            </button>
          )}
        </div>
      </form>
    </section>
  );
}

/** Pasting another retailer's link for the same product. */
function AddRetailer({ productId, atLimit, onAdded }) {
  const [url, setUrl] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const add = useAction(async () => {
    setFieldErrors({});
    const added = await api.addRetailer(productId, url.trim());
    setUrl('');
    onAdded(added);
    return added;
  });

  if (atLimit) {
    return (
      <section className="panel">
        <div className="panel-body">
          <p className="small muted">
            This product already has the maximum number of retailers for this deployment. Each one
            is checked on its own schedule, so the cap is what keeps the total request volume
            reasonable.
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <header className="panel-head">
        <h2 className="panel-title">Add a retailer</h2>
      </header>
      <form
        className="panel-body stack"
        onSubmit={(event) => {
          event.preventDefault();
          add.run().catch((e) => {
            if (e.fields) {
              setFieldErrors(e.fields);
            }
          });
        }}
      >
        <Field
          label="Link to the same product elsewhere"
          type="url"
          value={url}
          onChange={setUrl}
          error={fieldErrors.url}
          placeholder="https://www.flipkart.com/..."
          hint="The page is read now, so the price appears straight away."
        />
        <div className="row">
          <button type="submit" className="btn btn-ghost btn-sm" disabled={add.busy || url.trim() === ''}>
            {add.busy ? 'Reading the page…' : 'Add retailer'}
          </button>
        </div>
        {add.error && !add.error.fields ? (
          <ErrorNotice error={add.error} what="That link could not be added" />
        ) : null}
      </form>
    </section>
  );
}

function cheapestOf(listings) {
  let best = null;
  listings.forEach((listing) => {
    const price = Number(listing.price);
    if (!Number.isFinite(price)) {
      return;
    }
    if (best === null || price < Number(best.price)) {
      best = listing;
    }
  });
  return best;
}

/** True when today's price equals the lowest ever recorded. */
function isAtLow(detail) {
  const current = Number(detail.stats?.current);
  const low = Number(detail.stats?.allTimeLow);
  return Number.isFinite(current) && Number.isFinite(low) && current <= low;
}

/**
 * Swallows a rejection that has already been handled.
 *
 * `useAction` rethrows so a caller can decide whether a failure was fatal to its flow. For a
 * button whose only response is the error notice beside it, there is nothing further to decide —
 * but an unhandled rejection in the console looks like a bug, so it is caught explicitly rather
 * than left to float.
 */
function noop() {}
