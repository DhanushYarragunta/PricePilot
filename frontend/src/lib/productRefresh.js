const ACTIVE = new Set(['QUEUED', 'RUNNING']);
const TERMINAL = new Set(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED']);
const JOB_ID = /^[A-Za-z0-9-]{1,80}$/;

function aborted() {
  return new DOMException('Stopped watching this check.', 'AbortError');
}

export function refreshDelay(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(aborted());
    const stop = () => { clearTimeout(timer); reject(aborted()); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', stop); resolve(); }, milliseconds);
    signal.addEventListener('abort', stop, { once: true });
  });
}

export function validateRefreshJob(job, productId, previousId = null) {
  if (!job || typeof job.jobId !== 'string' || !JOB_ID.test(job.jobId) || String(job.productId) !== String(productId)
      || (!ACTIVE.has(job.status) && !TERMINAL.has(job.status))
      || job.terminal !== TERMINAL.has(job.status) || (previousId && job.jobId !== previousId)
      || !Number.isInteger(job.total) || job.total < 0
      || !Number.isInteger(job.checked) || job.checked < 0 || job.checked > job.total
      || !Array.isArray(job.results) || typeof job.message !== 'string') {
    throw new Error('The server returned an unreadable check status. Reload after the update finishes.');
  }
  return job;
}

export function refreshProgress(job) {
  if (!job) return 'Starting the price check…';
  if (job.status === 'QUEUED') return 'Price check queued. Your saved prices remain visible.';
  if (job.status === 'RUNNING') return 'Checked ' + job.checked + ' of ' + job.total + ' retailers…';
  return job.message;
}

/** A session only starts on an explicit click. Reload/retry polls the saved job. */
export function createProductRefreshSession({ productId, startJob, readJob, storage,
  onChange = () => {}, onCompleted = async () => {}, pause = refreshDelay,
  requestTimeoutMs = 15000 }) {
  const storageKey = 'pricepilot.refresh.' + productId;
  let disposed = false;
  let inFlight = null;
  let controller = null;
  let savedId = null;
  try {
    const stored = storage?.getItem(storageKey);
    if (stored && JOB_ID.test(stored)) savedId = stored;
    else if (stored) storage?.removeItem(storageKey);
  } catch { /* Session storage may be unavailable; retain in-memory state. */ }
  let state = { job: null, busy: false, error: null, resumable: Boolean(savedId) };

  function publish(update) {
    if (disposed) return;
    state = { ...state, ...update };
    onChange(state);
  }
  function remember(id) {
    savedId = id;
    try {
      if (id) storage?.setItem(storageKey, id);
      else storage?.removeItem(storageKey);
    } catch { /* Keep the same ID in memory when browser storage is disabled. */ }
  }
  async function boundedRequest(operation, signal) {
    const request = new AbortController();
    const abort = () => request.abort();
    signal.addEventListener('abort', abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; request.abort(); }, requestTimeoutMs);
    try {
      if (signal.aborted) throw aborted();
      return await operation(request.signal);
    } catch (error) {
      if (signal.aborted) throw aborted();
      if (timedOut) throw new Error('The status request timed out. Resume checking to see whether the saved check finished.');
      throw error;
    } finally {
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
    }
  }
  function run(resumeOnly) {
    if (disposed) return Promise.resolve(null);
    if (inFlight) return inFlight;
    const resumeId = savedId;
    if (resumeOnly && !resumeId) return Promise.resolve(null);
    controller = new AbortController();
    const signal = controller.signal;
    publish({ busy: true, error: null });
    inFlight = (async () => {
      try {
        let job = validateRefreshJob(await boundedRequest(
          (requestSignal) => resumeId ? readJob(resumeId, { signal: requestSignal })
            : startJob({ signal: requestSignal }), signal), productId, resumeId);
        for (;;) {
          if (disposed || signal.aborted) throw aborted();
          remember(job.jobId);
          publish({ job, resumable: true });
          if (job.terminal) {
            await onCompleted(job, { signal });
            if (disposed || signal.aborted) throw aborted();
            remember(null);
            publish({ resumable: false });
            return job;
          }
          await pause(2000, signal);
          if (disposed || signal.aborted) throw aborted();
          job = validateRefreshJob(await boundedRequest(
            (requestSignal) => readJob(job.jobId, { signal: requestSignal }), signal), productId, job.jobId);
        }
      } catch (error) {
        if (!disposed && error.name !== 'AbortError') {
          // A restart/expiry is not evidence that the remote scrape failed or was free.
          // Never resubmit it automatically.
          if (error.status === 404 || error.code === 'refresh_job_unknown') {
            remember(null);
            publish({ job: null, resumable: false });
          }
          publish({ error, resumable: Boolean(savedId) });
        }
        return null;
      } finally {
        inFlight = null;
        publish({ busy: false });
      }
    })();
    return inFlight;
  }
  return {
    start: () => run(false), resume: () => run(true), snapshot: () => state,
    dispose: () => { disposed = true; controller?.abort(); },
  };
}
