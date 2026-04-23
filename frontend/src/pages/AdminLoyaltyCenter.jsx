import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import loyaltyV2 from '../services/loyaltyV2';
import './AdminPages.css';

/**
 * Super-admin Loyalty Center.
 *
 * Tabs (in order of configuration urgency):
 *
 *   1.  Program — global settings (welcome bonus, expiry days, …).
 *   2.  Tiers   — ladder CRUD with effective-date inserts.
 *   3.  Perks   — catalog CRUD + tier assignments.
 *   4.  Binges  — bulk enable / disable with checkbox selection.
 *   5.  Status Match — pending request review queue.
 *
 * All mutations go through the loyaltyV2 service, which hits
 * {@code /api/v2/loyalty/super-admin/*}.  The page stays responsive
 * via optimistic updates for bulk actions (rolled back on error) but
 * conservative full-reload semantics for single-row edits, because
 * an edit can implicitly retire a prior row and the backend is the
 * source of truth for what the current active ladder looks like.
 */
export default function AdminLoyaltyCenter() {
  const [tab, setTab] = useState('program');

  return (
    <div className="admin-page">
      <SEO title="Loyalty Center · Super Admin" description="Program-wide loyalty configuration" />

      <header className="admin-page__header">
        <h1>Loyalty Center</h1>
        <p>Program-wide configuration — tiers, perks, binge enrollment, and status-match reviews.</p>
      </header>

      <nav className="admin-tabs">
        {[
          ['program', 'Program'],
          ['tiers', 'Tiers'],
          ['perks', 'Perks'],
          ['binges', 'Binges'],
          ['status-match', 'Status Match'],
          ['parity', 'Parity (v1↔v2)'],
          ['playbook', 'Playbook'],
        ].map(([k, label]) => (
          <button
            key={k}
            className={tab === k ? 'active' : ''}
            onClick={() => setTab(k)}
          >{label}</button>
        ))}
      </nav>

      {tab === 'program' && <ProgramTab />}
      {tab === 'tiers' && <TiersTab />}
      {tab === 'perks' && <PerksTab />}
      {tab === 'binges' && <BindingsTab />}
      {tab === 'status-match' && <StatusMatchTab />}
      {tab === 'parity' && <ParityTab />}
      {tab === 'playbook' && <PlaybookTab />}
    </div>
  );
}

// ── Program tab ─────────────────────────────────────────────────────────

function ProgramTab() {
  const [program, setProgram] = useState(null);
  const [saving, setSaving] = useState(false);

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
    <form className="stacked-form admin-card" onSubmit={save}>
      <label>Display name
        <input value={program.displayName || ''}
               onChange={(e) => setProgram({ ...program, displayName: e.target.value })} />
      </label>
      <label>Welcome bonus points
        <input type="number" value={program.welcomeBonusPoints ?? 0}
               onChange={(e) => setProgram({ ...program, welcomeBonusPoints: Number(e.target.value) })} />
      </label>
      <label>Points expiry (days)
        <input type="number" value={program.pointsExpiryDays ?? 540}
               onChange={(e) => setProgram({ ...program, pointsExpiryDays: Number(e.target.value) })} />
      </label>
      <label>Retroactive credit window (days)
        <input type="number" value={program.retroactiveCreditDays ?? 60}
               onChange={(e) => setProgram({ ...program, retroactiveCreditDays: Number(e.target.value) })} />
      </label>
      <label>
        <input type="checkbox" checked={!!program.active}
               onChange={(e) => setProgram({ ...program, active: e.target.checked })} />
        Program active
      </label>
      <button className="btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save'}</button>
    </form>
  );
}

