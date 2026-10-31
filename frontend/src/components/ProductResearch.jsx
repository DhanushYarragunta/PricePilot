import { useState } from 'react';
import { api } from '../lib/api.js';
import { useAction, useAsync } from '../lib/useAsync.js';
import { evidenceUrl } from '../lib/buyingAdvisor.js';
import { formatAbsolute } from '../lib/time.js';
import Field from './Field.jsx';
import Working from './Working.jsx';
import { ErrorNotice } from './Notice.jsx';

const SECTIONS = [
  ['specs', 'Specifications'], ['strengths', 'Strengths'], ['weaknesses', 'Weaknesses'],
  ['ownerExperiences', 'Owner experiences'], ['reviewFindings', 'Independent reviews and tests'],
  ['bestFor', 'Best-use scenarios'], ['avoidIf', 'Reasons to choose another product'],
];

export default function ProductResearch({ productId, listings = [] }) {
  const [listingId, setListingId] = useState('');
  return <ResearchForListing key={listingId} productId={productId} listings={listings} listingId={listingId} setListingId={setListingId} />;
}

function ResearchForListing({ productId, listings, listingId, setListingId }) {
  const [model, setModel] = useState('');
  const [variant, setVariant] = useState('');
  const report = useAsync((options) => api.research(productId, { listingId, model, variant }, options), [productId, listingId]);
  const research = useAction(async (refresh) => {
    const result = await api.runResearch(productId, { listingId: listingId ? Number(listingId) : null, model, variant, refresh });
    report.patch(result);
  });
  const data = report.data;
  const stale = data?.expiresAt && new Date(data.expiresAt) < new Date();
  return (
    <section className="panel advisor-panel" id="product-research">
      <header className="panel-head">
        <div><h2 className="panel-title">Product research</h2><p className="small muted">Specifications, tests and owner experiences with their sources.</p></div>
        {data?.researchAt && <span className="small muted">{stale ? 'Expired report' : data.cached ? 'Saved report' : 'Researched'} · {formatAbsolute(data.researchAt)}</span>}
      </header>
      <div className="panel-body stack">
        <div className="advisor-fields">
          <label className="field"><span className="field-label">Research this listing</span><select className="input" value={listingId} disabled={research.busy} onChange={(e) => { setListingId(e.target.value); setModel(''); setVariant(''); }}><option value="">Primary retailer</option>{listings.map((l) => <option key={l.listingId} value={l.listingId}>{l.retailer}</option>)}</select></label>
          <Field label="Exact model (if known)" value={model} onChange={setModel} maxLength={160} disabled={research.busy} hint="Use the model shown in the listing title." />
          <Field label="Variant (if known)" value={variant} onChange={setVariant} maxLength={160} disabled={research.busy} placeholder="Storage, colour or generation" />
        </div>
        <div className="advisor-actions">
          <button className="btn" disabled={research.busy || report.loading || report.reloading} onClick={() => research.run(false).catch(() => {})}>{research.busy ? 'Researching sources…' : 'Research this product'}</button>
          {data?.researchAt && <button className="btn btn-ghost" disabled={research.busy} onClick={() => research.run(true).catch(() => {})}>Refresh research</button>}
          {research.busy && <Working label="Checking sources within the search budget" inline />}
        </div>
        <ErrorNotice error={research.error || report.error} what="Research could not be completed" />
        {report.loading && <Working label="Loading saved research" />}
        {data && <>
          <div><h3>{data.identity?.title}</h3><p className="small muted">{[data.identity?.brand, data.identity?.model, data.identity?.variant].filter(Boolean).join(' · ')}</p>
            {data.identity?.ambiguous && <p className="advisor-warning">Exact model or variant is not fully confirmed. Treat the findings as provisional.</p>}
            <Notes items={data.identity?.uncertainties} />
          </div>
          {data.verdict && <p className="assistant-answer">{data.verdict}</p>}
          <div className="research-sections">{SECTIONS.map(([key, title]) => <section key={key}><h3>{title}</h3>{data[key]?.length ? <ul className="research-claims">{data[key].map((claim, index) => <li key={index}><Claim claim={claim} /></li>)}</ul> : <p className="small muted">No supported findings yet.</p>}</section>)}</div>
          {data.alternatives?.length > 0 && <section><h3>Alternatives to investigate</h3><div className="table-scroll"><table className="table"><thead><tr><th>Comparison evidence</th><th>Evidence type</th></tr></thead><tbody>{data.alternatives.map((claim, i) => <tr key={i}><td><Claim claim={claim} /></td><td>{humanize(claim.evidenceType)}</td></tr>)}</tbody></table></div><p className="small muted">Use Compare alternatives to check current retailer offers. Research sources do not establish live selling prices.</p></section>}
          <Notes items={data.limitations} />
          <details><summary>Sources consulted ({data.sources?.length || 0})</summary><ol className="research-sources">{data.sources?.map((source) => <li key={source.id}><SourceLink url={source.url}>{source.title || source.url}</SourceLink><p className="small muted">{humanize(source.sourceType)} · Retrieved {formatAbsolute(source.retrievedAt)}{source.publishedAt ? ' · Published ' + source.publishedAt : ''}</p><p className="small">{source.excerpt}</p></li>)}</ol></details>
        </>}
        {!data?.researchAt && <p className="small muted">Research starts when you request it. Saved reports are reused until they expire; an explicit refresh uses the search budget.</p>}
      </div>
    </section>
  );
}
function Claim({ claim }) {
  return <><p>{claim.text}</p><p className="small muted">{humanize(claim.category)} · {humanize(claim.evidenceType)} · Evidence: {humanize(claim.confidence)}</p>{claim.evidence?.map((e, i) => <blockquote className="research-evidence" key={i}><p>“{e.quote}”</p><SourceLink url={e.url}>Source {e.sourceId}</SourceLink></blockquote>)}</>;
}
export function Notes({ items }) { return items?.length ? <ul className="assistant-cautions">{items.map((item, i) => <li key={i}>{item}</li>)}</ul> : null; }
export function SourceLink({ url, children }) { const safe = evidenceUrl(url); return safe ? <a href={safe} target="_blank" rel="noopener noreferrer">{children} ↗</a> : <span>{children}</span>; }
function humanize(value) { return value ? String(value).replaceAll('_', ' ').toLowerCase() : 'not established'; }
