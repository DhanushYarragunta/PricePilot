import test from 'node:test';
import assert from 'node:assert/strict';
import { createProductRefreshSession, refreshDelay, refreshProgress, validateRefreshJob } from './productRefresh.js';
import { api } from './api.js';

const job = (status, extra = {}) => ({ jobId: 'refresh-123', productId: 7, status,
  terminal: !['QUEUED', 'RUNNING'].includes(status), total: 3,
  checked: status === 'QUEUED' ? 0 : status === 'RUNNING' ? 1 : 3,
  results: [], message: 'Checked with existing validation.', ...extra });
function storage() {
  const values = new Map();
  return { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key) };
}
const pending = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; };

test('one POST followed by status polls; duplicate clicks share the same job', async () => {
  const initial = pending();
  const memory = storage();
  let starts = 0, reads = 0, completions = 0;
  const changes = [];
  const session = createProductRefreshSession({ productId: 7, storage: memory,
    startJob: () => { starts++; return initial.promise; },
    readJob: async (id) => { assert.equal(id, 'refresh-123'); return ++reads === 1 ? job('RUNNING') : job('COMPLETED'); },
    pause: async () => {}, onChange: (value) => changes.push(value),
    onCompleted: async () => { completions++; },
  });
  const first = session.start(), second = session.start();
  assert.equal(first, second);
  initial.resolve(job('QUEUED'));
  await first;
  assert.equal(starts, 1); assert.equal(reads, 2); assert.equal(completions, 1);
  assert.equal(memory.getItem('pricepilot.refresh.7'), undefined);
  assert.equal(session.snapshot().busy, false);
  assert.deepEqual(changes.filter((s, i) => i && s.job !== changes[i - 1].job).map((s) => s.job.status),
    ['QUEUED', 'RUNNING', 'COMPLETED']);
});

test('a reload resumes saved job with GET and makes no paid submission', async () => {
  const memory = storage(); memory.setItem('pricepilot.refresh.7', 'refresh-123');
  let starts = 0;
  const session = createProductRefreshSession({ productId: '7', storage: memory,
    startJob: async () => { starts++; }, readJob: async () => job('PARTIAL'),
  });
  assert.equal(session.snapshot().resumable, true);
  const result = await session.resume();
  assert.equal(result.status, 'PARTIAL'); assert.equal(starts, 0);
  assert.equal(session.snapshot().job.status, 'PARTIAL');
});

test('unknown job clears saved ID without restarting or claiming success', async () => {
  const memory = storage(); memory.setItem('pricepilot.refresh.7', 'refresh-123');
  let starts = 0, completions = 0;
  const session = createProductRefreshSession({ productId: 7, storage: memory,
    startJob: async () => { starts++; },
    readJob: async () => { throw Object.assign(new Error('The previous check is no longer available.'), { status: 404, code: 'refresh_job_unknown' }); },
    onCompleted: () => { completions++; },
  });
  await session.resume();
  assert.equal(starts, 0); assert.equal(completions, 0);
  assert.equal(memory.getItem('pricepilot.refresh.7'), undefined);
  assert.equal(session.snapshot().error.code, 'refresh_job_unknown');
  assert.equal(session.snapshot().resumable, false);
});

test('network failure retains job and explicit retry only polls', async () => {
  const memory = storage(); let starts = 0, reads = 0;
  const session = createProductRefreshSession({ productId: 7, storage: memory, pause: async () => {},
    startJob: async () => { starts++; return job('RUNNING'); },
    readJob: async () => { if (++reads === 1) throw new Error('Offline'); return job('COMPLETED'); },
  });
  await session.start();
  assert.equal(session.snapshot().error.message, 'Offline');
  assert.equal(session.snapshot().resumable, true);
  await session.start();
  assert.equal(starts, 1); assert.equal(reads, 2); assert.equal(session.snapshot().error, null);
});