// ── Tiers tab ───────────────────────────────────────────────────────────

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
    if (!confirm(`Retire tier "${t.tierCode}"? This is effective-dated — prior wallet balances are preserved.`)) return;
    try {
      await loyaltyV2.retireTier(t.id);
      toast.success('Tier retired');
      reload();
    } catch { toast.error('Retire failed'); }
  };

  return (
    <div className="admin-card">
      <div className="row-between">
        <h3>Active tier ladder</h3>
        <button className="btn-primary" onClick={() => setDraft({
          tierCode: '', displayName: '', sequence: tiers.length + 1,
          minQualifyingCredits: 0, pointsMultiplier: 1.0,
          softLandingTierCode: '', validityCalendarYearsAfter: 1,
          lifetime: false,
        })}>Add tier</button>
      </div>

      <table className="plain-table">
        <thead>
          <tr>
            <th>#</th><th>Code</th><th>Name</th><th>Min credits</th>
            <th>Multiplier</th><th>Soft-land</th><th>Validity (yr)</th><th>Lifetime?</th><th></th>
          </tr>
        </thead>
        <tbody>
          {tiers.map((t) => (
            <tr key={t.id}>
              <td>{t.sequence}</td><td>{t.tierCode}</td><td>{t.displayName}</td>
              <td>{t.minQualifyingCredits}</td><td>{t.pointsMultiplier}</td>
              <td>{t.softLandingTierCode || '—'}</td>
              <td>{t.validityCalendarYearsAfter ?? '—'}</td>
              <td>{t.lifetime ? '✓' : ''}</td>
              <td>
                <button onClick={() => setDraft({ ...t })}>Edit</button>
                <button className="danger" onClick={() => retire(t)}>Retire</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {draft && (
        <form className="stacked-form" onSubmit={save}>
          <h4>{draft.id ? 'Edit tier (creates new effective-dated row)' : 'New tier'}</h4>
          <label>Code <input required value={draft.tierCode || ''}
            onChange={(e) => setDraft({ ...draft, tierCode: e.target.value.toUpperCase() })} /></label>
          <label>Display name <input required value={draft.displayName || ''}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} /></label>
          <label>Sequence <input type="number" value={draft.sequence ?? 1}
            onChange={(e) => setDraft({ ...draft, sequence: Number(e.target.value) })} /></label>
          <label>Min qualifying credits <input type="number" value={draft.minQualifyingCredits ?? 0}
            onChange={(e) => setDraft({ ...draft, minQualifyingCredits: Number(e.target.value) })} /></label>
          <label>Points multiplier <input type="number" step="0.05" value={draft.pointsMultiplier ?? 1}
            onChange={(e) => setDraft({ ...draft, pointsMultiplier: Number(e.target.value) })} /></label>
          <label>Soft-landing tier code <input value={draft.softLandingTierCode || ''}
            onChange={(e) => setDraft({ ...draft, softLandingTierCode: e.target.value.toUpperCase() })} /></label>
          <label>Validity (calendar years after year earned) <input type="number"
            value={draft.validityCalendarYearsAfter ?? 1}
            onChange={(e) => setDraft({ ...draft, validityCalendarYearsAfter: Number(e.target.value) })} /></label>
          <label><input type="checkbox" checked={!!draft.lifetime}
            onChange={(e) => setDraft({ ...draft, lifetime: e.target.checked })} />Lifetime tier</label>
          <div className="row-between">
            <button type="button" onClick={() => setDraft(null)}>Cancel</button>
            <button className="btn-primary">Save</button>
          </div>
        </form>
      )}
    </div>
  );
}

// ── Perks tab ───────────────────────────────────────────────────────────

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

  return (
    <div className="admin-card">
      <div className="row-between">
        <h3>Perk catalog</h3>
        <button className="btn-primary" onClick={() => setDraft({
          perkKey: '', displayName: '', handlerKey: '', active: true,
        })}>Add perk</button>
      </div>
      <table className="plain-table">
        <thead>
          <tr><th>Key</th><th>Name</th><th>Handler</th><th>Active</th><th></th></tr>
        </thead>
        <tbody>
          {perks.map((p) => (
            <tr key={p.id}>
              <td>{p.perkKey}</td><td>{p.displayName}</td><td>{p.handlerKey}</td>
              <td>{p.active ? '✓' : ''}</td>
              <td><button onClick={() => setDraft({ ...p })}>Edit</button></td>
            </tr>
          ))}
        </tbody>
      </table>

      {draft && (
        <form className="stacked-form" onSubmit={save}>
          <h4>{draft.id ? 'Edit perk' : 'New perk'}</h4>
          <label>Perk key <input required value={draft.perkKey || ''}
            onChange={(e) => setDraft({ ...draft, perkKey: e.target.value })} /></label>
          <label>Display name <input required value={draft.displayName || ''}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} /></label>
          <label>Handler key
            <select value={draft.handlerKey || ''}
                    onChange={(e) => setDraft({ ...draft, handlerKey: e.target.value })}>
              <option value="">— select —</option>
              <option value="tierDiscountPercent">tierDiscountPercent</option>
              <option value="freeCancellationExtended">freeCancellationExtended</option>
              <option value="extraMultiplier">extraMultiplier</option>
              <option value="priorityWaitlist">priorityWaitlist</option>
              <option value="earlyAccessBookingWindow">earlyAccessBookingWindow</option>
              <option value="birthdayBonusPoints">birthdayBonusPoints</option>
              <option value="welcomeBonusPoints">welcomeBonusPoints</option>
              <option value="extensionMonths">extensionMonths</option>
              <option value="rewardCatalogClaim">rewardCatalogClaim</option>
              <option value="surpriseDelightBudget">surpriseDelightBudget</option>
            </select>
          </label>
          <label><input type="checkbox" checked={!!draft.active}
            onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />Active</label>
          <div className="row-between">
            <button type="button" onClick={() => setDraft(null)}>Cancel</button>
            <button className="btn-primary">Save</button>
          </div>
        </form>
      )}
    </div>
  );
}

