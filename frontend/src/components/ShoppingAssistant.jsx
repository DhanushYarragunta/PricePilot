import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { api } from '../lib/api.js';
import { useCapabilities } from '../lib/capabilities.jsx';
import { formatAbsolute, formatRelative } from '../lib/time.js';
import {
  ASSISTANT_MESSAGE_LIMIT,
  ASSISTANT_REQUIREMENTS_LIMIT,
  assistantHistory,
  hasSourcedPrice,
  parseShoppingBudget,
  safeRetailerUrl,
} from '../lib/shoppingAssistant.js';
import Field from './Field.jsx';
import Money from './Money.jsx';
import Notice, { ErrorNotice } from './Notice.jsx';
import Working from './Working.jsx';

const FIRST_QUESTION = 'Compare this product with options from other brands and retailers that fit my requirements.';
const IDEAS = [
  'A lower price with similar core features',
  'Reliability and a good warranty',
  'The best fit for everyday use',
];

export default function ShoppingAssistant({ productId }) {
  const caps = useCapabilities();
  const location = useLocation();
  const heading = useRef(null);
  const request = useRef(null);
  const [budget, setBudget] = useState('');
  const [requirements, setRequirements] = useState('');
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const enabled = caps.loaded && !caps.unreachable && caps.aiEnabled;
  const started = messages.length > 0;

  useEffect(() => {
    if (location.hash === '#shopping-assistant') {
      heading.current?.focus({ preventScroll: true });
      heading.current?.scrollIntoView({ block: 'start' });
    }
  }, [location.hash]);

  useEffect(() => () => {
    const pending = request.current;
    request.current = null;
    pending?.abort();
  }, []);

  async function submit(event) {
    event.preventDefault();
    if (!enabled || request.current) return;
    setError(null);
    setFieldErrors({});
    let parsedBudget;
    try {
      parsedBudget = parseShoppingBudget(budget);
    } catch (e) {
      setFieldErrors({ budget: e.message });
      return;
    }
    if (!requirements.trim()) {
      setFieldErrors({ requirements: 'Tell me how you will use it and which features matter.' });
      return;
    }
    const message = started ? question.trim() : FIRST_QUESTION;
    if (!message) {
      setFieldErrors({ message: 'Enter a follow-up question.' });
      return;
    }

    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 195000);

    try {
      const result = await api.assistant(productId, {
        message,
        budget: parsedBudget,
        requirements: requirements.trim(),
        history: assistantHistory(messages),
      }, { signal: controller.signal });
      if (request.current !== controller) return;
      if (!result || typeof result.answer !== 'string' || !result.answer.trim()) {
        throw new Error('The assistant returned an incomplete response. Please try again.');
      }
      setMessages((previous) => [...previous, {
        role: 'user',
        content: started ? message : requirements.trim() + (parsedBudget ? `\nBudget: ₹${parsedBudget}` : ''),
      }, {
        role: 'assistant',
        content: result.answer,
        model: result.model,
        candidates: Array.isArray(result.candidates) ? result.candidates : [],
        warnings: Array.isArray(result.warnings) ? result.warnings : [],
        searchAvailable: result.searchAvailable,
      }].slice(-12));
      setQuestion('');
    } catch (e) {
      if (request.current !== controller) return;
      if (timedOut) {
        setError(new Error('This comparison took too long. Your tracked product is saved; try a more specific question in a moment.'));
      } else if (e.name !== 'AbortError') {
        if (e.fields) setFieldErrors(e.fields);
        setError(e);
      }
    } finally {
      clearTimeout(timeout);
      if (request.current === controller) {
        request.current = null;
        setBusy(false);
      }
    }
  }

  return (
    <section className="panel shopping-assistant" id="shopping-assistant" aria-labelledby="shopping-assistant-heading">
      <header className="panel-head">
        <div>
          <h2 className="panel-title" id="shopping-assistant-heading" tabIndex={-1} ref={heading}>
            Find your best fit
          </h2>
          <p className="small muted assistant-subtitle">Compare stores. Explore other brands. Ask what matters to you.</p>
        </div>
        {started ? (
          <button type="button" className="btn btn-sm btn-quiet" disabled={busy} onClick={() => {
            setMessages([]);
            setQuestion('');
            setError(null);
            setFieldErrors({});
          }}>Clear conversation</button>
        ) : null}
      </header>

      <div className="panel-body stack">
        {!caps.loaded ? <Working label="Checking assistant availability" /> : null}
        {caps.unreachable ? (
          <Notice tone="info" title="Assistant availability could not be checked.">
            Reload the page to try again. Your price history is still available below.
          </Notice>
        ) : caps.loaded && !caps.aiEnabled ? (
          <Notice tone="info" title="The shopping assistant is not available on this deployment yet.">
            You can keep tracking prices and compare the retailer listings below.
          </Notice>
        ) : null}
        {enabled && !caps.searchEnabled ? (
          <Notice tone="info" title="Store search is currently unavailable.">
            The assistant can discuss the product already tracked here. Finding fresh options at other stores needs store search to be enabled.
          </Notice>
        ) : null}

        <form onSubmit={submit} className="stack">
          <fieldset className="assistant-preferences" disabled={busy || !enabled}>
            <legend className="sr-only">Your shopping preferences</legend>
            <div className="assistant-fields">
              <Field
                as="textarea"
                label="What do you need it for?"
                placeholder="Tell me your use case, must-have features, and anything you want to avoid."
                value={requirements}
                onChange={setRequirements}
                maxLength={ASSISTANT_REQUIREMENTS_LIMIT}
                error={fieldErrors.requirements}
                rows={3}
                required
              />
              <Field
                label="Maximum budget (₹, optional)"
                inputMode="decimal"
                placeholder="3000"
                value={budget}
                onChange={setBudget}
                error={fieldErrors.budget}
                maxLength={12}
                hint="Leave blank if your budget is flexible."
              />
            </div>
            {!started ? (
              <div className="assistant-ideas" aria-label="Ideas for your requirements">
                {IDEAS.map((idea) => (
                  <button type="button" className="btn btn-sm btn-ghost" key={idea} onClick={() => setRequirements(idea)}>{idea}</button>
                ))}
              </div>
            ) : null}
          </fieldset>

          {started ? (
            <div className="assistant-conversation" role="log" aria-live="polite" aria-relevant="additions" aria-label="Shopping conversation">
              {messages.map((message, index) => (
                <div className={'assistant-turn assistant-turn-' + message.role} key={index}>
                  <div className="assistant-speaker">{message.role === 'user' ? 'You' : 'PricePilot assistant'}</div>
                  <p className="assistant-answer">{message.content}</p>
                  {message.role === 'assistant' ? (
                    <>
                      {message.candidates?.length > 0 ? (
                        <div className="assistant-candidates" aria-label="Products considered">
                          {message.candidates.map((candidate, candidateIndex) => (
                            <Candidate key={candidate.id || candidate.url || candidateIndex} candidate={candidate} />
                          ))}
                        </div>
                      ) : null}
                      {message.warnings?.length > 0 ? (
                        <ul className="assistant-cautions">
                          {message.warnings.map((warning, warningIndex) => <li key={warningIndex}>{warning}</li>)}
                        </ul>
                      ) : null}
                      {message.model ? <p className="machine assistant-model">Response by {message.model}</p> : null}
                    </>
                  ) : null}
                </div>
              ))}
            </div>
          ) : null}

          {started ? (
            <Field
              as="textarea"
              label="Ask a follow-up"
              placeholder="Which option fits my needs best? What would I give up by spending less?"
              value={question}
              onChange={setQuestion}
              maxLength={ASSISTANT_MESSAGE_LIMIT}
              error={fieldErrors.message}
              rows={2}
              disabled={busy || !enabled}
              required
            />
          ) : null}

          <ErrorNotice error={error} what="The comparison could not be completed" />
          <div className="assistant-submit">
            <button type="submit" className="btn" disabled={busy || !enabled || !requirements.trim() || (started && !question.trim())}>
              {busy ? 'Comparing your options…' : started ? 'Ask assistant' : 'Compare options for me'}
            </button>
            <div role="status" aria-live="polite" aria-atomic="true">
              {busy ? <Working label="Checking available product evidence; this can take up to three minutes" inline /> : null}
            </div>
          </div>
          <p className="small muted">Prices show when they were read. Check the exact variant, delivery, and warranty on the retailer&apos;s page before buying.</p>
        </form>
      </div>
    </section>
  );
}

