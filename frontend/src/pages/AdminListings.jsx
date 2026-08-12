import { useState, useEffect, useCallback } from 'react';
import { adminService, bookingService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiUploadCloud, FiAlertTriangle, FiCheckCircle, FiPlus } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Listing readiness per destination (distribution slice 4).
 *
 * <p>Readiness is per <b>(listing × destination)</b>, not per listing: every destination
 * demands different content — meeting point, age restrictions, inclusions, voucher
 * instructions — so a listing that satisfies Viator may be incomplete for GetYourGuide.
 * The table is therefore keyed on the pair, and a listing appears once per destination.
 *
 * <p><b>Blocking reasons are the product here.</b> A bare "78% ready" tells an operator
 * nothing they can act on; the list of what is missing is the entire reason this screen
 * exists rather than a percentage on the connections page.
 *
 * <p>Publish is offered only at 100%, mirroring the server — which refuses below 100,
 * refuses a non-ACTIVE connection, refuses stop-sell, and refuses a destination the
 * venue never enabled. The button is a convenience, never the enforcement.
 */

const STATE_TONE = {
  LIVE: 'success',
  READY: 'info',
  PUBLISHING: 'info',
  DRAFT: 'muted',
  PAUSED: 'warning',
  BLOCKED: 'danger',
  FAILED: 'danger',
};