// ── Bindings tab (the star feature: bulk enable/disable) ────────────────

function BindingsTab() {
  const [bindings, setBindings] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [busy, setBusy] = useState(false);

  const reload = useCallback(
    () => loyaltyV2.listBindings().then((rows) => { setBindings(rows || []); setSelected(new Set()); })
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
    if (selected.size === bindings.length) setSelected(new Set());
    else setSelected(new Set(bindings.map((b) => b.id)));
  };

  const bulkDo = async (status) => {
    if (selected.size === 0) { toast.info('Select at least one binge'); return; }
    if (!confirm(`${status === 'ENABLED' ? 'Enable' : 'Disable'} loyalty for ${selected.size} binge(s)?`)) return;
    setBusy(true);
    try {
      const touched = await loyaltyV2.bulkSetBindingStatus(Array.from(selected), status);
      toast.success(`${touched} binding(s) updated`);
      reload();
    } catch { toast.error('Bulk action failed'); }
    finally { setBusy(false); }
  };

  const allChecked = bindings.length > 0 && selected.size === bindings.length;

  return (
    <div className="admin-card">
      <div className="row-between">
        <h3>Binge bindings ({bindings.length})</h3>
        <div className="btn-group">
          <button className="btn-primary" disabled={busy} onClick={() => bulkDo('ENABLED')}>
            Enable selected
          </button>
          <button className="danger" disabled={busy} onClick={() => bulkDo('DISABLED')}>
            Disable selected
          </button>
        </div>
      </div>

      <table className="plain-table">
        <thead>
          <tr>
            <th><input type="checkbox" checked={allChecked} onChange={toggleAll} /></th>
            <th>Binge ID</th><th>Status</th><th>Legacy frozen?</th>
            <th>Enrolled at</th><th>By</th>
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
              <td>{b.bingeId}</td>
              <td><span className={`badge badge-${b.status?.toLowerCase()}`}>{b.status}</span></td>
              <td>{b.legacyFrozen ? '✓' : ''}</td>
              <td>{b.enrolledAt ? new Date(b.enrolledAt).toLocaleDateString() : '—'}</td>
              <td>{b.enrolledByAdminId || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ── Status match review queue ───────────────────────────────────────────

function StatusMatchTab() {
  const [pending, setPending] = useState({ content: [], totalElements: 0 });
  const reload = useCallback(
    () => loyaltyV2.listPendingStatusMatches({ size: 50 }).then(setPending)
          .catch(() => toast.error('Load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const approve = async (r) => {
    const notes = prompt('Approval notes (optional)') || '';
    try {
      await loyaltyV2.approveStatusMatch(r.id, { notes, challengeDays: 90 });
      toast.success('Approved');
      reload();
    } catch { toast.error('Approve failed'); }
  };
  const reject = async (r) => {
    const notes = prompt('Reason for rejection');
    if (!notes) return;
    try {
      await loyaltyV2.rejectStatusMatch(r.id, { notes });
      toast.success('Rejected');
      reload();
    } catch { toast.error('Reject failed'); }
  };

  return (
    <div className="admin-card">
      <h3>Pending status-match requests ({pending.totalElements ?? 0})</h3>
      {(pending.content || []).length === 0 ? <p className="empty-state">Nothing pending — nice!</p> : (
        <table className="plain-table">
          <thead>
            <tr>
              <th>Member</th><th>Competitor</th><th>Requested tier</th>
              <th>Proof</th><th>Submitted</th><th></th>
            </tr>
          </thead>
          <tbody>
            {pending.content.map((r) => (
              <tr key={r.id}>
                <td>#{r.membershipId}</td>
                <td>{r.competitorProgramName} / {r.competitorTierName}</td>
                <td>{r.requestedTierCode}</td>
                <td><a href={r.proofUrl} target="_blank" rel="noreferrer">view</a></td>
                <td>{new Date(r.createdAt).toLocaleDateString()}</td>
                <td>
                  <button className="btn-primary" onClick={() => approve(r)}>Approve</button>
                  <button className="danger" onClick={() => reject(r)}>Reject</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

// ── Parity tab (v1 ↔ v2 reconciliation during shadow period) ────────────

function ParityTab() {
  const [summary, setSummary] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    if (!confirm('Run parity now? This is read-only and may take up to a minute.')) return;
    setBusy(true);
    try {
      const s = await loyaltyV2.runParityNow();
      setSummary(s);
      const mismatches = (s.v1Ahead || 0) + (s.v2Ahead || 0);
      toast.success(`Checked ${s.customersChecked} customers — ${mismatches} mismatches, ${s.v2Missing} missing v2, ${s.v1Missing} missing v1`);
    } catch { toast.error('Parity run failed'); }
    finally { setBusy(false); }
  };

  return (
    <div className="admin-card">
      <div className="row-between">
        <h3>v1 ↔ v2 parity audit</h3>
        <button className="btn-primary" disabled={busy} onClick={run}>
          {busy ? 'Running…' : 'Run parity now'}
        </button>
      </div>
      <p style={{ color: 'var(--text-muted, #666)' }}>
        While the program runs in dual-write shadow mode, this job compares v1 and
        v2 wallet balances per customer and emits Micrometer counters
        (<code>loyalty.parity.mismatch</code>, <code>loyalty.parity.missing</code>).
        It runs automatically every night at 04:00 UTC and is gated off once
        v2 becomes primary. Use the button above to trigger an on-demand check.
      </p>
      {summary && (
        <table className="plain-table" style={{ maxWidth: 480 }}>
          <tbody>
            <tr><th>Customers checked</th><td>{summary.customersChecked}</td></tr>
            <tr><th>Matches</th><td>{summary.matches}</td></tr>
            <tr><th>v1 ahead</th><td>{summary.v1Ahead}</td></tr>
            <tr><th>v2 ahead</th><td>{summary.v2Ahead}</td></tr>
            <tr><th>Missing in v2</th><td>{summary.v2Missing}</td></tr>
            <tr><th>Missing in v1</th><td>{summary.v1Missing}</td></tr>
            <tr><th>Total |Δ| points</th><td>{summary.totalDiff}</td></tr>
            <tr><th>Started at</th><td>{String(summary.startedAt)}</td></tr>
            <tr><th>Finished at</th><td>{String(summary.finishedAt)}</td></tr>
          </tbody>
        </table>
      )}
    </div>
  );
}

// ── Playbook tab (operator dos & don'ts) ────────────────────────────────

function PlaybookTab() {
  return (
    <div className="admin-card">
      <h3>Loyalty operator playbook</h3>
      <p>A short reference for super admins running the loyalty program day-to-day.</p>

      <h4>Do</h4>
      <ul>
        <li>Use <strong>effective dates</strong> when changing tier thresholds or earn/redeem rules — never edit a live row in place. The system always inserts a new effective-dated row so historical accruals stay reproducible.</li>
        <li>Communicate <strong>tier changes</strong> at least 30 days in advance. Promotions are immediate; demotions are deferred to the validity window so members are never surprised.</li>
        <li>Review the <strong>Status Match</strong> queue weekly. Approved members get a 90-day challenge — they have to earn the qualifying credits to keep the tier.</li>
        <li>Watch the <strong>Parity</strong> tab during the shadow period. Investigate any mismatches before flipping <code>APP_LOYALTY_V2_PRIMARY=true</code>.</li>
        <li>For per-binge configuration, use <em>Binge Management → Loyalty</em> on the venue card. Earn/redeem rules are venue-scoped; the program-wide settings here apply globally.</li>
      </ul>

      <h4>Don't</h4>
      <ul>
        <li>Don't disable a binge binding to "fix" a calculation bug — that strands accrued points. Pause earning by setting an end-date on the rule instead.</li>
        <li>Don't manually edit wallet balances in the database. Use the adjustment endpoint so a ledger entry is written; otherwise reconciliation breaks.</li>
        <li>Don't thaw a <code>LEGACY_FROZEN</code> binding until you've confirmed v1 has been migrated and reconciled. Use the parity job to verify.</li>
        <li>Don't promise members a tier — promotions are computed from qualifying credits, not assigned. Use Status Match for one-off competitive matches.</li>
        <li>Don't lower <code>pointsExpiryDays</code> without a 90-day grace announcement; the next nightly expiry job will retroactively expire older lots.</li>
      </ul>

      <h4>Cutover checklist (v1 → v2 primary)</h4>
      <ol>
        <li>Run V22 backfill migration in production and verify <code>loyalty_membership</code> count ≈ <code>loyalty_accounts</code> count.</li>
        <li>Run the parity job nightly for at least 7 days with zero mismatches.</li>
        <li>Set <code>APP_LOYALTY_V2_PRIMARY=true</code> in env. v1 earn/expire short-circuits become no-ops; v2 becomes authoritative.</li>
        <li>Monitor the <code>loyalty.v2.*</code> Micrometer counters and customer-support tickets for 30 days.</li>
        <li>Schedule the V23 v1-drop migration after the soak period.</li>
      </ol>
    </div>
  );
}
