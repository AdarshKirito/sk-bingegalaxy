import { useState } from 'react';
import { octoSimulator } from '../services/octoSimulator';
import { toast } from 'react-toastify';
import {
  FiPlay, FiKey, FiCheckCircle, FiSlash, FiRefreshCw, FiAlertTriangle, FiTrash2,
} from 'react-icons/fi';
import './AdminPages.css';

/**
 * Drive this venue's OCTO surface exactly as a reseller would.
 *
 * <p><b>Why a screen and not a curl snippet.</b> The channel could be configured
 * end-to-end and nobody could tell whether it worked: every write is answered
 * {@code PENDING} because the canonical booking is created by a sweep afterwards, so
 * "the reseller got a 201" proves nothing about whether the venue received a booking.
 * Confirming that took an operator with a terminal, a hand-built Bearer token and
 * knowledge of the OCTO payload shape.
 *
 * <p><b>The transcript is the product.</b> Each step records the request, the raw
 * response and the status, so what an operator hands to a partner — or reads back after
 * a failed reservation — is what actually crossed the wire, not a summary of it.
 *
 * <p><b>It authenticates as a reseller, not as you.</b> The key is typed in, held in
 * component state only, and sent through a client with no cookies and no interceptors
 * (see {@code services/octoSimulator.js}). That is what makes a 401 here meaningful: if
 * the operator's own session could open this surface, the screen would prove nothing.
 */

const STATUS_TONE = (status) => {
  if (status >= 200 && status < 300) return 'success';
  if (status === 401 || status === 403) return 'danger';
  if (status === 429) return 'warning';
  return status >= 400 ? 'danger' : 'muted';
};

/** OCTO lifecycle → how worried an operator should be. */
const OUTCOME_TONE = {
  CONFIRMED: 'success',
  ON_HOLD: 'info',
  PENDING: 'warning',
  CANCELLED: 'muted',
  REJECTED: 'danger',
  SUPERSEDED: 'muted',
};

