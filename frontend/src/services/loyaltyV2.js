import axios from 'axios';

/**
 * Loyalty v2 HTTP client.
 *
 * Uses a dedicated axios instance (NOT the /api/v1 baseURL one) so
 * every v2 call hits the /api/v2 route.  Cookies ride along for auth,
 * and the gateway injects X-User-Id/X-User-Role before reaching
 * booking-service.
 *
 * We keep v2 isolated on the frontend because, during the M11 shadow
 * period, the old /api/v1/bookings/loyalty endpoint is still live —
 * having two clients lets individual pages migrate at their own pace.
 */
const v2 = axios.create({
  baseURL: '/api/v2/loyalty',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
  timeout: 15000,
});

const unwrap = (r) => (r?.data?.data !== undefined ? r.data.data : r?.data);

// ── Customer ────────────────────────────────────────────────────────────

export async function getMyMembership() {
  return unwrap(await v2.get('/me'));
}

export async function getMyLedger({ page = 0, size = 25 } = {}) {
  return unwrap(await v2.get('/me/ledger', { params: { page, size } }));
}

export async function getRedeemQuote({ bingeId, bookingAmount, points }) {
  return unwrap(await v2.get('/me/redeem-quote', { params: { bingeId, bookingAmount, points } }));
}

export async function listMyStatusMatches() {
  return unwrap(await v2.get('/me/status-match'));
}

export async function submitStatusMatch(body) {
  return unwrap(await v2.post('/me/status-match', body));
}

// ── Admin (per-binge) ───────────────────────────────────────────────────

export async function getBinding(bingeId) {
  return unwrap(await v2.get(`/admin/bindings/${bingeId}`));
}
export async function enableBinding(bingeId) {
  return unwrap(await v2.post(`/admin/bindings/${bingeId}/enable`));
}
export async function disableBinding(bindingId) {
  return unwrap(await v2.post(`/admin/bindings/${bindingId}/disable`));
}
export async function listEarnRules(bindingId) {
  return unwrap(await v2.get(`/admin/bindings/${bindingId}/earn-rules`));
}
export async function upsertEarnRule(bindingId, draft) {
  return unwrap(await v2.post(`/admin/bindings/${bindingId}/earn-rules`, draft));
}
export async function getRedeemRule(bindingId) {
  return unwrap(await v2.get(`/admin/bindings/${bindingId}/redeem-rule`));
}
export async function upsertRedeemRule(bindingId, draft) {
  return unwrap(await v2.post(`/admin/bindings/${bindingId}/redeem-rule`, draft));
}
export async function upsertPerkOverride(bindingId, draft) {
  return unwrap(await v2.post(`/admin/bindings/${bindingId}/perks`, draft));
}
export async function listPendingStatusMatches({ page = 0, size = 25 } = {}) {
  return unwrap(await v2.get('/admin/status-match/pending', { params: { page, size } }));
}
export async function approveStatusMatch(requestId, body) {
  return unwrap(await v2.post(`/admin/status-match/${requestId}/approve`, body));
}
export async function rejectStatusMatch(requestId, body) {
  return unwrap(await v2.post(`/admin/status-match/${requestId}/reject`, body));
}

// ── Super-admin (program-wide) ──────────────────────────────────────────

export async function getProgram() {
  return unwrap(await v2.get('/super-admin/program'));
}
export async function updateProgram(body) {
  return unwrap(await v2.put('/super-admin/program', body));
}
export async function listTiers() {
  return unwrap(await v2.get('/super-admin/tiers'));
}
export async function upsertTier(draft) {
  return unwrap(await v2.post('/super-admin/tiers', draft));
}
export async function retireTier(tierId) {
  return unwrap(await v2.delete(`/super-admin/tiers/${tierId}`));
}
export async function listPerks() {
  return unwrap(await v2.get('/super-admin/perks'));
}
export async function savePerk(draft) {
  return unwrap(await v2.post('/super-admin/perks', draft));
}
export async function assignPerkToTier(mapping) {
  return unwrap(await v2.post('/super-admin/tier-perks', mapping));
}
export async function removePerkFromTier(tierPerkId) {
  return unwrap(await v2.delete(`/super-admin/tier-perks/${tierPerkId}`));
}
export async function listBindings() {
  return unwrap(await v2.get('/super-admin/bindings'));
}
export async function bulkSetBindingStatus(bindingIds, status) {
  return unwrap(await v2.post('/super-admin/bindings/bulk', { bindingIds, status }));
}
export async function runParityNow() {
  return unwrap(await v2.post('/super-admin/parity/run'));
}

export default {
  getMyMembership, getMyLedger, getRedeemQuote, listMyStatusMatches, submitStatusMatch,
  getBinding, enableBinding, disableBinding, listEarnRules, upsertEarnRule,
  getRedeemRule, upsertRedeemRule, upsertPerkOverride,
  listPendingStatusMatches, approveStatusMatch, rejectStatusMatch,
  getProgram, updateProgram, listTiers, upsertTier, retireTier,
  listPerks, savePerk, assignPerkToTier, removePerkFromTier,
  listBindings, bulkSetBindingStatus, runParityNow,
};
