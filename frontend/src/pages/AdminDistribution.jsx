import { useState, useEffect, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiLink, FiPause, FiPlay, FiSlash, FiAlertTriangle, FiCheckCircle, FiKey, FiCopy, FiX,
  FiPlus,
} from 'react-icons/fi';
import './AdminPages.css';

/**
 * Distribution connections (slice 3).
 *
 * <p>Until this screen existed an operator could not create a connection without curl —
 * the same write-only gap the attribution feature had. A backend that works and cannot
 * be reached is not shipped.
 *
 * <p><b>The UI is capability-driven, not hopeful.</b> What a provider needs and what it
 * can do come from the server (`authMethod`, `requiresCredential`,
 * `credentialSubmissionSupported`, `capabilities`), never from assumptions here. A
 * single "paste your API key" box would be wrong for three of the five seeded providers:
 * Google Things to Do is an SFTP feed behind a content licence, Actions Center is basic
 * auth rotated every six months, and the simulator needs no credential at all.
 *
 * <p>Styling uses the `.adm-*` vocabulary. The other admin CSS system in this repo has
 * no definitions for most of what a page like this needs, and using it yields markup
 * that is structurally correct and visually broken.
 */

const STATUS_TONE = {
  ACTIVE: 'success',
  PENDING: 'warning',
  AWAITING_PROVIDER: 'warning',
  DEGRADED: 'danger',
  PAUSED: 'muted',
  REVOKED: 'danger',
};

/** Why a connection is not simply "on" — an operator needs the reason, not the enum. */
const STATUS_HELP = {
  PENDING: 'Created but not selling. Attach a destination, then Activate to go live.',
  AWAITING_PROVIDER: 'Waiting on the provider: certification, a pilot, or a signed agreement.',
  ACTIVE: 'Live and exchanging data.',
  DEGRADED: 'Reachable but failing. Check provider health before relying on it.',
  PAUSED: 'All traffic stopped by this venue.',
  REVOKED: 'Terminated. Kept for reconciliation; it cannot be reused.',
};