export default function AdminChannelSimulator() {
  const [key, setKey] = useState('');
  const [products, setProducts] = useState([]);
  const [productId, setProductId] = useState('');
  const [localDate, setLocalDate] = useState(
    new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10));
  const [windows, setWindows] = useState([]);
  const [availabilityId, setAvailabilityId] = useState('');
  const [uuid, setUuid] = useState('');
  const [guestName, setGuestName] = useState('Simulator Traveller');
  const [guestEmail, setGuestEmail] = useState('traveller@example.com');
  const [retail, setRetail] = useState(300000);
  const [currency, setCurrency] = useState('INR');
  const [busy, setBusy] = useState(false);
  const [log, setLog] = useState([]);

  const record = (entry) => {
    setLog((prev) => [entry, ...prev].slice(0, 40));
    return entry;
  };

  /** One wrapper so no step can forget to log itself or to clear the busy flag. */
  const run = async (fn) => {
    if (!key.trim()) {
      toast.error('Paste the reseller key first — this surface has no other way in.');
      return null;
    }
    setBusy(true);
    try {
      const entry = record(await fn());
      if (entry.status >= 400) {
        // The provider-facing body, verbatim. Replacing it with friendly copy would
        // hide the errorMessage a partner would actually receive.
        toast.error(`${entry.label}: ${entry.response?.errorMessage
          || entry.response?.message || `HTTP ${entry.status}`}`);
      }
      return entry;
    } catch (e) {
      // A transport failure is not an OCTO answer; say which it was.
      record({ label: 'Transport failure', method: '—', url: '—', status: 0,
        response: { error: String(e?.message || e) }, ms: 0, at: new Date().toISOString() });
      toast.error('The request never reached the service.');
      return null;
    } finally {
      setBusy(false);
    }
  };

  const loadProducts = async () => {
    const entry = await run(() => octoSimulator.listProducts(key));
    if (entry?.status === 200 && Array.isArray(entry.response)) {
      setProducts(entry.response);
      if (entry.response.length === 0) {
        toast.info('No products. A listing must be LIVE before a reseller can see it.');
      } else if (!productId) {
        setProductId(entry.response[0].id);
      }
    }
  };

  const checkAvailability = async () => {
    const entry = await run(() => octoSimulator.checkAvailability(key, productId, localDate));
    if (entry?.status === 200 && Array.isArray(entry.response)) {
      setWindows(entry.response);
      setAvailabilityId(entry.response[0]?.id || '');
      if (entry.response.length === 0) {
        toast.info('No bookable window that day — closed, fully booked, or blocked.');
      }
    }
  };

  const reserve = async () => {
    // The reseller owns the uuid and it is the idempotency key for the WHOLE lifecycle,
    // so it is generated once here and reused by confirm, status and cancel.
    const ref = uuid.trim() || `SIM-${Date.now()}`;
    setUuid(ref);
    await run(() => octoSimulator.reserve(key, {
      uuid: ref,
      productId,
      availabilityId,
      contact: { fullName: guestName, emailAddress: guestEmail },
      // Whole-space hire is priced per booking, so only the COUNT of these matters.
      unitItems: [{ unitId: 'adult' }, { unitId: 'adult' }],
      pricing: { retail: Number(retail), currency },
    }));
  };

  const step = (label, fn) => async () => {
    if (!uuid.trim()) {
      toast.error(`Reserve first — ${label} addresses a reservation by its uuid.`);
      return;
    }
    await run(fn);
  };

  const latestOutcome = log.find((e) => e.label === 'Get status' && e.status === 200)?.response;

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiPlay /> Distribution</span>
          <h1>Channel simulator</h1>
          <p>Drive this venue&apos;s OCTO endpoints exactly as a reseller would, and see
             what actually crossed the wire.</p>
        </div>
      </div>

      {/* ── Credential ──────────────────────────────────────────────────── */}
      <form className="adm-form" onSubmit={(e) => e.preventDefault()}>
        <div className="input-group" style={{ margin: 0, maxWidth: 560 }}>
          <label htmlFor="rkey"><FiKey aria-hidden="true" /> Reseller key</label>
          <input id="rkey" type="password" autoComplete="off" value={key}
                 placeholder="skbg_octo_…"
                 onChange={(e) => setKey(e.target.value)} />
        </div>
        <p className="adm-hint" style={{ marginTop: '0.5rem' }}>
          Issued on the Distribution page, shown once and never recoverable. It is held in
          this page only — never stored, and never sent with your admin session, because a
          surface an admin session could open would prove nothing about a reseller.
        </p>
      </form>

      {/* ── The lifecycle ───────────────────────────────────────────────── */}
      <div className="adm-card" style={{ padding: '1rem 1.15rem', marginTop: '1rem' }}>
        <h3 style={{ marginTop: 0 }}>1 · What is on sale</h3>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <button className="btn btn-secondary" disabled={busy} onClick={loadProducts}>
            <FiRefreshCw /> Load products
          </button>
          <div className="input-group" style={{ margin: 0, minWidth: 220 }}>
            <label htmlFor="prod">Product</label>
            <select id="prod" value={productId} onChange={(e) => setProductId(e.target.value)}>
              <option value="">—</option>
              {products.map((p) => (
                <option key={p.id} value={p.id}>{p.id} ({p.internalName})</option>
              ))}
            </select>
          </div>
          <div className="input-group" style={{ margin: 0 }}>
            <label htmlFor="date">Date</label>
            <input id="date" type="date" value={localDate}
                   onChange={(e) => setLocalDate(e.target.value)} />
          </div>
          <button className="btn btn-secondary" disabled={busy || !productId}
                  onClick={checkAvailability}>
            Check availability
          </button>
          <div className="input-group" style={{ margin: 0, minWidth: 260 }}>
            <label htmlFor="avail">Window</label>
            <select id="avail" value={availabilityId}
                    onChange={(e) => setAvailabilityId(e.target.value)}>
              <option value="">—</option>
              {windows.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.localDateTimeStart} → {w.localDateTimeEnd}
                  {w.available ? '' : ' (unavailable)'}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="adm-card" style={{ padding: '1rem 1.15rem', marginTop: '1rem' }}>
        <h3 style={{ marginTop: 0 }}>2 · Sell it</h3>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div className="input-group" style={{ margin: 0, minWidth: 200 }}>
            <label htmlFor="uuid">Reservation uuid</label>
            {/* The reseller chooses this, and it is the idempotency key for the whole
                lifecycle — reserve, confirm and cancel all carry the same one. */}
            <input id="uuid" type="text" value={uuid} placeholder="generated on reserve"
                   onChange={(e) => setUuid(e.target.value)} />
          </div>
          <div className="input-group" style={{ margin: 0, minWidth: 180 }}>
            <label htmlFor="gname">Traveller</label>
            <input id="gname" type="text" value={guestName}
                   onChange={(e) => setGuestName(e.target.value)} />
          </div>
          <div className="input-group" style={{ margin: 0, minWidth: 200 }}>
            <label htmlFor="gmail">Traveller email</label>
            <input id="gmail" type="email" value={guestEmail}
                   onChange={(e) => setGuestEmail(e.target.value)} />
          </div>
          <div className="input-group" style={{ margin: 0, maxWidth: 140 }}>
            <label htmlFor="retail">Retail (minor)</label>
            {/* Minor units end to end, matching how settlements store money. 300000 is
                ₹3,000.00 — converting on the wire would add a rounding step to money a
                venue is owed. */}
            <input id="retail" type="number" min="0" value={retail}
                   onChange={(e) => setRetail(e.target.value)} />
          </div>
          <div className="input-group" style={{ margin: 0, maxWidth: 110 }}>
            <label htmlFor="cur">Currency</label>
            <input id="cur" type="text" value={currency} maxLength={3}
                   onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
          </div>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginTop: '1rem' }}>
          <button className="btn btn-primary" disabled={busy || !productId || !availabilityId}
                  onClick={reserve}>
            Reserve (hold)
          </button>
          <button className="btn btn-primary" disabled={busy}
                  onClick={step('confirm', () => octoSimulator.confirm(key, uuid.trim()))}>
            <FiCheckCircle /> Confirm
          </button>
          <button className="btn btn-secondary" disabled={busy}
                  onClick={step('status', () => octoSimulator.status(key, uuid.trim()))}>
            <FiRefreshCw /> Check status
          </button>
          <button className="btn btn-danger" disabled={busy}
                  onClick={step('cancel', () => octoSimulator.cancel(key, uuid.trim()))}>
            <FiSlash /> Cancel
          </button>
        </div>

        <p className="adm-hint" style={{ marginTop: '0.75rem' }}>
          <FiAlertTriangle aria-hidden="true" /> Reserve and confirm are answered
          <strong> PENDING</strong>: the canonical booking is created by a sweep that runs
          every 30 seconds. <strong>Check status</strong> is how you learn what actually
          happened — a refusal looks identical to a success until you ask.
        </p>

        {latestOutcome && (
          <p style={{ marginTop: '0.5rem' }}>
            Last known outcome:{' '}
            <span className={`badge badge-${OUTCOME_TONE[latestOutcome.status] || 'muted'}`}>
              {latestOutcome.status}
            </span>
            {latestOutcome.supplierReference && <> · booking {latestOutcome.supplierReference}</>}
            {latestOutcome.errorMessage && <> · {latestOutcome.errorMessage}</>}
            {latestOutcome.pending && <> · still being decided — ask again</>}
          </p>
        )}
      </div>

      {/* ── Transcript ──────────────────────────────────────────────────── */}
      <div className="adm-card" style={{ padding: '1rem 1.15rem', marginTop: '1rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0 }}>3 · What crossed the wire</h3>
          {log.length > 0 && (
            <button className="btn btn-secondary btn-sm" onClick={() => setLog([])}>
              <FiTrash2 /> Clear
            </button>
          )}
        </div>

        {log.length === 0 && (
          <p className="adm-hint" style={{ marginTop: '0.5rem' }}>
            Nothing yet. Every call is recorded here with its exact request and raw
            response, so a failure can be handed to a partner as evidence.
          </p>
        )}

        {log.map((e, i) => (
          <details key={`${e.at}-${i}`} style={{ marginTop: '0.6rem' }}>
            <summary style={{ cursor: 'pointer' }}>
              <span className={`badge badge-${STATUS_TONE(e.status)}`}>
                {e.status || 'ERR'}
              </span>{' '}
              <strong>{e.label}</strong>{' '}
              <span className="adm-hint">{e.method} {e.url} · {e.ms}ms</span>
            </summary>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                          background: 'var(--surface-2, rgba(127,127,127,0.12))',
                          padding: '0.6rem', borderRadius: 6, fontSize: '0.78rem',
                          marginTop: '0.4rem', overflowX: 'auto' }}>
{e.request ? `request  ${JSON.stringify(e.request, null, 2)}\n\n` : ''}response {JSON.stringify(e.response, null, 2)}
            </pre>
          </details>
        ))}
      </div>
    </div>
  );
}