export default function AdminListings() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [busyId, setBusyId] = useState(null);

  // The evaluate form. The page used to tell an operator to "evaluate an event type
  // against a destination" and offer no way to do it — the endpoint existed, the API
  // wrapper existed, and nothing called it, so a venue's first listing could not be
  // created from the console at all.
  const [showForm, setShowForm] = useState(false);
  const [pairs, setPairs] = useState([]);          // connection↔destination options
  const [eventTypes, setEventTypes] = useState([]);
  const [requirements, setRequirements] = useState([]);
  const [draft, setDraft] = useState({ connectionDestinationId: '', eventTypeId: '', content: {} });
  const [evaluating, setEvaluating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.getDistributionListings();
      setRows(res.data?.data || []);
      setLoaded(true);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load listings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /**
   * Load what an operator must choose between: the venue's connection↔destination
   * pairings, and its event types.
   *
   * <p>A listing is created against a PAIRING, not a destination — the same destination
   * reached through two providers is two different technical routes with their own
   * commercial terms, so the id has to identify which.
   */
  const openForm = async () => {
    setShowForm(true);
    try {
      // The PUBLIC venue-scoped read, not /admin/event-types. Two reasons, both real:
      //
      //   1. /admin/event-types is gated on the EVENT_TYPES module, which the
      //      DISTRIBUTION grant does not imply — so an admin allowed to manage channels
      //      but not the catalogue would get a 403 that killed this whole form.
      //   2. It returns only ACTIVE event types, which is what may be listed. Offering
      //      an inactive one would publish inventory the venue has switched off.
      const [conns, types] = await Promise.all([
        adminService.getDistributionConnections(),
        bookingService.getEventTypes(),
      ]);
      const options = [];
      (conns.data?.data || []).forEach((c) => {
        (c.destinations || []).forEach((d) => {
          options.push({
            id: d.id,
            destinationCode: d.destinationCode,
            label: `${c.providerName} → ${d.destinationName || d.destinationCode}`,
            enabled: d.enabled,
          });
        });
      });
      setPairs(options);
      setEventTypes(types.data?.data || []);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load connections or event types');
    }
  };

  /**
   * A destination's content requirements, fetched when one is chosen.
   *
   * <p>Asked for rather than assumed: each marketplace demands different content, and
   * hardcoding a field list here would drift from the server's readiness policy — which
   * is the thing that actually decides whether a listing may go live.
   */
  const chooseDestination = async (pairId) => {
    setDraft({ connectionDestinationId: pairId, eventTypeId: draft.eventTypeId, content: {} });
    setRequirements([]);
    const pair = pairs.find((p) => String(p.id) === String(pairId));
    if (!pair) return;
    try {
      const res = await adminService.getDistributionListingRequirements(pair.destinationCode);
      setRequirements(res.data?.data || []);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Could not load the destination requirements');
    }
  };

  const evaluate = async (e) => {
    e.preventDefault();
    if (!draft.connectionDestinationId || !draft.eventTypeId) return;
    setEvaluating(true);
    try {
      const res = await adminService.evaluateDistributionListing({
        connectionDestinationId: Number(draft.connectionDestinationId),
        eventTypeId: Number(draft.eventTypeId),
        content: draft.content,
      });
      const pct = res.data?.data?.readinessPct;
      // The percentage is the answer, so it goes in the toast: an operator who filled
      // every box and still sees 71% needs to know that immediately, not after
      // hunting for the row in the table below.
      toast.success(pct === 100
        ? 'Listing is ready to publish'
        : `Readiness recorded — ${pct}%. See what is still blocking it below.`);
      setShowForm(false);
      setDraft({ connectionDestinationId: '', eventTypeId: '', content: {} });
      setRequirements([]);
      await load();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Could not evaluate this listing');
    } finally {
      setEvaluating(false);
    }
  };

  const publish = async (row) => {
    setBusyId(row.id);
    try {
      await adminService.publishDistributionListing(row.id);
      toast.success(`Published to ${row.destinationName || row.destinationCode}`);
      await load();
    } catch (e) {
      // The server's refusal is the useful message — it names the actual blocker
      // (readiness, connection state, stop-sell, destination not enabled).
      toast.error(e.response?.data?.message || 'Could not publish');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiUploadCloud /> Distribution</span>
          <h1>Listing readiness</h1>
          <p>What each event type still needs before it can go live on a destination.</p>
        </div>
        {/* A bare div, not a new class: .adm-header is already a space-between flex
            row, and inventing .adm-header-actions would add a name this stylesheet has
            no definition for — markup that is structurally right and visually broken. */}
        <div>
          <button className="btn btn-primary" onClick={() => (showForm ? setShowForm(false) : openForm())}>
            <FiPlus /> {showForm ? 'Cancel' : 'Evaluate an event type'}
          </button>
        </div>
      </div>

      {showForm && (
        <form className="adm-form" onSubmit={evaluate} style={{ marginBottom: '1rem' }}>
          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <div className="input-group" style={{ margin: 0, minWidth: 260 }}>
              <label htmlFor="pair">Destination</label>
              <select id="pair" value={draft.connectionDestinationId}
                      onChange={(e) => chooseDestination(e.target.value)}>
                <option value="">Select a destination…</option>
                {pairs.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}{p.enabled ? '' : ' (not enabled)'}
                  </option>
                ))}
              </select>
            </div>
            <div className="input-group" style={{ margin: 0, minWidth: 220 }}>
              <label htmlFor="etype">Event type</label>
              <select id="etype" value={draft.eventTypeId}
                      onChange={(e) => setDraft({ ...draft, eventTypeId: e.target.value })}>
                <option value="">Select an event type…</option>
                {eventTypes.map((t) => (
                  <option key={t.id} value={t.id}>{t.name}</option>
                ))}
              </select>
            </div>
            <button className="btn btn-primary" type="submit"
                    disabled={!draft.connectionDestinationId || !draft.eventTypeId || evaluating}>
              {evaluating ? 'Evaluating…' : 'Evaluate'}
            </button>
          </div>

          {/* One input per field the SERVER says this destination requires. Rendered from
              the response rather than a list kept here, because the same policy decides
              whether the listing may publish — a local copy would drift and an operator
              would fill in fields that do not count. */}
          {requirements.length > 0 && (
            <div style={{ marginTop: '1rem', display: 'grid', gap: '0.75rem',
                          gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))' }}>
              {requirements.map((r) => (
                <div className="input-group" style={{ margin: 0 }} key={r.field}>
                  <label htmlFor={`req-${r.field}`}>{r.field}</label>
                  <input id={`req-${r.field}`} type="text"
                         value={draft.content[r.field] || ''}
                         onChange={(e) => setDraft({
                           ...draft,
                           content: { ...draft.content, [r.field]: e.target.value },
                         })} />
                  {/* The instruction, not the field name. "meetingPoint is missing" is a
                      schema error; this is something an operator can act on. */}
                  <p className="adm-hint" style={{ margin: '0.25rem 0 0', fontSize: '0.82em' }}>
                    {r.instruction}
                  </p>
                </div>
              ))}
            </div>
          )}

          {showForm && pairs.length === 0 && (
            <p className="adm-hint" style={{ marginTop: '0.75rem' }}>
              <FiAlertTriangle aria-hidden="true" /> This venue has no connection pointed at
              a destination yet. Add one under Distribution first — a listing is created
              against that pairing, not against a destination on its own.
            </p>
          )}
        </form>
      )}

      {loading && <div className="loading"><div className="spinner"></div></div>}

      {!loading && loaded && rows.length === 0 && (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiUploadCloud /></span>
          <h3>No listings yet</h3>
          {/* Ambiguity is the thing to remove: "empty" here means no connection has been
              pointed at a destination yet, not that something failed. */}
          <p>
            Listings appear once a connection reaches a destination. Set one up under
            Distribution, then evaluate an event type against it.
          </p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">Destination</th>
                <th scope="col">Event type</th>
                <th scope="col">State</th>
                <th scope="col">Readiness</th>
                <th scope="col">Blocking</th>
                <th scope="col"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const ready = r.readinessPct === 100;
                return (
                  <tr key={r.id}>
                    <td>
                      {r.destinationName || r.destinationCode}
                      <div className="adm-hint" style={{ fontSize: '0.8em' }}>{r.destinationCode}</div>
                    </td>
                    <td>{r.externalProductId || `#${r.eventTypeId}`}</td>
                    <td>
                      <span className={`badge badge-${STATE_TONE[r.publishState] || 'muted'}`}>
                        {r.publishState}
                      </span>
                    </td>
                    <td>
                      {ready
                        ? <span><FiCheckCircle aria-label="Ready" /> 100%</span>
                        : <span>{r.readinessPct}%</span>}
                    </td>
                    <td>
                      {r.blockingReasons?.length > 0 ? (
                        // Listed in full, not truncated to a count. "3 issues" sends the
                        // operator hunting; the reasons are what they act on.
                        <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
                          {r.blockingReasons.map((b) => (
                            <li key={b} className="adm-hint">{b}</li>
                          ))}
                        </ul>
                      ) : (
                        <span className="adm-hint">—</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {r.publishState !== 'LIVE' && (
                        <button
                          className="btn btn-primary btn-sm"
                          disabled={!ready || busyId === r.id}
                          // Disabled below 100% because the server will refuse anyway;
                          // the title says why so a greyed button is not a dead end.
                          title={ready ? 'Publish to this destination'
                                       : 'Resolve the blocking items first'}
                          onClick={() => publish(r)}
                        >
                          {busyId === r.id ? 'Publishing…' : 'Publish'}
                        </button>
                      )}
                      {r.publishState === 'LIVE' && (
                        <span className="adm-hint">
                          {r.lastPublishedAt
                            ? `Live since ${new Date(r.lastPublishedAt).toLocaleDateString()}`
                            : 'Live'}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {!loading && rows.some((r) => r.readinessPct < 100) && (
        <p className="adm-table-note">
          <FiAlertTriangle aria-hidden="true" /> A listing cannot go live below 100%.
          The database enforces this too, so no interface can bypass it.
        </p>
      )}
    </div>
  );
}