function Candidate({ candidate }) {
  const url = safeRetailerUrl(candidate.url);
  const label = candidate.kind === 'same_product' ? 'Matching product'
    : candidate.kind === 'alternative' ? 'Alternative' : 'Product match unconfirmed';
  const priceKnown = hasSourcedPrice(candidate);

  return (
    <article className="assistant-candidate">
      <div className="assistant-candidate-meta">
        <span>{candidate.retailer || 'Retailer'}</span>
        <span>{label}</span>
      </div>
      <h3 className="assistant-candidate-title">
        {url ? <a href={url} target="_blank" rel="noopener noreferrer">{candidate.title || 'View product'} ↗</a> : candidate.title || 'Product'}
      </h3>
      <div className="assistant-candidate-price">
        {priceKnown ? <Money value={candidate.price} currency={candidate.currency} /> : <span className="small muted">Price not verified</span>}
        {candidate.inStock === false ? <span className="small muted">Out of stock</span> : null}
        {candidate.inStock === null || candidate.inStock === undefined ? <span className="small muted">Stock not verified</span> : null}
      </div>
      {priceKnown ? (
        <p className="small muted" title={formatAbsolute(candidate.capturedAt)}>Price read {formatRelative(candidate.capturedAt)}</p>
      ) : null}
      {candidate.reason ? <p className="small assistant-candidate-reason">{candidate.reason}</p> : null}
      {Array.isArray(candidate.cautions) && candidate.cautions.length > 0 ? (
        <ul className="assistant-cautions">
          {candidate.cautions.map((caution, index) => <li key={index}>{caution}</li>)}
        </ul>
      ) : null}
    </article>
  );
}
