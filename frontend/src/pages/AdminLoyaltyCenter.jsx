import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import {
  FiAward, FiSettings, FiLayers, FiGift, FiPackage, FiShuffle,
  FiBarChart2, FiBookOpen, FiRefreshCw, FiPlus, FiCheck, FiX,
  FiAlertTriangle, FiEdit2, FiTrash2, FiExternalLink,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import loyaltyV2 from '../services/loyaltyV2';
import './AdminSecurity.css';

/**
 * Return a safe href for rendering a user-submitted URL.
 * Only http(s) and relative URLs are permitted; everything else
 * (javascript:, data:, vbscript:, file:, etc.) becomes null so that
 * a malicious proof URL cannot execute when an admin clicks it.
 */
function safeExternalHref(url) {
  if (typeof url !== 'string') return null;
  const trimmed = url.trim();
  if (!trimmed) return null;
  if (/^(https?:)?\/\//i.test(trimmed) || trimmed.startsWith('/')) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(trimmed) && !/^https?:/i.test(trimmed)) return null;
    return trimmed;
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(trimmed)) return null;
  return trimmed;
}

/**
 * Super-admin Loyalty Center.
 *
 * Tabs (in order of configuration urgency):
 *   1. Program      — global settings (welcome bonus, expiry days, …).
 *   2. Tiers        — ladder CRUD with effective-date inserts.
 *   3. Perks        — catalog CRUD + tier assignments.
 *   4. Binges       — bulk enable / disable with checkbox selection.
 *   5. Status Match — pending request review queue.
 *   6. Parity       — v1 ↔ v2 reconciliation.
 *   7. Playbook     — operator dos & don'ts.
 *
 * All mutations go through the loyaltyV2 service, which hits
 * `/api/v2/loyalty/super-admin/*`. The page uses full-reload
 * semantics for single-row edits because an edit can implicitly
 * retire a prior row and the backend is the source of truth for
 * what the current active ladder looks like.
 */
export default function AdminLoyaltyCenter() {
  const [tab, setTab] = useState('program');

  const TABS = [
    ['program',      'Program',      <FiSettings />],
    ['tiers',        'Tiers',        <FiLayers />],
    ['perks',        'Perks',        <FiGift />],
    ['binges',       'Binges',       <FiPackage />],
    ['status-match', 'Status Match', <FiShuffle />],
    ['parity',       'Parity',       <FiBarChart2 />],
    ['playbook',     'Playbook',     <FiBookOpen />],
  ];

  return (
    <div className="sec-page">
      <SEO title="Loyalty Center · Super Admin" description="Program-wide loyalty configuration" />

      <div className="sec-header">
        <div className="sec-header-copy">
          <span className="sec-kicker"><FiAward /> LOYALTY PROGRAM</span>
          <h1>Loyalty Center</h1>
          <p>
            Program-wide configuration — tiers, perks, binge enrollment, and status-match reviews.
            Edits here ship to every customer in real time; use effective-dated inserts for ladder changes.
          </p>
        </div>
      </div>

      <nav className="sec-tabs">
        {TABS.map(([k, label]) => (
          <button key={k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </nav>

      {tab === 'program'      && <ProgramTab />}
      {tab === 'tiers'        && <TiersTab />}
      {tab === 'perks'        && <PerksTab />}
      {tab === 'binges'       && <BindingsTab />}
      {tab === 'status-match' && <StatusMatchTab />}
      {tab === 'parity'       && <ParityTab />}
      {tab === 'playbook'     && <PlaybookTab />}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Program settings
   ────────────────────────────────────────────────────────────── */
function ProgramTab() {
  const [program, setProgram] = useState(null);
  const [saving, setSaving]   = useState(false);

  useEffect(() => {
    loyaltyV2.getProgram().then(setProgram).catch(() => toast.error('Could not load program'));
  }, []);

  const save = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      const updated = await loyaltyV2.updateProgram(program);
      setProgram(updated);
      toast.success('Program saved');
    } catch { toast.error('Save failed'); }
    finally { setSaving(false); }
  };

  if (!program) return <SkeletonGrid count={3} />;

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Program settings</h2>
          <p>Applied globally to every customer wallet. Changes save instantly.</p>
        </div>
      </div>

      <form className="sec-form" onSubmit={save}>
        <label>
          Display name
          <input
            value={program.displayName || ''}
            onChange={(e) => setProgram({ ...program, displayName: e.target.value })}
          />
        </label>
        <label>
          Welcome bonus points
          <input
            type="number"
            value={program.welcomeBonusPoints ?? 0}
            onChange={(e) => setProgram({ ...program, welcomeBonusPoints: Number(e.target.value) })}
          />
        </label>
        <label>
          Points expiry (days)
          <input
            type="number"
            value={program.pointsExpiryDays ?? 540}
            onChange={(e) => setProgram({ ...program, pointsExpiryDays: Number(e.target.value) })}
          />
        </label>
        <label>
          Retroactive credit window (days)
          <input
            type="number"
            value={program.retroactiveCreditDays ?? 60}
            onChange={(e) => setProgram({ ...program, retroactiveCreditDays: Number(e.target.value) })}
          />
        </label>
        <label className="sec-checkbox">
          <input
            type="checkbox"
            checked={!!program.active}
            onChange={(e) => setProgram({ ...program, active: e.target.checked })}
          />
          Program active
        </label>
        <div className="sec-form-actions">
          <button className="sec-btn sec-btn-primary" disabled={saving}>
            {saving ? 'Saving…' : <><FiCheck /> Save settings</>}
          </button>
        </div>
      </form>
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Tier ladder
   ────────────────────────────────────────────────────────────── */
function TiersTab() {
  const [tiers, setTiers] = useState([]);
  const [draft, setDraft] = useState(null);

  const reload = useCallback(
    () => loyaltyV2.listTiers().then(setTiers).catch(() => toast.error('Tier load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const save = async (e) => {
    e.preventDefault();
    try {
      await loyaltyV2.upsertTier(draft);
      toast.success('Tier saved');
      setDraft(null);
      reload();
    } catch { toast.error('Save failed'); }
  };
  const retire = async (t) => {
    if (!window.confirm(`Retire tier "${t.tierCode}"? This is effective-dated — prior wallet balances are preserved.`)) return;
    try {
      await loyaltyV2.retireTier(t.id);
      toast.success('Tier retired');
      reload();
    } catch { toast.error('Retire failed'); }
  };

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Active tier ladder</h2>
          <p>Edits create a new effective-dated row; historical accruals stay reproducible.</p>
        </div>
        <button
          className="sec-btn sec-btn-primary"
          onClick={() => setDraft({
            tierCode: '', displayName: '', sequence: tiers.length + 1,
            minQualifyingCredits: 0, pointsMultiplier: 1.0,
            softLandingTierCode: '', validityCalendarYearsAfter: 1,
            lifetime: false,
          })}
        >
          <FiPlus /> Add tier
        </button>
      </div>

      {tiers.length === 0 ? (
        <div className="sec-empty"><h3>No tiers yet</h3><p>Add your first tier to bootstrap the ladder.</p></div>
      ) : (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>#</th><th>Tier</th><th>Name</th>
                <th>Min credits</th><th>Multiplier</th>
                <th>Soft-land</th><th>Validity (yr)</th><th>Lifetime</th><th></th>
              </tr>
            </thead>
            <tbody>
              {tiers.map((t) => (
                <tr key={t.id}>
                  <td>{t.sequence}</td>
                  <td><TierChip code={t.tierCode} /></td>
                  <td style={{ fontWeight: 600 }}>{t.displayName}</td>
                  <td>{Number(t.minQualifyingCredits || 0).toLocaleString()}</td>
                  <td>{Number(t.pointsMultiplier || 1).toFixed(2)}×</td>
                  <td>{t.softLandingTierCode ? <TierChip code={t.softLandingTierCode} /> : '—'}</td>
                  <td>{t.validityCalendarYearsAfter ?? '—'}</td>
                  <td>{t.lifetime ? <span className="sec-pill success">Lifetime</span> : '—'}</td>
                  <td>
                    <div className="sec-row-actions">
                      <button className="sec-btn" onClick={() => setDraft({ ...t })}><FiEdit2 /> Edit</button>
                      <button className="sec-btn sec-btn-danger" onClick={() => retire(t)}><FiTrash2 /> Retire</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {draft && (
        <div className="sec-editor">
          <h4>{draft.id ? 'Edit tier — creates new effective-dated row' : 'New tier'}</h4>
          <form className="sec-form" onSubmit={save}>
            <label>
              Code
              <input required value={draft.tierCode || ''}
                     onChange={(e) => setDraft({ ...draft, tierCode: e.target.value.toUpperCase() })} />
            </label>
            <label>
              Display name
              <input required value={draft.displayName || ''}
                     onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
            </label>
            <label>
              Sequence
              <input type="number" value={draft.sequence ?? 1}
                     onChange={(e) => setDraft({ ...draft, sequence: Number(e.target.value) })} />
            </label>
            <label>
              Min qualifying credits
              <input type="number" value={draft.minQualifyingCredits ?? 0}
                     onChange={(e) => setDraft({ ...draft, minQualifyingCredits: Number(e.target.value) })} />
            </label>
            <label>
              Points multiplier
              <input type="number" step="0.05" value={draft.pointsMultiplier ?? 1}
                     onChange={(e) => setDraft({ ...draft, pointsMultiplier: Number(e.target.value) })} />
            </label>
            <label>
              Soft-landing tier code
              <input value={draft.softLandingTierCode || ''}
                     onChange={(e) => setDraft({ ...draft, softLandingTierCode: e.target.value.toUpperCase() })} />
            </label>
            <label>
              Validity (calendar years after year earned)
              <input type="number" value={draft.validityCalendarYearsAfter ?? 1}
                     onChange={(e) => setDraft({ ...draft, validityCalendarYearsAfter: Number(e.target.value) })} />
            </label>
            <label className="sec-checkbox">
              <input type="checkbox" checked={!!draft.lifetime}
                     onChange={(e) => setDraft({ ...draft, lifetime: e.target.checked })} />
              Lifetime tier
            </label>
            <div className="sec-form-actions">
              <button type="button" className="sec-btn" onClick={() => setDraft(null)}>Cancel</button>
              <button className="sec-btn sec-btn-primary"><FiCheck /> Save tier</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Perk catalog
   ────────────────────────────────────────────────────────────── */
function PerksTab() {
  const [perks, setPerks] = useState([]);
  const [draft, setDraft] = useState(null);

  const reload = useCallback(
    () => loyaltyV2.listPerks().then(setPerks).catch(() => toast.error('Perk load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const save = async (e) => {
    e.preventDefault();
    try {
      await loyaltyV2.savePerk(draft);
      toast.success('Perk saved');
      setDraft(null);
      reload();
    } catch { toast.error('Save failed'); }
  };

  const HANDLERS = [
    'tierDiscountPercent','freeCancellationExtended','extraMultiplier','priorityWaitlist',
    'earlyAccessBookingWindow','birthdayBonusPoints','welcomeBonusPoints','extensionMonths',
    'rewardCatalogClaim','surpriseDelightBudget',
  ];

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Perk catalog</h2>
          <p>Define perks available to tier-assigned customers. Each perk maps to a handler in the earn/redeem engine.</p>
        </div>
        <button
          className="sec-btn sec-btn-primary"
          onClick={() => setDraft({ perkKey: '', displayName: '', handlerKey: '', active: true })}
        >
          <FiPlus /> Add perk
        </button>
      </div>

      {perks.length === 0 ? (
        <div className="sec-empty"><h3>No perks defined</h3></div>
      ) : (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr><th>Key</th><th>Display name</th><th>Handler</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {perks.map((p) => (
                <tr key={p.id}>
                  <td><code>{p.perkKey}</code></td>
                  <td style={{ fontWeight: 600 }}>{p.displayName}</td>
                  <td><code style={{ fontSize: 12 }}>{p.handlerKey}</code></td>
                  <td>
                    <span className={`sec-pill ${p.active ? 'success' : 'mfa-off'}`}>
                      {p.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td>
                    <button className="sec-btn" onClick={() => setDraft({ ...p })}>
                      <FiEdit2 /> Edit
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {draft && (
        <div className="sec-editor">
          <h4>{draft.id ? 'Edit perk' : 'New perk'}</h4>
          <form className="sec-form" onSubmit={save}>
            <label>
              Perk key
              <input required value={draft.perkKey || ''}
                     onChange={(e) => setDraft({ ...draft, perkKey: e.target.value })} />
            </label>
            <label>
              Display name
              <input required value={draft.displayName || ''}
                     onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
            </label>
            <label>
              Handler key
              <select value={draft.handlerKey || ''}
                      onChange={(e) => setDraft({ ...draft, handlerKey: e.target.value })}>
                <option value="">— select —</option>
                {HANDLERS.map((h) => <option key={h} value={h}>{h}</option>)}
              </select>
            </label>
            <label className="sec-checkbox">
              <input type="checkbox" checked={!!draft.active}
                     onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
              Active
            </label>
            <div className="sec-form-actions">
              <button type="button" className="sec-btn" onClick={() => setDraft(null)}>Cancel</button>
              <button className="sec-btn sec-btn-primary"><FiCheck /> Save perk</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Binge bindings (bulk enable / disable)
   ────────────────────────────────────────────────────────────── */
function BindingsTab() {
  const [bindings, setBindings] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [busy, setBusy] = useState(false);

  const reload = useCallback(
    () => loyaltyV2.listBindings()
      .then((rows) => { setBindings(rows || []); setSelected(new Set()); })
      .catch(() => toast.error('Binding load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const toggle = (id) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelected(next);
  };
  const toggleAll = () => {
    const eligible = bindings.filter((b) => !b.legacyFrozen);
    if (selected.size === eligible.length) setSelected(new Set());
    else setSelected(new Set(eligible.map((b) => b.id)));
  };

  const bulkDo = async (status) => {
    if (selected.size === 0) { toast.info('Select at least one binge'); return; }
    if (!window.confirm(`${status === 'ENABLED' ? 'Enable' : 'Disable'} loyalty for ${selected.size} binge(s)?`)) return;
    setBusy(true);
    try {
      const touched = await loyaltyV2.bulkSetBindingStatus(Array.from(selected), status);
      toast.success(`${touched} binding(s) updated`);
      reload();
    } catch { toast.error('Bulk action failed'); }
    finally { setBusy(false); }
  };

  const eligible = bindings.filter((b) => !b.legacyFrozen);
  const allChecked = eligible.length > 0 && selected.size === eligible.length;

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Binge bindings <span style={{ color: 'var(--text-muted)', fontWeight: 500 }}>({bindings.length})</span></h2>
          <p>Bulk-toggle loyalty participation per binge. Legacy-frozen rows are immutable until migrated.</p>
        </div>
        <div className="sec-actions">
          {selected.size > 0 && (
            <span className="sec-selection-hint">{selected.size} selected</span>
          )}
          <button className="sec-btn sec-btn-primary" disabled={busy || selected.size === 0}
                  onClick={() => bulkDo('ENABLED')}>
            <FiCheck /> Enable selected
          </button>
          <button className="sec-btn sec-btn-danger" disabled={busy || selected.size === 0}
                  onClick={() => bulkDo('DISABLED')}>
            <FiX /> Disable selected
          </button>
        </div>
      </div>

      {bindings.length === 0 ? (
        <div className="sec-empty"><h3>No binge bindings</h3></div>
      ) : (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>
                  <input type="checkbox" checked={allChecked} onChange={toggleAll}
                         disabled={eligible.length === 0} />
                </th>
                <th>Binge ID</th>
                <th>Status</th>
                <th>Legacy frozen</th>
                <th>Enrolled at</th>
                <th>By admin</th>
              </tr>
            </thead>
            <tbody>
              {bindings.map((b) => (
                <tr key={b.id}>
                  <td>
                    <input type="checkbox"
                           disabled={b.legacyFrozen}
                           checked={selected.has(b.id)}
                           onChange={() => toggle(b.id)} />
                  </td>
                  <td><code>#{b.bingeId}</code></td>
                  <td>
                    <span className={`sec-pill ${b.status === 'ENABLED' ? 'success' : 'mfa-off'}`}>
                      {b.status}
                    </span>
                  </td>
                  <td>{b.legacyFrozen ? <span className="sec-pill warn">Frozen</span> : '—'}</td>
                  <td>{b.enrolledAt ? new Date(b.enrolledAt).toLocaleDateString() : '—'}</td>
                  <td>{b.enrolledByAdminId ? `#${b.enrolledByAdminId}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Status match review queue
   ────────────────────────────────────────────────────────────── */
function StatusMatchTab() {
  const [pending, setPending] = useState({ content: [], totalElements: 0 });

  const reload = useCallback(
    () => loyaltyV2.listPendingStatusMatches({ size: 50 }).then(setPending)
        .catch(() => toast.error('Load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const approve = async (r) => {
    const notes = window.prompt('Approval notes (optional)') || '';
    try {
      await loyaltyV2.approveStatusMatch(r.id, { notes, challengeDays: 90 });
      toast.success('Approved — 90-day challenge started');
      reload();
    } catch { toast.error('Approve failed'); }
  };
  const reject = async (r) => {
    const notes = window.prompt('Reason for rejection');
    if (!notes) return;
    try {
      await loyaltyV2.rejectStatusMatch(r.id, { notes });
      toast.success('Rejected');
      reload();
    } catch { toast.error('Reject failed'); }
  };

  const rows = pending.content || [];

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Pending status-match requests <span style={{ color: 'var(--text-muted)', fontWeight: 500 }}>({pending.totalElements ?? 0})</span></h2>
          <p>Customers requesting tier-match against a competitor's program. Approved matches start a 90-day challenge.</p>
        </div>
        <button className="sec-btn" onClick={reload}><FiRefreshCw /> Refresh</button>
      </div>

      {rows.length === 0 ? (
        <div className="sec-empty"><h3>Nothing pending</h3><p>Nice — everyone's caught up.</p></div>
      ) : (
        <div className="sec-table-wrap">
          <table className="sec-table">
            <thead>
              <tr>
                <th>Member</th><th>Competitor</th><th>Requested tier</th>
                <th>Proof</th><th>Submitted</th><th></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td><code>#{r.membershipId}</code></td>
                  <td>
                    <div style={{ fontWeight: 600 }}>{r.competitorProgramName}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{r.competitorTierName}</div>
                  </td>
                  <td><TierChip code={r.requestedTierCode} /></td>
                  <td>
                    {(() => {
                      const safe = safeExternalHref(r.proofUrl);
                      return safe
                        ? <a href={safe} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)', fontWeight: 600 }}>
                            View <FiExternalLink style={{ verticalAlign: '-2px' }} />
                          </a>
                        : <span style={{ color: 'var(--text-muted)' }} title={r.proofUrl || ''}>n/a</span>;
                    })()}
                  </td>
                  <td>{new Date(r.createdAt).toLocaleDateString()}</td>
                  <td>
                    <div className="sec-row-actions">
                      <button className="sec-btn sec-btn-primary" onClick={() => approve(r)}>
                        <FiCheck /> Approve
                      </button>
                      <button className="sec-btn sec-btn-danger" onClick={() => reject(r)}>
                        <FiX /> Reject
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Parity (v1 ↔ v2)
   ────────────────────────────────────────────────────────────── */
function ParityTab() {
  const [summary, setSummary] = useState(null);
  const [busy, setBusy]       = useState(false);

  const run = async () => {
    if (!window.confirm('Run parity now? This is read-only and may take up to a minute.')) return;
    setBusy(true);
    try {
      const s = await loyaltyV2.runParityNow();
      setSummary(s);
      const mismatches = (s.v1Ahead || 0) + (s.v2Ahead || 0);
      toast.success(`Checked ${s.customersChecked} customers — ${mismatches} mismatches, ${s.v2Missing} missing v2, ${s.v1Missing} missing v1`);
    } catch { toast.error('Parity run failed'); }
    finally { setBusy(false); }
  };

  const mismatches = summary ? (summary.v1Ahead || 0) + (summary.v2Ahead || 0) : 0;

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>v1 ↔ v2 parity audit</h2>
          <p>
            Dual-write shadow comparison. Runs automatically every night at 04:00 UTC;
            use the button to trigger an on-demand check.
          </p>
        </div>
        <button className="sec-btn sec-btn-primary" disabled={busy} onClick={run}>
          {busy ? 'Running…' : <><FiRefreshCw /> Run parity now</>}
        </button>
      </div>

      <div className="mfa-warning">
        <FiAlertTriangle size={18} />
        <span>
          While the program runs in dual-write shadow mode, this job compares v1 and v2 wallet
          balances per customer and emits Micrometer counters
          (<code>loyalty.parity.mismatch</code>, <code>loyalty.parity.missing</code>).
          It is gated off once <code>APP_LOYALTY_V2_PRIMARY=true</code>.
        </span>
      </div>

      {summary && (
        <>
          <div className="sec-stats" style={{ marginTop: 16 }}>
            <div className="sec-stat">
              <div className="sec-stat-icon"><FiBarChart2 /></div>
              <div className="sec-stat-label">Customers checked</div>
              <div className="sec-stat-value">{Number(summary.customersChecked || 0).toLocaleString()}</div>
            </div>
            <div className="sec-stat">
              <div className="sec-stat-icon" style={{ background: 'rgba(34,197,94,0.15)', color: '#16a34a' }}><FiCheck /></div>
              <div className="sec-stat-label">Matches</div>
              <div className="sec-stat-value">{Number(summary.matches || 0).toLocaleString()}</div>
            </div>
            <div className="sec-stat">
              <div className="sec-stat-icon" style={{ background: 'rgba(239,68,68,0.12)', color: '#dc2626' }}><FiAlertTriangle /></div>
              <div className="sec-stat-label">Mismatches</div>
              <div className="sec-stat-value">{mismatches.toLocaleString()}</div>
            </div>
            <div className="sec-stat">
              <div className="sec-stat-icon" style={{ background: 'rgba(234,179,8,0.15)', color: '#a16207' }}><FiX /></div>
              <div className="sec-stat-label">Missing rows</div>
              <div className="sec-stat-value">{Number((summary.v1Missing || 0) + (summary.v2Missing || 0)).toLocaleString()}</div>
            </div>
          </div>

          <table className="sec-kv-table">
            <tbody>
              <tr><th>v1 ahead</th><td>{summary.v1Ahead}</td></tr>
              <tr><th>v2 ahead</th><td>{summary.v2Ahead}</td></tr>
              <tr><th>Missing in v2</th><td>{summary.v2Missing}</td></tr>
              <tr><th>Missing in v1</th><td>{summary.v1Missing}</td></tr>
              <tr><th>Total |Δ| points</th><td>{Number(summary.totalDiff || 0).toLocaleString()}</td></tr>
              <tr><th>Started at</th><td>{String(summary.startedAt)}</td></tr>
              <tr><th>Finished at</th><td>{String(summary.finishedAt)}</td></tr>
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Playbook
   ────────────────────────────────────────────────────────────── */
function PlaybookTab() {
  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Operator playbook</h2>
          <p>Short reference for super admins running the loyalty program day-to-day.</p>
        </div>
      </div>

      <div className="sec-playbook-grid">
        <div className="sec-playbook-card do">
          <h3><FiCheck /> Do</h3>
          <ul>
            <li>Use <strong>effective dates</strong> when changing tier thresholds or earn/redeem rules — never edit a live row in place.</li>
            <li>Communicate <strong>tier changes</strong> at least 30 days in advance. Promotions are immediate; demotions are deferred.</li>
            <li>Review the <strong>Status Match</strong> queue weekly. Approved members get a 90-day challenge.</li>
            <li>Watch the <strong>Parity</strong> tab during the shadow period. Investigate mismatches before flipping <code>APP_LOYALTY_V2_PRIMARY=true</code>.</li>
            <li>For per-binge configuration, use <em>Binge Management → Loyalty</em> on the venue card.</li>
          </ul>
        </div>

        <div className="sec-playbook-card dont">
          <h3><FiX /> Don't</h3>
          <ul>
            <li>Don't disable a binge binding to "fix" a calculation bug — that strands accrued points.</li>
            <li>Don't manually edit wallet balances in the database; reconciliation breaks without a ledger entry.</li>
            <li>Don't thaw a <code>LEGACY_FROZEN</code> binding until v1 has been migrated and reconciled.</li>
            <li>Don't promise members a tier — promotions are computed from qualifying credits.</li>
            <li>Don't lower <code>pointsExpiryDays</code> without a 90-day grace announcement.</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiBookOpen /> Cutover checklist (v1 → v2 primary)</h3>
          <ol>
            <li>Run V22 backfill migration and verify <code>loyalty_membership</code> count ≈ <code>loyalty_accounts</code> count.</li>
            <li>Run the parity job nightly for at least 7 days with zero mismatches.</li>
            <li>Set <code>APP_LOYALTY_V2_PRIMARY=true</code>. v1 earn/expire becomes a no-op; v2 authoritative.</li>
            <li>Monitor <code>loyalty.v2.*</code> Micrometer counters and customer-support tickets for 30 days.</li>
            <li>Schedule the V23 v1-drop migration after the soak period.</li>
          </ol>
        </div>
      </div>
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Shared: tier chip
   ────────────────────────────────────────────────────────────── */
function TierChip({ code }) {
  const c = String(code || '').toUpperCase();
  if (!c) return <span style={{ color: 'var(--text-muted)' }}>—</span>;
  return <span className={`sec-tier-chip sec-tier-${c}`}>{c}</span>;
}
