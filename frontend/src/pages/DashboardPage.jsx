import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../lib/api.js';
import { useAsync, useAction } from '../lib/useAsync.js';
import { useCapabilities } from '../lib/capabilities.jsx';
import { useToast } from '../lib/toast.jsx';
import Money from '../components/Money.jsx';
import Delta from '../components/Delta.jsx';
import RangeMark from '../components/RangeMark.jsx';
import Field from '../components/Field.jsx';
import Working from '../components/Working.jsx';
import EmptyState from '../components/EmptyState.jsx';
import Notice, { ErrorNotice } from '../components/Notice.jsx';
import { VerdictChip } from '../components/Verdict.jsx';
import { formatCount } from '../lib/money.js';
import { formatRelative } from '../lib/time.js';

/**
 * The dashboard: everything this user is tracking, as one list.
 *
 * ── Why rows and not cards ──
 * A grid of cards would put every price at a different x position, and comparing prices is the
 * entire job of this screen. Rows share a right-aligned price column, so "which of these fell?" is
 * answered by looking down one column rather than by reading twelve tiles. It is also what a price
 * list has looked like for a very long time, which is worth something.
 */
export default function DashboardPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const caps = useCapabilities();
  const products = useAsync((options) => api.products(options), []);

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-row">
          <div>
            <h1>Your products</h1>
            <p className="page-sub">
              {products.data
                ? products.data.length === 0
                  ? 'Nothing tracked yet.'
                  : formatCount(products.data.length, 'product') +
                    ', each checked automatically. Prices below were read from the retailers’ own pages.'
                : 'Loading your list.'}
            </p>
          </div>
          {products.reloading ? <Working label="Reloading your list" /> : null}
        </div>
      </header>

      <AddProduct
        onAdded={(added) => {
          toast.notify(
            added.notice ||
              'Now tracking ' + (added.title || 'this product') + ' at ' + added.retailer + '.',
            'Added'
          );
          navigate('/products/' + added.productId + '#shopping-assistant');
        }}
      />

      <ErrorNotice
        error={products.error}
        what="Your list could not be loaded"
        onRetry={products.reload}
      />

      {products.loading ? (
        <div className="ledger">
          <div className="ledger-empty">
            <Working label="Loading your products" />
          </div>
        </div>
      ) : null}

      {products.data && products.data.length === 0 ? (
        <div className="ledger">
          <EmptyState title="Nothing tracked yet">
            Paste a product link above — from{' '}
            {caps.supportedRetailers.length > 0
              ? caps.supportedRetailers.slice(0, 3).join(', ')
              : 'Amazon.in, Flipkart or Croma'}{' '}
            — and the first price is read from that page while you wait. After that it is checked on
            its own, more often when the price is moving and less often when it is not.
          </EmptyState>
        </div>
      ) : null}

      {products.data && products.data.length > 0 ? (
        <div className="ledger">
          {products.data.map((product) => (
            <LedgerRow key={product.productId} product={product} />
          ))}
        </div>
      ) : null}

      {products.data && products.data.length >= caps.maxTrackedProducts ? (
        <Notice tone="info" title="You have reached the limit.">
          This deployment tracks up to {caps.maxTrackedProducts} products per person, which is what
          keeps the request volume to each retailer polite. Remove one to add another.
        </Notice>
      ) : null}
    </div>
  );
}

/**
 * One product, as a row.
 *
 * The whole row is the link. A card with a separate "View" button adds a decision — click the card
 * or click the button? — to an interaction that has exactly one outcome.
 */
