import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import {
  FiAward, FiSettings, FiLayers, FiGift, FiPackage, FiShuffle,
  FiBookOpen, FiRefreshCw, FiPlus, FiCheck, FiX,
  FiAlertTriangle, FiEdit2, FiTrash2, FiExternalLink, FiHelpCircle,
  FiTrendingUp, FiClock, FiDollarSign, FiArrowUp,
  FiSearch, FiUsers, FiUser,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { SkeletonGrid } from '../components/ui/Skeleton';
import loyaltyV2 from '../services/loyaltyV2';
import { parseServerDate } from '../services/timeFormat';
import './AdminSecurity.css';

/**
 * Return a safe href for rendering a user-submitted URL.
 * Only http(s) and relative URLs are permitted; everything else
 * (javascript:, data:, vbscript:, file:, etc.) becomes null so that
 * a malicious proof URL cannot execute when an admin clicks it.
 */
/**
 * Normalize any "list" response into a plain array.
 * The backend can return either an array or a Spring `Page` ({content, totalElements, …}),
 * and our earlier `.map(...)` assumed array unconditionally — which blew up on Page shapes.
 */
function toArray(v) {
  if (Array.isArray(v)) return v;
  if (v && Array.isArray(v.content)) return v.content;
  if (v && Array.isArray(v.items))   return v.items;
  return [];
}

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
 *   6. Guide        — operator reference.
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
    ['members',      'Members',      <FiUsers />],
    ['guide',        'How it works', <FiHelpCircle />],
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
      {tab === 'members'      && <MembersTab />}
      {tab === 'guide'        && <GuideTab />}
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
  const confirm = useConfirm();
  const [tiers, setTiers] = useState([]);
  const [draft, setDraft] = useState(null);

  const reload = useCallback(
    () => loyaltyV2.listTiers()
      .then((res) => setTiers(toArray(res)))
      .catch(() => toast.error('Tier load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const save = async (e) => {
    e.preventDefault();
    try {
      await loyaltyV2.upsertTier(toTierPayload(draft));
      toast.success('Tier saved');
      setDraft(null);
      reload();
    } catch { toast.error('Save failed'); }
  };
  const retire = async (t) => {
    const ok = await confirm({
      title: `Retire tier “${t.code}”?`,
      message: 'Retirement is effective-dated. Prior wallet balances are preserved and members keep their accumulated benefits, but no new members can be assigned to this tier.',
      confirmLabel: 'Retire tier',
      variant: 'danger',
    });
    if (!ok) return;
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
            code: '', displayName: '', rankOrder: tiers.length,
            qualificationCreditsRequired: 0, qualificationWindowDays: 365,
            softLandingTierCode: '', validityCalendarYearsAfter: 1,
            lifetimeCreditsRequired: null, lifetimeYearsHeldRequired: null,
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
                <th>Min credits</th><th>Window</th>
                <th>Soft-land</th><th>Validity (yr)</th><th>Lifetime req.</th><th></th>
              </tr>
            </thead>
            <tbody>
              {tiers.map((t) => (
                <tr key={t.id}>
                  <td>{t.rankOrder}</td>
                  <td><TierChip code={t.code} /></td>
                  <td style={{ fontWeight: 600 }}>{t.displayName}</td>
                  <td>{Number(t.qualificationCreditsRequired || 0).toLocaleString()}</td>
                  <td>{Number(t.qualificationWindowDays || 365).toLocaleString()}d</td>
                  <td>{t.softLandingTierCode ? <TierChip code={t.softLandingTierCode} /> : '—'}</td>
                  <td>{t.validityCalendarYearsAfter ?? '—'}</td>
                  <td>{t.lifetimeCreditsRequired ? <span className="sec-pill success">{Number(t.lifetimeCreditsRequired).toLocaleString()}</span> : '—'}</td>
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
              <input required value={draft.code || ''}
                     onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} />
            </label>
            <label>
              Display name
              <input required value={draft.displayName || ''}
                     onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
            </label>
            <label>
              Rank order
              <input type="number" value={draft.rankOrder ?? 0}
                     onChange={(e) => setDraft({ ...draft, rankOrder: Number(e.target.value) })} />
            </label>
            <label>
              Min qualifying credits
              <input type="number" value={draft.qualificationCreditsRequired ?? 0}
                     onChange={(e) => setDraft({ ...draft, qualificationCreditsRequired: Number(e.target.value) })} />
            </label>
            <label>
              Qualification window (days)
              <input type="number" value={draft.qualificationWindowDays ?? 365}
                     onChange={(e) => setDraft({ ...draft, qualificationWindowDays: Number(e.target.value) })} />
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
              <input
                type="checkbox"
                checked={draft.validityCalendarYearsAfter == null}
                onChange={(e) => setDraft({ ...draft, validityCalendarYearsAfter: e.target.checked ? null : 1 })}
              />
              Permanent tier
            </label>
            <label>
              Lifetime credits required
              <input type="number" value={draft.lifetimeCreditsRequired ?? ''}
                     onChange={(e) => setDraft({ ...draft, lifetimeCreditsRequired: e.target.value === '' ? null : Number(e.target.value) })} />
            </label>
            <label>
              Lifetime years held required
              <input type="number" value={draft.lifetimeYearsHeldRequired ?? ''}
                     onChange={(e) => setDraft({ ...draft, lifetimeYearsHeldRequired: e.target.value === '' ? null : Number(e.target.value) })} />
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
   Perk catalog + tier-perk assignments
   ────────────────────────────────────────────────────────────── */
function PerksTab() {
  const [perks, setPerks]           = useState([]);
  const [tiers, setTiers]           = useState([]);
  const [tierPerks, setTierPerks]   = useState([]);
  const [draft, setDraft]           = useState(null);
  const [assignForm, setAssignForm] = useState({ tierDefinitionId: '', perkId: '', autoGrant: true });
  const [assigning, setAssigning]   = useState(false);

  const reloadPerks = useCallback(
    () => loyaltyV2.listPerks()
      .then((res) => setPerks(toArray(res)))
      .catch(() => toast.error('Perk load failed')),
    [],
  );
  const reloadTierPerks = useCallback(
    () => loyaltyV2.listTierPerks()
      .then((res) => setTierPerks(Array.isArray(res) ? res : []))
      .catch(() => {}),
    [],
  );
  const reload = useCallback(() => {
    reloadPerks();
    reloadTierPerks();
    loyaltyV2.listTiers()
      .then((res) => setTiers(toArray(res)))
      .catch(() => {});
  }, [reloadPerks, reloadTierPerks]);

  useEffect(() => { reload(); }, [reload]);

  const save = async (e) => {
    e.preventDefault();
    try {
      await loyaltyV2.savePerk(toPerkPayload(draft));
      toast.success('Perk saved');
      setDraft(null);
      reloadPerks();
    } catch { toast.error('Save failed'); }
  };

  const handleAssignPerk = async (e) => {
    e.preventDefault();
    if (!assignForm.tierDefinitionId || !assignForm.perkId) {
      toast.warn('Select a tier and a perk');
      return;
    }
    setAssigning(true);
    try {
      await loyaltyV2.assignPerkToTier({
        tierDefinitionId: Number(assignForm.tierDefinitionId),
        perkId: Number(assignForm.perkId),
        autoGrant: assignForm.autoGrant,
        sortOrder: 0,
      });
      toast.success('Perk assigned to tier');
      setAssignForm({ tierDefinitionId: '', perkId: '', autoGrant: true });
      reloadTierPerks();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Assign failed');
    } finally { setAssigning(false); }
  };

  const handleRemoveTierPerk = async (tierPerkId) => {
    try {
      await loyaltyV2.removePerkFromTier(tierPerkId);
      toast.success('Perk removed from tier');
      reloadTierPerks();
    } catch { toast.error('Remove failed'); }
  };

  const HANDLERS = [
    'DISCOUNT_PERCENT_OF_BOOKING', 'FREE_CANCELLATION_EXTENDED', 'BONUS_POINTS_MULTIPLIER',
    'PRIORITY_WAITLIST', 'EARLY_ACCESS_BOOKING_WINDOW', 'BIRTHDAY_BONUS_POINTS',
    'WELCOME_BONUS_POINTS', 'STATUS_EXTENSION_GRANT', 'REWARD_CATALOG_CLAIM',
    'SURPRISE_DELIGHT_BUDGET',
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
          onClick={() => setDraft({
            code: '', displayName: '', description: '', category: 'SOFT',
            fulfillmentType: 'AUTOMATIC', deliveryHandlerKey: '', defaultPointCost: 0,
            cooldownHours: 0, paramsJson: '', active: true,
          })}
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
                  <td><code>{p.code}</code></td>
                  <td style={{ fontWeight: 600 }}>{p.displayName}</td>
                  <td><code style={{ fontSize: 12 }}>{p.deliveryHandlerKey}</code></td>
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
              Perk code
              <input required value={draft.code || ''}
                     onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} />
            </label>
            <label>
              Display name
              <input required value={draft.displayName || ''}
                     onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} />
            </label>
            <label>
              Handler key
              <select value={draft.deliveryHandlerKey || ''}
                      onChange={(e) => setDraft({ ...draft, deliveryHandlerKey: e.target.value })}>
                <option value="">— select —</option>
                {HANDLERS.map((h) => <option key={h} value={h}>{h}</option>)}
              </select>
            </label>
            <label>
              Category
              <select value={draft.category || 'SOFT'}
                      onChange={(e) => setDraft({ ...draft, category: e.target.value })}>
                <option value="FINANCIAL">FINANCIAL</option>
                <option value="SOFT">SOFT</option>
                <option value="INVISIBLE">INVISIBLE</option>
              </select>
            </label>
            <label>
              Fulfillment
              <select value={draft.fulfillmentType || 'AUTOMATIC'}
                      onChange={(e) => setDraft({ ...draft, fulfillmentType: e.target.value })}>
                <option value="AUTOMATIC">AUTOMATIC</option>
                <option value="ON_DEMAND">ON_DEMAND</option>
                <option value="MANUAL">MANUAL</option>
              </select>
            </label>
            <label>
              Default point cost
              <input type="number" value={draft.defaultPointCost ?? 0}
                     onChange={(e) => setDraft({ ...draft, defaultPointCost: Number(e.target.value) })} />
            </label>
            <label>
              Cooldown hours
              <input type="number" value={draft.cooldownHours ?? 0}
                     onChange={(e) => setDraft({ ...draft, cooldownHours: Number(e.target.value) })} />
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

      {/* ── Tier-perk assignments ─────────────────────────────── */}
      <div style={{ marginTop: 32, borderTop: '1px solid var(--border)', paddingTop: 24 }}>
        <div className="sec-card-head">
          <div>
            <h2>Tier-perk assignments</h2>
            <p>Which perks each tier receives. Auto-grant perks apply automatically; on-demand perks require a member claim.</p>
          </div>
        </div>

        {tiers.length === 0 ? (
          <div className="sec-empty" style={{ padding: '20px 0' }}>
            <p>No tiers configured — add tiers first.</p>
          </div>
        ) : (
          <div className="sec-tier-perk-grid">
            {tiers.map((tier) => {
              const assigned = tierPerks.filter((tp) => tp.tierDefinitionId === tier.id);
              return (
                <div key={tier.id} className="sec-tier-perk-row">
                  <div className="sec-tier-perk-label">
                    <TierChip code={tier.code} />
                    <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{tier.displayName}</span>
                  </div>
                  <div className="sec-tier-perk-chips">
                    {assigned.length === 0 ? (
                      <span style={{ fontSize: 13, color: 'var(--text-muted)', fontStyle: 'italic' }}>No perks assigned</span>
                    ) : (
                      assigned.map((tp) => {
                        const perk = perks.find((p) => p.id === tp.perkId);
                        return (
                          <div key={tp.id} className="sec-perk-chip">
                            <span>{perk?.displayName || `Perk #${tp.perkId}`}</span>
                            {tp.autoGrant && (
                              <span className="sec-pill success" style={{ fontSize: 10, padding: '1px 6px' }}>auto</span>
                            )}
                            <button
                              className="sec-perk-chip-remove"
                              title="Remove from tier"
                              onClick={() => handleRemoveTierPerk(tp.id)}
                            >
                              <FiX size={11} />
                            </button>
                          </div>
                        );
                      })
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        <form className="sec-form sec-tier-perk-assign-form" onSubmit={handleAssignPerk}
              style={{ marginTop: 20 }}>
          <h4 style={{ margin: '0 0 12px', fontSize: 14 }}>Assign a perk to a tier</h4>
          <label>
            Tier
            <select value={assignForm.tierDefinitionId}
                    onChange={(e) => setAssignForm({ ...assignForm, tierDefinitionId: e.target.value })}>
              <option value="">— select tier —</option>
              {tiers.map((t) => (
                <option key={t.id} value={t.id}>{t.displayName} ({t.code})</option>
              ))}
            </select>
          </label>
          <label>
            Perk
            <select value={assignForm.perkId}
                    onChange={(e) => setAssignForm({ ...assignForm, perkId: e.target.value })}>
              <option value="">— select perk —</option>
              {perks.filter((p) => p.active).map((p) => (
                <option key={p.id} value={p.id}>{p.displayName}</option>
              ))}
            </select>
          </label>
          <label className="sec-checkbox">
            <input type="checkbox" checked={assignForm.autoGrant}
                   onChange={(e) => setAssignForm({ ...assignForm, autoGrant: e.target.checked })} />
            Auto-grant (applied automatically — no member action needed)
          </label>
          <div className="sec-form-actions">
            <button className="sec-btn sec-btn-primary" disabled={assigning}>
              {assigning ? 'Assigning…' : <><FiPlus /> Assign to tier</>}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Binge bindings (bulk enable / disable)
   ────────────────────────────────────────────────────────────── */
function BindingsTab() {
  const confirm = useConfirm();
  const [bindings, setBindings] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [busy, setBusy] = useState(false);

  const reload = useCallback(
    () => loyaltyV2.listBindings()
      .then((res) => { setBindings(toArray(res)); setSelected(new Set()); })
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
    const eligible = bindings;
    if (selected.size === eligible.length) setSelected(new Set());
    else setSelected(new Set(eligible.map((b) => b.id)));
  };

  const bulkDo = async (status) => {
    if (selected.size === 0) { toast.info('Select at least one binge'); return; }
    const enabling = status === 'ENABLED';
    const ok = await confirm({
      title: `${enabling ? 'Enable' : 'Disable'} loyalty for ${selected.size} binge${selected.size === 1 ? '' : 's'}?`,
      message: enabling
        ? 'Members at the selected binges will start accruing points and qualifying for tiers immediately.'
        : 'New accruals at the selected binges will stop. Existing balances and tiers are preserved.',
      confirmLabel: enabling ? 'Enable loyalty' : 'Disable loyalty',
      variant: enabling ? 'primary' : 'danger',
    });
    if (!ok) return;
    setBusy(true);
    try {
      const touched = await loyaltyV2.bulkSetBindingStatus(Array.from(selected), status);
      toast.success(`${touched} binding(s) updated`);
      reload();
    } catch { toast.error('Bulk action failed'); }
    finally { setBusy(false); }
  };

  const eligible = bindings;
  const allChecked = eligible.length > 0 && selected.size === eligible.length;

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Binge bindings <span style={{ color: 'var(--text-muted)', fontWeight: 500 }}>({bindings.length})</span></h2>
          <p>Bulk-toggle loyalty participation per binge. Legacy rows are thawed automatically when enabled.</p>
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
  const confirm = useConfirm();
  const [pending, setPending] = useState({ content: [], totalElements: 0 });

  const reload = useCallback(
    () => loyaltyV2.listPendingStatusMatches({ size: 50 }).then(setPending)
        .catch(() => toast.error('Load failed')),
    [],
  );
  useEffect(() => { reload(); }, [reload]);

  const approve = async (r) => {
    const result = await confirm({
      title: 'Approve status-match request?',
      message: 'A 90-day challenge will start. The member must meet the matched-tier requirements during the challenge window or they revert to their earned tier.',
      confirmLabel: 'Approve & start challenge',
      variant: 'primary',
      withReason: true,
      reasonRequired: false,
      reasonLabel: 'Approval notes (optional)',
      reasonPlaceholder: 'Add an optional note for the audit log…',
    });
    if (!result) return;
    const notes = result.reason || '';
    try {
      await loyaltyV2.approveStatusMatch(r.id, { notes, challengeDays: 90 });
      toast.success('Approved — 90-day challenge started');
      reload();
    } catch { toast.error('Approve failed'); }
  };
  const reject = async (r) => {
    const result = await confirm({
      title: 'Reject status-match request?',
      message: 'A reason is required and will be visible to the member and in the audit log.',
      confirmLabel: 'Reject request',
      variant: 'danger',
      withReason: true,
      reasonRequired: true,
      reasonLabel: 'Reason for rejection',
      reasonPlaceholder: 'Explain why this request is being rejected…',
    });
    if (!result) return;
    const notes = result.reason;
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
                  <td>{parseServerDate(r.createdAt)?.toLocaleDateString() || ''}</td>
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
   Guide — "How the loyalty program works" reference
   ────────────────────────────────────────────────────────────── */
function GuideTab() {
  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>How the loyalty program works</h2>
          <p>
            A single source of truth for how customers earn, climb tiers, redeem, and
            keep their status — share this with new admins or support staff.
          </p>
        </div>
      </div>

      {/* Tier ladder at a glance */}
      <h3 style={{ margin: '6px 0 12px', fontSize: 16 }}>Tier ladder</h3>
      <div className="sec-table-wrap">
        <table className="sec-table">
          <thead>
            <tr>
              <th>Tier</th>
              <th>Minimum qualifying credits / year</th>
              <th>Earn multiplier</th>
              <th>Signature perks</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><span className="sec-tier-chip sec-tier-BRONZE">BRONZE</span></td>
              <td>0</td><td>1.00×</td>
              <td>Base earn rate, birthday surprise.</td>
            </tr>
            <tr>
              <td><span className="sec-tier-chip sec-tier-SILVER">SILVER</span></td>
              <td>5,000</td><td>1.25×</td>
              <td>Welcome refreshment, 24 h priority booking window.</td>
            </tr>
            <tr>
              <td><span className="sec-tier-chip sec-tier-GOLD">GOLD</span></td>
              <td>20,000</td><td>1.50×</td>
              <td>Late checkout, priority support, flex-cancel.</td>
            </tr>
            <tr>
              <td><span className="sec-tier-chip sec-tier-PLATINUM">PLATINUM</span></td>
              <td>50,000</td><td>2.00×</td>
              <td>Room upgrade, annual choice gift, dedicated concierge.</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="sec-playbook-grid" style={{ marginTop: 20 }}>
        <div className="sec-playbook-card">
          <h3><FiTrendingUp /> Earning &amp; climbing</h3>
          <ul>
            <li>Every booking earns <strong>points</strong> (default 10 pts per ₹1) and <strong>qualifying credits</strong> from the active earn rule.</li>
            <li>Points are the spendable currency; qualifying credits drive tier promotions.</li>
            <li>Per-binge rules can add tier or campaign multipliers while preserving an auditable base earn rate.</li>
            <li>We look at a rolling <strong>12-month qualifying window</strong>. When a customer crosses a threshold, the new tier activates immediately.</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiDollarSign /> Redeeming points</h3>
          <ul>
            <li>Points are redeemed at booking checkout via the <code>redeem-quote</code> endpoint.</li>
            <li>Default rate: <strong>100 points = ₹1</strong> off (configurable per binge via Redeem rules).</li>
            <li>Redemptions deduct FIFO from the oldest earn lot, so nothing expires under-water.</li>
            <li>Partial redemptions are allowed; caps can be set per binge (e.g. max 50% of bill).</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiClock /> Expiry &amp; validity</h3>
          <ul>
            <li>Points expire <strong>{`{pointsExpiryDays}`} days</strong> after earning (default 540). See <em>Program → Points expiry</em>.</li>
            <li>Tier status is valid for the <strong>validityCalendarYearsAfter</strong> period of each tier (e.g. Gold: current year + 1).</li>
            <li>If a customer doesn't re-qualify, they soft-land to the <strong>softLandingTierCode</strong> — a graceful demote instead of dropping to Bronze.</li>
            <li>Lifetime tiers such as Lifetime Platinum never expire.</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiArrowUp /> Status Match</h3>
          <ul>
            <li>Members submit proof of elite status in a competing program through <em>Membership → Status Match</em>.</li>
            <li>Super admins review each request in the <strong>Status Match</strong> tab.</li>
            <li>Approval kicks off a <strong>90-day challenge</strong> — the member must earn the tier's qualifying credits inside the window to keep it.</li>
            <li>Auto-rejects are never issued; every decision is manual and audited.</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiPackage /> Binges &amp; bindings</h3>
          <ul>
            <li>A <strong>binding</strong> connects a binge (venue) to the loyalty program. Without one, the binge doesn't earn or redeem points.</li>
            <li>Enable / disable in bulk from the <strong>Binges</strong> tab.</li>
            <li>Per-binge earn and redeem rules are configured under <em>Binge Management → Loyalty</em>.</li>
            <li>Legacy bindings are automatically thawed after cutover; disabled bindings preserve existing balances.</li>
          </ul>
        </div>

      </div>

      <div className="mfa-warning" style={{ marginTop: 20 }}>
        <FiAlertTriangle size={18} />
        <span>
          Admins and support agents: <strong>never quote exact future tier dates to members</strong>.
          Tier changes happen when qualifying credits cross a threshold; we can describe the
          mechanism but not commit to a specific future promotion date — that's the engine's job.
        </span>
      </div>
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
            <li>Review ledger activity after rule changes and keep earn/redeem rules effective-dated.</li>
            <li>For per-binge configuration, use <em>Binge Management → Loyalty</em> on the venue card.</li>
          </ul>
        </div>

        <div className="sec-playbook-card dont">
          <h3><FiX /> Don't</h3>
          <ul>
            <li>Don't disable a binge binding to "fix" a calculation bug — that strands accrued points.</li>
            <li>Don't manually edit wallet balances in the database; reconciliation breaks without a ledger entry.</li>
            <li>Don't bypass the wallet service; every balance change needs a ledger entry.</li>
            <li>Don't promise members a tier — promotions are computed from qualifying credits.</li>
            <li>Don't lower <code>pointsExpiryDays</code> without a 90-day grace announcement.</li>
          </ul>
        </div>

        <div className="sec-playbook-card">
          <h3><FiBookOpen /> Production checks</h3>
          <ol>
            <li>Verify every enabled binge has one active earn rule and one active redemption rule.</li>
            <li>Watch wallet balance, lot remaining, and ledger delta totals during support audits.</li>
            <li>Keep disabled bindings disabled instead of deleting them; balances and history remain intact.</li>
            <li>Monitor <code>loyalty.v2.*</code> Micrometer counters and customer-support tickets after edits.</li>
          </ol>
        </div>
      </div>
    </div>
  );
}

/* ──────────────────────────────────────────────────────────────
   Members — customer lookup, wallet snapshot, ledger, adjustments
   ────────────────────────────────────────────────────────────── */
function MembersTab() {
  const [inputId, setInputId]     = useState('');
  const [searching, setSearching] = useState(false);
  const [profile, setProfile]     = useState(null);
  const [ledger, setLedger]       = useState(null);
  const [ledgerPage, setLedgerPage] = useState(0);
  const [adjustPts, setAdjustPts] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjusting, setAdjusting] = useState(false);

  const activeId = profile ? parseInt(inputId, 10) : null;

  const loadLedger = useCallback(async (cid, pg) => {
    try {
      const l = await loyaltyV2.getCustomerLedger(cid, { page: pg, size: 25 });
      setLedger(l && Array.isArray(l.content) ? l : { content: [], totalPages: 0, number: 0 });
      setLedgerPage(pg);
    } catch { toast.error('Could not load ledger'); }
  }, []);

  const search = async (e) => {
    e.preventDefault();
    const id = parseInt(inputId, 10);
    if (!id || id <= 0) { toast.warn('Enter a valid numeric customer ID'); return; }
    setSearching(true);
    setProfile(null);
    setLedger(null);
    try {
      const p = await loyaltyV2.getCustomerAccount(id);
      setProfile(p);
      await loadLedger(id, 0);
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Customer not found or not enrolled');
    } finally { setSearching(false); }
  };

  const doAdjust = async (e) => {
    e.preventDefault();
    const pts = parseInt(adjustPts, 10);
    if (!pts) { toast.warn('Enter a non-zero point amount'); return; }
    setAdjusting(true);
    try {
      const updated = await loyaltyV2.adjustCustomerPoints(activeId, {
        points: pts,
        description: adjustReason || undefined,
      });
      setProfile(updated);
      setAdjustPts('');
      setAdjustReason('');
      toast.success(`Points adjusted: ${pts > 0 ? '+' : ''}${pts.toLocaleString()}`);
      await loadLedger(activeId, 0);
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Adjustment failed');
    } finally { setAdjusting(false); }
  };

  return (
    <div className="sec-card">
      <div className="sec-card-head">
        <div>
          <h2>Member lookup</h2>
          <p>
            Search any customer by their numeric ID to inspect their wallet, full ledger history,
            and apply manual point adjustments. Every adjustment writes an auditable ledger entry.
          </p>
        </div>
      </div>

      <form className="sec-member-search-form" onSubmit={search}>
        <label style={{ flex: 1 }}>
          Customer ID
          <input
            type="number"
            min="1"
            required
            value={inputId}
            onChange={(e) => setInputId(e.target.value)}
            placeholder="e.g. 42"
          />
        </label>
        <button className="sec-btn sec-btn-primary" disabled={searching} style={{ alignSelf: 'flex-end' }}>
          {searching ? 'Searching…' : <><FiSearch /> Look up</>}
        </button>
      </form>

      {profile && (
        <>
          {/* ── Wallet snapshot ─────────────────────────────── */}
          <div className="sec-member-card">
            <div className="sec-member-card-header">
              <FiUser size={16} />
              <span className="sec-member-number">Member #{profile.memberNumber || '—'}</span>
              <TierChip code={profile.tierCode} />
              {profile.tierEffectiveUntil ? (
                <span className="sec-member-validity">
                  <FiClock size={12} style={{ verticalAlign: '-1px' }} />
                  Tier valid until {new Date(profile.tierEffectiveUntil).toLocaleDateString()}
                </span>
              ) : profile.tierCode && (
                <span className="sec-member-validity">Lifetime tier</span>
              )}
            </div>
            <div className="sec-member-stats">
              <div className="sec-member-stat">
                <span>{Number(profile.pointsBalance ?? 0).toLocaleString()}</span>
                <small>Balance</small>
              </div>
              <div className="sec-member-stat">
                <span>{Number(profile.pointsEarnedLifetime ?? 0).toLocaleString()}</span>
                <small>Lifetime earned</small>
              </div>
              <div className="sec-member-stat">
                <span>{Number(profile.pointsRedeemedLifetime ?? 0).toLocaleString()}</span>
                <small>Lifetime redeemed</small>
              </div>
              <div className="sec-member-stat">
                <span>{Number(profile.qualifyingCreditsWindow ?? 0).toLocaleString()}</span>
                <small>QC window (12 mo)</small>
              </div>
              <div className="sec-member-stat">
                <span>{Number(profile.lifetimeCredits ?? 0).toLocaleString()}</span>
                <small>Lifetime credits</small>
              </div>
            </div>
          </div>

          {/* ── Manual points adjustment ─────────────────────── */}
          <div className="sec-adjustment-panel">
            <h4>Manual points adjustment</h4>
            <p>Positive to credit, negative to debit. Required for goodwill credits, support resolutions, and corrections.</p>
            <form className="sec-form" onSubmit={doAdjust}>
              <label>
                Points (+credit / −debit)
                <input
                  type="number"
                  value={adjustPts}
                  onChange={(e) => setAdjustPts(e.target.value)}
                  placeholder="e.g. 500 or -100"
                  required
                />
              </label>
              <label>
                Reason (audit log)
                <input
                  value={adjustReason}
                  onChange={(e) => setAdjustReason(e.target.value)}
                  placeholder="e.g. Goodwill credit — support ticket #1234"
                />
              </label>
              <div className="sec-form-actions">
                <button className="sec-btn sec-btn-primary" disabled={adjusting}>
                  {adjusting ? 'Applying…' : <><FiCheck /> Apply adjustment</>}
                </button>
              </div>
            </form>
          </div>

          {/* ── Ledger ───────────────────────────────────────── */}
          {ledger && (
            <div className="sec-member-ledger">
              <h4>Point history</h4>
              {ledger.content.length === 0 ? (
                <div className="sec-empty" style={{ padding: '20px 0' }}>
                  <p>No ledger entries yet.</p>
                </div>
              ) : (
                <>
                  <div className="sec-table-wrap">
                    <table className="sec-table">
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th>Activity</th>
                          <th>Points</th>
                          <th>Booking</th>
                          <th>Note</th>
                        </tr>
                      </thead>
                      <tbody>
                        {ledger.content.map((entry) => {
                          const isCredit = entry.pointsDelta > 0;
                          const isDebit  = entry.pointsDelta < 0;
                          const color    = isCredit ? '#15803d' : isDebit ? '#dc2626' : 'var(--text-muted)';
                          return (
                            <tr key={entry.id}>
                              <td style={{ whiteSpace: 'nowrap', fontSize: 12, color: 'var(--text-muted)' }}>
                                {parseServerDate(entry.createdAt)?.toLocaleDateString() || ''}
                              </td>
                              <td>
                                <span style={{ fontSize: 13, fontWeight: 600 }}>
                                  {humanAdminReason(entry.reasonCode, entry.entryType)}
                                </span>
                              </td>
                              <td>
                                <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color }}>
                                  {isCredit ? '+' : ''}{Number(entry.pointsDelta).toLocaleString()}
                                </span>
                              </td>
                              <td>
                                {entry.bookingRef
                                  ? <code style={{ fontSize: 12 }}>{entry.bookingRef}</code>
                                  : <span style={{ color: 'var(--text-muted)' }}>—</span>}
                              </td>
                              <td style={{ fontSize: 12, color: 'var(--text-muted)', maxWidth: 240 }}>
                                {entry.description || '—'}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                  {ledger.totalPages > 1 && (
                    <div className="sec-pager">
                      <button className="sec-btn" disabled={ledgerPage === 0}
                              onClick={() => loadLedger(activeId, ledgerPage - 1)}>← Prev</button>
                      <span>Page {ledgerPage + 1} of {ledger.totalPages}</span>
                      <button className="sec-btn" disabled={ledgerPage + 1 >= ledger.totalPages}
                              onClick={() => loadLedger(activeId, ledgerPage + 1)}>Next →</button>
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function humanAdminReason(code, type) {
  if (!code) return type || 'Activity';
  const map = {
    EARN_BOOKING: 'Earned on booking', BOOKING_COMPLETED: 'Earned on booking',
    EARN_BONUS: 'Bonus points', EARN_WELCOME: 'Welcome bonus', WELCOME_BONUS: 'Welcome bonus',
    EARN_BIRTHDAY: 'Birthday bonus', REDEEM_BOOKING: 'Redeemed on booking',
    BOOKING_REDEMPTION: 'Redeemed on booking', REDEEM_REWARD: 'Redeemed reward',
    EXPIRE: 'Points expired', LOT_EXPIRED: 'Points expired',
    ADJUST_ADMIN: 'Admin adjustment', ADMIN_ADJUSTMENT: 'Admin adjustment',
    STATUS_MATCH: 'Status match bonus', GUEST_MERGE: 'Guest activity credited',
    REVERSE_REDEEM: 'Redemption refunded', CANCELLATION: 'Booking cancellation',
  };
  return map[code] || code.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

/* ──────────────────────────────────────────────────────────────
   Shared: tier chip
   ────────────────────────────────────────────────────────────── */
function toPerkPayload(draft) {
  return {
    ...draft,
    code: String(draft.code || '').trim().toUpperCase(),
    category: draft.category || 'SOFT',
    fulfillmentType: draft.fulfillmentType || 'AUTOMATIC',
    deliveryHandlerKey: String(draft.deliveryHandlerKey || '').trim(),
    defaultPointCost: Number(draft.defaultPointCost ?? 0),
    cooldownHours: Number(draft.cooldownHours ?? 0),
    paramsJson: draft.paramsJson?.trim() || null,
  };
}

function toTierPayload(draft) {
  return {
    ...draft,
    code: String(draft.code || '').trim().toUpperCase(),
    rankOrder: Number(draft.rankOrder ?? 0),
    qualificationCreditsRequired: Number(draft.qualificationCreditsRequired ?? 0),
    qualificationWindowDays: Number(draft.qualificationWindowDays ?? 365),
    softLandingTierCode: draft.softLandingTierCode?.trim()?.toUpperCase() || null,
    validityCalendarYearsAfter:
      draft.validityCalendarYearsAfter === '' ? null : draft.validityCalendarYearsAfter,
    lifetimeCreditsRequired:
      draft.lifetimeCreditsRequired === '' ? null : draft.lifetimeCreditsRequired,
    lifetimeYearsHeldRequired:
      draft.lifetimeYearsHeldRequired === '' ? null : draft.lifetimeYearsHeldRequired,
  };
}

function TierChip({ code }) {
  const c = String(code || '').toUpperCase();
  if (!c) return <span style={{ color: 'var(--text-muted)' }}>—</span>;
  return <span className={`sec-tier-chip sec-tier-${c}`}>{c}</span>;
}