test('navigation aborts watching without cancelling or restarting server work', async () => {
  const observed = pending(), pause = pending(); const memory = storage(); let reads = 0, completions = 0;
  const session = createProductRefreshSession({ productId: 7, storage: memory,
    startJob: async () => job('RUNNING'), readJob: async () => { reads++; return job('COMPLETED'); },
    pause: async () => { observed.resolve(); return pause.promise; }, onCompleted: () => { completions++; },
  });
  const running = session.start(); await observed.promise;
  session.dispose(); pause.resolve(); await running;
  assert.equal(reads, 0); assert.equal(completions, 0);
  assert.equal(memory.getItem('pricepilot.refresh.7'), 'refresh-123');
});

test('failed product reload retains the completed job so retry never starts another scrape', async () => {
  const memory = storage(); let starts = 0, reads = 0, completions = 0;
  const session = createProductRefreshSession({ productId: 7, storage: memory,
    startJob: async () => { starts++; return job('COMPLETED'); },
    readJob: async () => { reads++; return job('COMPLETED'); },
    onCompleted: async () => { if (++completions === 1) throw new Error('Product reload failed'); },
  });
  await session.start();
  assert.equal(session.snapshot().resumable, true);
  assert.equal(memory.getItem('pricepilot.refresh.7'), 'refresh-123');
  await session.start();
  assert.equal(starts, 1); assert.equal(reads, 1); assert.equal(completions, 2);
  assert.equal(session.snapshot().resumable, false);
  assert.equal(memory.getItem('pricepilot.refresh.7'), undefined);
});

test('a hung status request times out but keeps its saved job', async () => {
  const memory = storage(); memory.setItem('pricepilot.refresh.7', 'refresh-123');
  const session = createProductRefreshSession({ productId: 7, storage: memory, requestTimeoutMs: 5,
    readJob: async (_, { signal }) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(new DOMException('Stopped', 'AbortError')))),
  });
  await session.resume();
  assert.match(session.snapshot().error.message, /timed out/);
  assert.equal(session.snapshot().resumable, true);
});

test('failed and cancelled terminal states remain failures', async () => {
  for (const status of ['FAILED', 'CANCELLED']) {
    const session = createProductRefreshSession({ productId: 7, startJob: async () => job(status),
      readJob: async () => { assert.fail('terminal state must not poll'); },
    });
    await session.start(); assert.equal(session.snapshot().job.status, status);
  }
});

test('mismatched job identity and malformed progress are rejected', () => {
  for (const value of [null, job('RUNNING', { productId: 8 }), job('RUNNING', { jobId: undefined }),
    job('RUNNING', { terminal: true }), job('RUNNING', { checked: 9 }), job('RUNNING', { status: 'UNKNOWN' })]) {
    assert.throws(() => validateRefreshJob(value, 7));
  }
  assert.throws(() => validateRefreshJob(job('RUNNING'), 7, 'different-id'));
  assert.equal(refreshProgress(job('RUNNING')), 'Checked 1 of 3 retailers…');
});

test('disabled session storage keeps duplicate protection in memory', async () => {
  let starts = 0, reads = 0;
  const session = createProductRefreshSession({ productId: 7, storage: {
    getItem() { throw new Error('disabled'); }, setItem() { throw new Error('disabled'); }, removeItem() { throw new Error('disabled'); },
  }, startJob: async () => { starts++; return job('RUNNING'); }, pause: async () => {},
  readJob: async () => { if (++reads === 1) throw new Error('Offline'); return job('COMPLETED'); } });
  await session.start(); await session.start(); assert.equal(starts, 1);
});

test('aborted poll delay stops immediately', async () => {
  const control = new AbortController(); control.abort();
  await assert.rejects(refreshDelay(2000, control.signal), { name: 'AbortError' });
});

test('refresh API forwards cancellation and encodes the status identifier', async () => {
  const previous = global.fetch; const calls = []; const controller = new AbortController();
  global.fetch = async (url, options) => { calls.push({ url, ...options }); return {
    ok: true, status: 202, headers: { get: () => 'application/json' }, json: async () => job('QUEUED'),
  }; };
  try {
    await api.refresh(7, { signal: controller.signal });
    await api.refreshStatus(7, 'job/id', { signal: controller.signal });
    assert.equal(calls[0].method, 'POST'); assert.equal(calls[0].signal, controller.signal);
    assert.equal(calls[1].url, '/api/products/7/refresh/job%2Fid'); assert.equal(calls[1].method, 'GET');
  } finally { global.fetch = previous; }
});