function LedgerRow({ product }) {
  return (
    <Link to={'/products/' + product.productId} className="ledger-row">
      <span className="ledger-thumb">
        {product.imageUrl ? (
          // Decorative: the title beside it already names the product, so alt text would make a
          // screen reader read the same thing twice.
          <img src={product.imageUrl} alt="" loading="lazy" />
        ) : (
          <span className="ledger-thumb-empty" aria-hidden="true">
            ₹
          </span>
        )}
      </span>

      <span className="ledger-range">
        <RangeMark
          low={product.allTimeLow}
          high={product.allTimeHigh}
          current={product.fromPrice}
          currency={product.currency}
          atLow={product.atAllTimeLow}
        />
      </span>

      <span>
        <span className="ledger-title">{product.title}</span>
        <span className="ledger-meta">
          {/*
           * Written as a sentence, not as values joined by middle dots. "4 retailers, last checked
           * 12 minutes ago" is the same information in a form a person reads at a glance instead of
           * parsing.
           */}
          {formatCount(product.retailerCount, 'retailer')}, last checked{' '}
          {formatRelative(product.lastCheckedAt)}
          {product.inStock ? '' : ' — out of stock'}
        </span>
        {product.note ? <span className="ledger-meta faint">{product.note}</span> : null}
      </span>

      <span className="ledger-price">
        {product.retailerCount > 1 ? (
          <span className="ledger-price-from">cheapest at {product.fromRetailer}</span>
        ) : null}
        <span className="ledger-price-now">
          <Money value={product.fromPrice} currency={product.currency} />
        </span>
        <span style={{ display: 'block', marginTop: '0.15rem' }}>
          <Delta
            percent={product.windowChangePercent}
            label={'in ' + product.windowDays + 'd'}
          />
        </span>
      </span>

      <span className="ledger-verdict">
        {/*
         * Three mutually exclusive states, in priority order: the target the user set has been met,
         * or this is the cheapest it has ever been, or here is the AI's opinion. Showing all three
         * at once would put three badges on a row and make none of them land.
         */}
        {product.targetMet ? (
          <span className="targetflag">target met</span>
        ) : product.atAllTimeLow ? (
          <span className="lowflag">lowest yet</span>
        ) : (
          <VerdictChip verdict={product.verdict} />
        )}
        {product.targetPrice && !product.targetMet ? (
          <span className="micro" style={{ display: 'block', marginTop: '0.2rem' }}>
            target <Money value={product.targetPrice} currency={product.currency} />
          </span>
        ) : null}
      </span>
    </Link>
  );
}

/**
 * The add bar.
 *
 * Deliberately slow, and says so. `POST /api/products` fetches the page before responding, which
 * takes a few seconds, and the button label changes to name what is happening rather than showing
 * a spinner. The alternative — accept the URL, return immediately, resolve it in the background —
 * would mean reporting success and then discovering half a minute later that the link was a search
 * results page.
 */
function AddProduct({ onAdded }) {
  const caps = useCapabilities();
  const [url, setUrl] = useState('');
  const [target, setTarget] = useState('');
  const [note, setNote] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const add = useAction(async () => {
    setFieldErrors({});
    const payload = { url: url.trim() };
    if (target.trim() !== '') {
      const parsed = Number(target);
      if (!Number.isFinite(parsed) || parsed <= 0) {
        setFieldErrors({ targetPrice: 'Enter a price like 129900, or leave it blank.' });
        throw new Error('invalid target');
      }
      payload.targetPrice = parsed;
    }
    if (note.trim() !== '') {
      payload.note = note.trim();
    }
    const added = await api.track(payload);
    setUrl('');
    setTarget('');
    setNote('');
    onAdded(added);
    return added;
  });

  const submit = (event) => {
    event.preventDefault();
    add.run().catch((e) => {
      // Per-field messages from bean validation go under their field; everything else is a
      // sentence about the URL itself — an unsupported retailer, a blocked fetch, a page with no
      // price on it — and belongs where the user is looking.
      if (e.fields) {
        setFieldErrors(e.fields);
      }
    });
  };

  return (
    <form className="addbar" onSubmit={submit}>
      <div className="addbar-url">
        <Field
          label="Product link"
          type="url"
          inputMode="url"
          placeholder="https://www.amazon.in/dp/..."
          value={url}
          onChange={setUrl}
          error={fieldErrors.url}
          required
        />
      </div>
      <div className="addbar-target">
        <Field
          label="Tell me at (optional)"
          inputMode="decimal"
          placeholder="129900"
          value={target}
          onChange={setTarget}
          error={fieldErrors.targetPrice}
        />
      </div>
      <div className="addbar-actions">
        <button type="submit" className="btn" disabled={add.busy || url.trim() === ''}>
          {add.busy ? 'Reading the page…' : 'Track this price'}
        </button>
        {add.busy ? <Working label="Fetching the retailer's page" inline /> : null}
      </div>

      <div className="addbar-note">
        <Field
          label="Note to yourself (optional)"
          placeholder="Replacing the old one in December"
          value={note}
          onChange={setNote}
          error={fieldErrors.note}
        />
      </div>

      {add.error && !add.error.fields ? (
        <div className="addbar-hint">
          <ErrorNotice error={add.error} what="That link could not be added" />
        </div>
      ) : (
        <p className="addbar-hint">
          Works with{' '}
          {caps.supportedRetailers.length > 0
            ? caps.supportedRetailers.join(', ')
            : 'the retailers configured on this deployment'}
          . The first price is read while you wait, so this takes a few seconds.
          {caps.searchEnabled
            ? ' Other retailers selling the same product are looked for afterwards, in the background.'
            : ''}
        </p>
      )}
    </form>
  );
}
