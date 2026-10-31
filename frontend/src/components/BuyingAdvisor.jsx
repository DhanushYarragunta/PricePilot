import { useEffect, useState } from 'react';
import { api } from '../lib/api.js';
import { useAction, useAsync } from '../lib/useAsync.js';
import { observedPoints, outcomeLabel, preferenceForm, preferencePayload } from '../lib/buyingAdvisor.js';
import { formatAbsolute, formatDay, formatDateOnly } from '../lib/time.js';
import { formatPrice } from '../lib/money.js';
import Field, { CheckField } from './Field.jsx';
import Money from './Money.jsx';
import Working from './Working.jsx';
import { ErrorNotice } from './Notice.jsx';
import { Notes, SourceLink } from './ProductResearch.jsx';

export default function BuyingAdvisor({ productId, listings = [], observedAt }) {
  const saved = useAsync((options) => api.buyingPlan(productId, options), [productId, observedAt]);
  const [message, setMessage] = useState('');
  const [form, setForm] = useState(preferenceForm());
  const data = saved.data;
  useEffect(() => { if (data) setForm(preferenceForm(data.preferences)); }, [data]);
  const update = (key) => (value) => setForm((previous) => ({ ...previous, [key]: value }));
  const act = useAction(async (kind) => {
    try {
      const result = kind === 'message'
        ? await api.discussPlan(productId, { version: data.version, message: message.trim() })
        : await api.updateBuyingPlan(productId, { version: data.version, preferences: preferencePayload(form) });
      saved.patch(result);
      if (kind === 'message') setMessage('');
    } catch (error) {
      if (error.status === 409) saved.reload();
      throw error;
    }
  });
  const selected = data?.history?.series?.find((series) => series.listingId === data.history.selectedListingId && series.currency === 'INR');
  const currency = selected?.currency || 'INR';
  const plan = data?.plan;
  const forecast = data?.forecast;
  return <section className="panel advisor-panel" id="buying-plan">
    <header className="panel-head"><div><h2 className="panel-title">Your buying plan</h2><p className="small muted">A saved conversation about your budget, timing and observed prices.</p></div>{data?.updatedAt && <span className="small muted">Updated {formatAbsolute(data.updatedAt)}</span>}</header>
    <div className="panel-body stack">
      <ErrorNotice error={saved.error || act.error} what="The buying plan could not be updated" onRetry={saved.reload} />
      {saved.loading && <Working label="Loading your buying plan" />}
      {data && <>
        <div className="advisor-decision"><span className="small muted">Current recommendation</span><h3>{outcomeLabel(plan?.outcome)}</h3><p>{plan?.summary || 'Tell me when you need this product and what you can spend.'}</p><Notes items={plan?.reasons} />
          <div className="advisor-facts"><div>Budget<br /><strong><Money value={data.preferences?.budget} currency={currency} /></strong>{data.preferences?.budget ? data.preferences.hardBudget ? ' maximum' : ' flexible' : ''}</div><div>Target price<br /><strong><Money value={plan?.targetPrice || data.preferences?.targetPrice} currency={currency} /></strong></div><div>Purchase deadline<br /><strong>{formatDateOnly(data.preferences?.deadlineDate)}</strong> {data.preferences?.deadlineDate ? data.preferences.deadlineKind?.toLowerCase() : ''}</div><div>Next reassessment<br /><strong>{plan?.reassessAt ? formatAbsolute(plan.reassessAt) : 'Not scheduled'}</strong></div></div>
          <Notes items={plan?.changeTriggers} />
        </div>
        <details><summary>Edit purchase requirements</summary><form className="stack advisor-editor" onSubmit={(event) => { event.preventDefault(); act.run('preferences').catch(() => {}); }}><fieldset disabled={act.busy} className="advisor-fieldset"><div className="advisor-fields">
          <label className="field"><span className="field-label">Retailer / variant</span><select className="input" value={form.listingId} onChange={(e) => update('listingId')(e.target.value)}><option value="">Primary listing</option>{listings.map((listing) => <option key={listing.listingId} value={listing.listingId}>{listing.retailer} · {listing.title || 'tracked variant'}</option>)}</select></label>
          <Field label={'Budget (' + currency + ')'} type="number" min="0.01" step="0.01" value={form.budget} onChange={update('budget')} />
          <Field label={'Target price (' + currency + ')'} type="number" min="0.01" step="0.01" value={form.targetPrice} onChange={update('targetPrice')} />
          <Field label="Purchase deadline" type="date" value={form.deadlineDate} onChange={update('deadlineDate')} />
          <label className="field"><span className="field-label">Deadline type</span><select className="input" value={form.deadlineKind} onChange={(e) => update('deadlineKind')(e.target.value)}><option value="HARD">Must buy by this date</option><option value="FLEXIBLE">Flexible target date</option></select></label>
          <Field label="Earliest acceptable purchase" type="date" value={form.earliestDate} onChange={update('earliestDate')} />
          <Field label="Desired savings (%)" type="number" min="0.01" max="99.99" step="0.01" value={form.desiredSavingsPercent} onChange={update('desiredSavingsPercent')} />
          <Field label="Reason for buying (optional)" value={form.reason} onChange={update('reason')} maxLength={500} />
        </div><CheckField label="My budget is an absolute maximum" checked={form.hardBudget} onChange={update('hardBudget')} /><CheckField label="I am willing to consider alternatives" checked={form.allowAlternatives} onChange={update('allowAlternatives')} /></fieldset><button className="btn" disabled={act.busy}>Save and reassess</button></form></details>
        <div className="assistant-conversation" role="log" aria-label="Saved buying conversation">{data.conversation?.map((turn, i) => <div className={'assistant-turn assistant-turn-' + turn.role?.toLowerCase()} key={turn.id || i}><div className="assistant-speaker">{turn.role?.toLowerCase() === 'user' ? 'You' : 'PricePilot'}</div><p className="assistant-answer">{turn.content || turn.message}</p><span className="small muted">{formatAbsolute(turn.createdAt)}</span></div>)}</div>
        {data.followUpQuestion && <p className="advisor-question">{data.followUpQuestion}</p>}
        <form className="stack" onSubmit={(event) => { event.preventDefault(); if (message.trim()) act.run('message').catch(() => {}); }}><Field as="textarea" label="Discuss your purchase" placeholder="My absolute budget is ₹3,000. I need it by 2026-12-15." value={message} onChange={setMessage} maxLength={2000} rows={3} disabled={act.busy} required /><div className="advisor-actions"><button className="btn" disabled={act.busy || !message.trim()}>{act.busy ? 'Updating your plan…' : 'Ask buying advisor'}</button>{['I can wait longer', 'I need it sooner', 'Change my budget', 'Why are you recommending this?'].map((text) => <button className="btn btn-sm btn-ghost" type="button" key={text} disabled={act.busy} onClick={() => setMessage(text)}>{text}</button>)}</div></form>
        <Notes items={data.warnings} />
        {data.notifications?.length > 0 && <details><summary>Buying-plan notifications</summary><ul className="research-sources">{data.notifications.map((notification) => <li key={notification.id}><p>{notification.message}</p><p className="small muted">{formatAbsolute(notification.createdAt)} · {notification.deliveredAt ? 'Mail server accepted ' + formatAbsolute(notification.deliveredAt) : 'Email delivery not confirmed'}</p></li>)}</ul></details>}
        <section><h3>365-day observed history</h3><p className="small muted">{formatDay(data.history?.windowStart, { withYear: true })} – {formatDay(data.history?.windowEnd, { withYear: true })}. Each dot is a saved observation; gaps contain no assumed prices.</p>
          {data.history?.series?.map((series) => <HistorySeries key={series.listingId + series.currency} series={series} start={data.history.windowStart} end={data.history.windowEnd} />)}<Notes items={data.history?.limitations} />
        </section>
        {forecast && <section className="advisor-forecast"><h3>Forecast — an estimate, separate from observed prices</h3><p>{forecast.lower != null && forecast.upper != null ? <><Money value={forecast.lower} currency={currency} /> – <Money value={forecast.upper} currency={currency} /> by {formatDateOnly(forecast.horizonDate)}</> : 'There is not enough evidence for a useful future price range.'}</p><p className="small muted">{forecast.status?.replaceAll('_', ' ')} · {forecast.method?.replaceAll('_', ' ')} · Uncertainty: {forecast.uncertainty}</p><Notes items={forecast.limitations} />{forecast.backtests?.length > 0 && <details><summary>Historical holdout comparison</summary><div className="table-scroll"><table className="table"><thead><tr><th>Method</th><th>Mean absolute error</th><th>Holdout observations</th></tr></thead><tbody>{forecast.backtests.map((test, i) => <tr key={i}><td>{test.method}</td><td><Money value={test.mae ?? test.meanAbsoluteError} currency={currency} /></td><td>{test.holdoutPoints ?? '—'}</td></tr>)}</tbody></table></div></details>}</section>}
      </>}
    </div>
  </section>;
}
function HistorySeries({ series, start, end }) {
  const stats = series.stats || {};
  const points = observedPoints(series);
  const times = [Date.parse(start), Date.parse(end)];
  const prices = points.map((p) => p.value);
  const low = Math.min(...prices), high = Math.max(...prices);
  const x = (t) => 55 + (t - times[0]) / Math.max(1, times[1] - times[0]) * 900;
  const y = (v) => 165 - (v - low) / Math.max(1, high - low) * 130;
  return <article className="advisor-history"><h4><SourceLink url={series.url}>{series.retailer} · {series.currency}</SourceLink></h4><p className="small muted">{series.title}</p>
    <div className="advisor-facts"><div>Last observed<br /><Money value={stats.currentPrice} currency={series.currency} /><span className="small muted"> {stats.stale ? '(stale)' : ''}{stats.currentInStock === false ? ' · out of stock' : ''}</span></div><div>Minimum<br /><Money value={stats.minimum} currency={series.currency} /></div><div>Average<br /><Money value={stats.average} currency={series.currency} /></div><div>Median<br /><Money value={stats.median} currency={series.currency} /></div></div>
    <p className="small muted">{stats.observationCount || 0} observations on {stats.observedDays || 0} days · {stats.coveragePercent ?? 0}% daily coverage · {stats.completeYear ? 'Full daily year coverage' : 'Incomplete year'} · Last successful observation: {formatAbsolute(stats.lastObservedAt)}</p>
    {points.length > 0 && <svg className="advisor-chart" viewBox="0 0 1000 205" role="img" aria-label={series.retailer + ' actual price observations over the past 365 days, with no interpolation'}><line x1="55" y1="180" x2="955" y2="180" stroke="currentColor" opacity=".25" /><text x="55" y="200" className="chart-axis">{formatDay(start)}</text><text x="955" y="200" textAnchor="end" className="chart-axis">{formatDay(end)}</text><text x="55" y="18" className="chart-axis">{formatPrice(high, series.currency)}</text>{points.map((point, i) => <circle key={i} cx={x(point.time)} cy={y(point.value)} r="3.5" fill="currentColor"><title>{formatAbsolute(point.capturedAt) + ': ' + formatPrice(point.value, series.currency)}</title></circle>)}</svg>}
    <details><summary>Statistics and missing intervals</summary><p className="small">Maximum: <Money value={stats.maximum} currency={series.currency} /> · P10: <Money value={stats.p10} currency={series.currency} /> · P90: <Money value={stats.p90} currency={series.currency} /> · {stats.priceChanges || 0} observed price changes · {stats.priceDrops || 0} drops.</p><p className="small">{stats.belowMedianPercent ?? '—'}% of observations were below the median. Observed low-price spans: {stats.observedLowPriceSpanDays ?? '—'} days. These are sampled observations, not confirmed sale durations.</p><ul className="assistant-cautions">{series.gaps?.map((gap, i) => <li key={i}>{formatAbsolute(gap.from)} – {formatAbsolute(gap.to)} ({gap.hours} hours without a reading)</li>)}</ul></details>
  </article>;
}