export default function AdminDistribution() {
  const [providers, setProviders] = useState([]);
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState(null);
  const [form, setForm] = useState({ providerCode: '', environment: 'SANDBOX', credentialRef: '' });
  // Held in component state only. The server returns the plaintext key exactly once —
  // only its digest is stored — so this is the single moment it is readable anywhere,
  // and it is deliberately not persisted to storage of any kind.
  const [issuedKey, setIssuedKey] = useState(null);
  // Which connection's "add destination" form is open, plus its draft terms. Scoped to
  // one connection at a time: commercial terms belong to a connection↔destination
  // pairing, and a single shared form would make it easy to attach terms to the wrong one.
  const [attachTo, setAttachTo] = useState(null);
  const [destinations, setDestinations] = useState([]);
  const [attachForm, setAttachForm] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [p, c] = await Promise.all([
        adminService.getDistributionProviders(),
        adminService.getDistributionConnections(),
      ]);
      setProviders(p.data?.data || []);
      setConnections(c.data?.data || []);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load distribution connections');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const selectedProvider = providers.find((p) => p.code === form.providerCode);

  const issueKey = async (connection) => {
    const rotating = Boolean(connection.resellerKeyHint);
    if (rotating && !window.confirm(
      'Issue a NEW reseller key?\n\n'
      + 'The current key stops working the moment this completes, and any reseller '
      + 'still using it will start getting 401s until you send them the new one.'
    )) return;

    setBusyId(connection.id);
    try {
      const res = await adminService.issueResellerKey(connection.id);
      setIssuedKey({ connectionId: connection.id, ...(res.data?.data || {}) });
      await load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not issue a reseller key');
    } finally {
      setBusyId(null);
    }
  };

  /**
   * Open the attach form for one connection and load the destinations its provider
   * actually operates.
   *
   * <p>The step that had no control at all. A connection with no destination reaches
   * nowhere: Activate refuses it, and a listing needs a connectionDestinationId, so the
   * entire publish chain dead-ended one hop after "Create connection" — with the page
   * cheerfully showing the connection as Pending and no way forward.
   */
  const openAttach = async (connection) => {
    setAttachTo(connection.id);
    setAttachForm({
      destinationCode: '',
      commissionBps: '',
      paymentResponsibility: 'CHANNEL_COLLECTS',
      settlementModel: 'COMMISSION_SETTLEMENT',
      safetyInventory: 0,
      enabled: false,
    });
    try {
      const res = await adminService.getDistributionDestinations(connection.providerCode);
      setDestinations(res.data?.data || []);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load destinations');
      setDestinations([]);
    }
  };

  const closeAttach = () => {
    setAttachTo(null);
    setAttachForm(null);
    setDestinations([]);
  };

  const submitAttach = async (e, connectionId) => {
    e.preventDefault();
    if (!attachForm?.destinationCode) return;
    setBusyId(connectionId);
    try {
      const payload = {
        destinationCode: attachForm.destinationCode,
        paymentResponsibility: attachForm.paymentResponsibility,
        settlementModel: attachForm.settlementModel,
        safetyInventory: Number(attachForm.safetyInventory) || 0,
        enabled: attachForm.enabled,
      };
      // Sent only when stated. An empty box means "no commission agreed yet", which is
      // not the same as zero — and zero would silently claim the channel takes no cut.
      if (String(attachForm.commissionBps).trim() !== '') {
        payload.commissionBps = Number(attachForm.commissionBps);
      }
      await adminService.attachDistributionDestination(connectionId, payload);
      toast.success('Destination added');
      closeAttach();
      await load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not add the destination');
    } finally {
      setBusyId(null);
    }
  };

  const submit = async (e) => {
    e.preventDefault();
    if (!form.providerCode) return;
    try {
      const payload = { providerCode: form.providerCode, environment: form.environment };
      // Only sent when the provider actually needs one. Sending it to a
      // platform-managed provider is refused by the server, and rightly so.
      if (selectedProvider?.requiresCredential && form.credentialRef.trim()) {
        payload.credentialRef = form.credentialRef.trim();
      }
      await adminService.createDistributionConnection(payload);
      toast.success('Connection created');
      setForm({ providerCode: '', environment: 'SANDBOX', credentialRef: '' });
      load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not create the connection');
    }
  };

  const act = async (id, fn, confirmMessage) => {
    if (confirmMessage && !window.confirm(confirmMessage)) return;
    setBusyId(id);
    try {
      await fn();
      load();
    } catch (e) {
      toast.error(e.response?.data?.message || 'Action failed');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiLink /> Distribution</span>
          <h1>Channel connections</h1>
          <p>Connect this venue to a distribution provider and control its traffic.</p>
        </div>
      </div>

      {/* ── Create ───────────────────────────────────────────────────────── */}
      <form className="adm-form" onSubmit={submit}>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div className="input-group" style={{ margin: 0, minWidth: 220 }}>
            <label htmlFor="provider">Provider</label>
            <select id="provider" value={form.providerCode}
                    onChange={(e) => setForm({ ...form, providerCode: e.target.value, credentialRef: '' })}>
              <option value="">Select a provider…</option>
              {providers.map((p) => (
                <option key={p.code} value={p.code}>{p.displayName}</option>
              ))}
            </select>
          </div>
          <div className="input-group" style={{ margin: 0 }}>
            <label htmlFor="env">Environment</label>
            <select id="env" value={form.environment}
                    onChange={(e) => setForm({ ...form, environment: e.target.value })}>
              {/* Sandbox first and default: the riskier choice should never be the
                  one a distracted operator picks by accident. */}
              <option value="SANDBOX">Sandbox</option>
              <option value="PRODUCTION">Production</option>
            </select>
          </div>
          <button className="btn btn-primary" type="submit" disabled={!form.providerCode}>
            Create connection
          </button>
        </div>

        {/* Only rendered when the SERVER says this provider needs a credential. */}
        {selectedProvider?.requiresCredential && (
          <div style={{ marginTop: '0.75rem' }}>
            <div className="input-group" style={{ margin: 0, maxWidth: 420 }}>
              <label htmlFor="credref">Credential reference</label>
              <input id="credref" type="text" value={form.credentialRef}
                     placeholder="e.g. viator/binge-12/production"
                     onChange={(e) => setForm({ ...form, credentialRef: e.target.value })} />
            </div>
            <p className="adm-hint" style={{ marginTop: '0.4rem' }}>
              {selectedProvider.credentialSubmissionSupported
                ? 'A pointer to the stored secret — not the secret itself.'
                /* Said outright rather than offering an input that cannot work. This
                   deployment resolves secrets from the environment and refuses writes,
                   so the secret is provisioned out of band. */
                : 'This deployment does not accept secrets through the browser. Provision the '
                  + 'secret on distribution-service first, then enter the reference that names it.'}
              {' '}Authentication method: <strong>{selectedProvider.authMethod}</strong>.
            </p>
          </div>
        )}

        {selectedProvider && selectedProvider.certificationState !== 'NONE' && (
          <p className="adm-hint" style={{ marginTop: '0.5rem' }}>
            <FiAlertTriangle aria-hidden="true" />{' '}
            {selectedProvider.displayName} requires <strong>{selectedProvider.certificationState}</strong>{' '}
            before it can go live. The connection will be created as Pending.
          </p>
        )}
      </form>

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && connections.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiLink /></span>
          <h3>No connections yet</h3>
          <p>
            This venue is not connected to any distribution provider. Only providers a
            super-admin has activated appear in the list above.
          </p>
        </div>
      )}

      {/* Shown once, inline, and dismissed by the operator rather than by a timer or a
          re-render. The key exists nowhere else after this — not in the API, not in the
          database, not in a later response — so anything that could make it disappear
          before it has been copied would lose it for good. */}
      {issuedKey?.key && (
        <section className="adm-card"
                 style={{ padding: '1rem 1.15rem', marginBottom: '1rem',
                          border: '1px solid var(--warning, #f59e0b)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem' }}>
            <h3 style={{ margin: 0, display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}>
              <FiKey /> Reseller key — copy it now
            </h3>
            <button className="btn btn-secondary btn-sm" onClick={() => setIssuedKey(null)}
                    aria-label="Dismiss the reseller key">
              <FiX /> Done
            </button>
          </div>
          <p className="adm-hint" style={{ margin: '0.5rem 0' }}>
            Give this to the reseller as a <code>Bearer</code> token. SK Binge stores only a
            one-way hash of it, so this is the only time it can be shown.
            {issuedKey.replacedPrevious && <strong> The previous key has stopped working.</strong>}
          </p>
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <code style={{ flex: '1 1 320px', wordBreak: 'break-all', padding: '0.5rem 0.65rem',
                           background: 'var(--surface-2, rgba(127,127,127,0.12))',
                           borderRadius: '6px', fontSize: '0.85rem' }}>
              {issuedKey.key}
            </code>
            <button className="btn btn-secondary btn-sm"
                    onClick={() => {
                      // clipboard is unavailable over plain HTTP on some browsers; the
                      // key stays on screen either way, so a failure is recoverable.
                      navigator.clipboard?.writeText(issuedKey.key)
                        .then(() => toast.success('Key copied'))
                        .catch(() => toast.info('Copy it manually — clipboard access was refused'));
                    }}>
              <FiCopy /> Copy
            </button>
          </div>
        </section>
      )}

      {!loading && connections.map((c) => (
        <div className="adm-card" key={c.id} style={{ marginBottom: '1rem', padding: '1rem 1.15rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap' }}>
            <div>
              <h3 style={{ margin: 0 }}>{c.providerName}</h3>
              <p className="adm-hint" style={{ margin: '0.25rem 0 0' }}>
                <span className={`badge badge-${STATUS_TONE[c.status] || 'muted'}`}>{c.status}</span>
                {' '}· {c.environment}
                {c.credentialHint && <> · credential {c.credentialHint}</>}
                {/* A hint outlives a rotation that removed the secret, so "configured"
                    is resolved live on the server rather than inferred from the hint. */}
                {c.credentialHint && !c.credentialConfigured && (
                  <strong> · secret not resolvable</strong>
                )}
                {/* The INBOUND key — what a reseller presents to reach this venue.
                    Distinct from the credential above, which is what SK Binge presents
                    to a provider. Without it no reseller can authenticate at all. */}
                {c.resellerKeyHint
                  ? <> · reseller key {c.resellerKeyHint}</>
                  : <> · <strong>no reseller key</strong></>}
              </p>
              <p className="adm-hint" style={{ margin: '0.35rem 0 0' }}>
                {STATUS_HELP[c.status]}
                {c.pausedReason && <> — {c.pausedReason}</>}
              </p>
              {c.capabilities?.length > 0 && (
                <p className="adm-hint" style={{ margin: '0.35rem 0 0', fontSize: '0.82em' }}>
                  Supports: {c.capabilities.join(', ')}
                </p>
              )}
            </div>

            {/* Controls follow the state machine: a revoked connection is terminal, so
                it offers no actions at all rather than buttons that will 400. */}
            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-start' }}>
              {/* Go live. A reseller can only authenticate against an ACTIVE connection
                  and a listing can only publish from one, so without this the setup
                  dead-ended: every other button worked and the channel still could not
                  sell. The server verifies the provider, the credential and at least one
                  enabled destination before flipping the status. */}
              {(c.status === 'PENDING' || c.status === 'AWAITING_PROVIDER'
                || c.status === 'DEGRADED') && (
                <button className="btn btn-primary btn-sm" disabled={busyId === c.id}
                        onClick={() => act(c.id,
                          () => adminService.activateDistributionConnection(c.id))}>
                  <FiCheckCircle /> Activate
                </button>
              )}
              {/* Attach a destination. Without one the connection reaches nowhere:
                  Activate refuses it and a listing has no connectionDestinationId to
                  hang off, so this is the hop the whole publish chain dead-ended at. */}
              {c.status !== 'REVOKED' && (
                <button className="btn btn-secondary btn-sm" disabled={busyId === c.id}
                        onClick={() => (attachTo === c.id ? closeAttach() : openAttach(c))}>
                  <FiPlus /> {attachTo === c.id ? 'Cancel' : 'Add destination'}
                </button>
              )}
              {c.status !== 'REVOKED' && (
                <button className="btn btn-secondary btn-sm" disabled={busyId === c.id}
                        onClick={() => issueKey(c)}>
                  <FiKey /> {c.resellerKeyHint ? 'New key' : 'Issue key'}
                </button>
              )}
              {c.status === 'PAUSED' && (
                <button className="btn btn-secondary btn-sm" disabled={busyId === c.id}
                        onClick={() => act(c.id, () => adminService.resumeDistributionConnection(c.id))}>
                  <FiPlay /> Resume
                </button>
              )}
              {c.status !== 'PAUSED' && c.status !== 'REVOKED' && (
                <button className="btn btn-secondary btn-sm" disabled={busyId === c.id}
                        onClick={() => act(c.id,
                          () => adminService.pauseDistributionConnection(c.id, 'Paused from console'))}>
                  <FiPause /> Pause
                </button>
              )}
              {c.status !== 'REVOKED' && (
                <button className="btn btn-danger btn-sm" disabled={busyId === c.id}
                        onClick={() => act(c.id,
                          () => adminService.revokeDistributionConnection(c.id, 'Revoked from console'),
                          'Revoking is permanent. The connection cannot be reused. Continue?')}>
                  <FiSlash /> Revoke
                </button>
              )}
            </div>
          </div>

          {/* Named rather than left blank. An empty destinations table and a connection
              that simply has none look identical, and the second is the reason Activate
              keeps refusing — which is not something an operator should have to infer. */}
          {c.status !== 'REVOKED' && !(c.destinations?.length > 0) && attachTo !== c.id && (
            <p className="adm-hint" style={{ margin: '0.75rem 0 0' }}>
              <FiAlertTriangle aria-hidden="true" /> This connection reaches no destination
              yet, so it cannot be activated and nothing can be listed against it.
            </p>
          )}

          {attachTo === c.id && attachForm && (
            <form className="adm-form" style={{ marginTop: '0.75rem' }}
                  onSubmit={(e) => submitAttach(e, c.id)}>
              <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <div className="input-group" style={{ margin: 0, minWidth: 220 }}>
                  <label htmlFor={`dest-${c.id}`}>Destination</label>
                  <select id={`dest-${c.id}`} value={attachForm.destinationCode}
                          onChange={(e) => setAttachForm({ ...attachForm, destinationCode: e.target.value })}>
                    <option value="">Select a destination…</option>
                    {destinations.map((d) => (
                      <option key={d.code} value={d.code}>{d.displayName}</option>
                    ))}
                  </select>
                </div>
                <div className="input-group" style={{ margin: 0, maxWidth: 160 }}>
                  <label htmlFor={`comm-${c.id}`}>Commission (bps)</label>
                  {/* Basis points, matching the platform's existing rate convention —
                      2000 is 20%. Left blank when no rate has been agreed. */}
                  <input id={`comm-${c.id}`} type="number" min="0" max="10000"
                         value={attachForm.commissionBps} placeholder="e.g. 2000"
                         onChange={(e) => setAttachForm({ ...attachForm, commissionBps: e.target.value })} />
                </div>
                <div className="input-group" style={{ margin: 0, minWidth: 190 }}>
                  <label htmlFor={`pay-${c.id}`}>Who collects</label>
                  <select id={`pay-${c.id}`} value={attachForm.paymentResponsibility}
                          onChange={(e) => setAttachForm({
                            ...attachForm, paymentResponsibility: e.target.value })}>
                    {/* Channel-collects first and default: Viator and GetYourGuide are
                        both merchant of record, and defaulting to "the venue collects"
                        would tell an operator to expect cash that never arrives. */}
                    <option value="CHANNEL_COLLECTS">The channel collects</option>
                    <option value="VENUE_COLLECTS">The venue collects</option>
                  </select>
                </div>
                <div className="input-group" style={{ margin: 0, maxWidth: 150 }}>
                  <label htmlFor={`safety-${c.id}`}>Safety inventory</label>
                  <input id={`safety-${c.id}`} type="number" min="0"
                         value={attachForm.safetyInventory}
                         onChange={(e) => setAttachForm({
                           ...attachForm, safetyInventory: e.target.value })} />
                </div>
                <button className="btn btn-primary" type="submit"
                        disabled={!attachForm.destinationCode || busyId === c.id}>
                  Add destination
                </button>
              </div>

              <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem',
                              marginTop: '0.75rem' }}>
                <input type="checkbox" checked={attachForm.enabled}
                       onChange={(e) => setAttachForm({ ...attachForm, enabled: e.target.checked })} />
                {/* Off by default, matching the server. Agreeing terms and putting
                    inventory on sale are two decisions, and collapsing them would
                    publish the moment an operator saved terms still being negotiated. */}
                Enable it now — otherwise it is attached but not selling
              </label>

              {attachForm.destinationCode
                && destinations.find((d) => d.code === attachForm.destinationCode)
                && !destinations.find((d) => d.code === attachForm.destinationCode).deliversReservations && (
                <p className="adm-hint" style={{ marginTop: '0.5rem' }}>
                  <FiAlertTriangle aria-hidden="true" /> This destination is a feed plus a
                  deep link — travellers check out on SK Binge and no reservation is ever
                  delivered back, so nothing will appear in the channel inbox for it.
                </p>
              )}

              {destinations.length === 0 && (
                <p className="adm-hint" style={{ marginTop: '0.5rem' }}>
                  No destination is available for {c.providerName}. Every real destination
                  ships inactive until a super-admin turns it on.
                </p>
              )}
            </form>
          )}

          {c.destinations?.length > 0 && (
            <div className="admin-table-wrap" style={{ marginTop: '0.75rem' }}>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th scope="col">Destination</th>
                    <th scope="col">Enabled</th>
                    <th scope="col">Who collects</th>
                    <th scope="col">Reservations</th>
                  </tr>
                </thead>
                <tbody>
                  {c.destinations.map((d) => (
                    <tr key={d.id}>
                      <td>{d.destinationName}</td>
                      <td>{d.enabled ? 'Yes' : 'No'}</td>
                      <td>{d.paymentResponsibility}</td>
                      {/* Google never delivers a booking back — it is a feed plus a deep
                          link. Saying so here stops an operator waiting for reservations
                          that will never arrive. */}
                      <td>{d.deliversReservations ? 'Delivered' : 'Feed only — no reservations'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
