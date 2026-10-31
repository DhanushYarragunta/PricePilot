import test from 'node:test';
import assert from 'node:assert/strict';
import { api, setToken } from './api.js';

test('research URL encodes exact identity; plan update preserves explicit nulls/version', async () => {
  const original = globalThis.fetch;
  const calls=[];
  globalThis.fetch = async (url, options) => { calls.push({url,...options}); return {ok:true,status:200,headers:new Headers({'Content-Type':'application/json'}),json:async()=>({})}; };
  try {
    setToken('test-token');
    await api.research(7, {model:'Model + Pro',variant:'128 GB',listingId:4});
    await api.updateBuyingPlan(7, {version:3,preferences:{deadlineDate:null,budget:3000}});
    await api.testEmail();
    assert.match(calls[0].url,/model=Model\+%2B\+Pro/);
    assert.equal(calls[1].method,'PATCH');
    assert.deepEqual(JSON.parse(calls[1].body),{version:3,preferences:{deadlineDate:null,budget:3000}});
    assert.equal(calls[2].url,'/api/alerts/test-email');
    assert.deepEqual(JSON.parse(calls[2].body),{});
    assert.equal(calls[2].headers.Authorization,'Bearer test-token');
  } finally { globalThis.fetch=original; setToken(null); }
});
