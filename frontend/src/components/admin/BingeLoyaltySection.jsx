import { useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { useConfirm } from '../ui/ConfirmProvider';
import { useAuth } from '../../context/AuthContext';
import loyaltyV2 from '../../services/loyaltyV2';
import { currencySymbol, resolveCurrency, formatCurrency } from '../../utils/currency';
import './BingeLoyaltySection.css';

/**
 * Per-binge loyalty configuration panel.
 *
 * Renders inside the BingeManagement modal when an admin clicks
 * "Loyalty" on a venue card.  Surfaces:
 *  - binge-binding status + enable/disable button
 *  - earning-rule list (add new, incl. per-tier multiplier / cap /
 *    min-amount / effective window)
 *  - redemption-rule form (pointsPerCurrencyUnit, minPoints,
 *    maxRedemptionPercent, tier bonus JSON) OR the inherited
 *    venue-country default when no per-binge override is set.
 *
 * Every field carries a "?" help circle explaining what it does.
 *
 * Governance: when a super-admin has LOCKED this binge's loyalty config
 * (binding.adminConfigLocked), regular admins see a read-only banner and
 * cannot enable/disable or edit rules — only a super-admin can.
 *
 * Safe to open on a binge that has never been wired to loyalty —
 * the controller returns 404 on the binding call; we treat that as
 * "not enabled" and show a prominent Enable button.
 */
export default function BingeLoyaltySection({ binge, onClose }) {
  const confirm = useConfirm();
  const { isSuperAdmin } = useAuth();
  // Loyalty rules are denominated in THIS binge's currency (min amounts,
  // points-per-unit), which may differ from the globally selected venue.
  const ccyCode = resolveCurrency(binge);
  const ccy = currencySymbol(ccyCode);
  const oneUnit = formatCurrency(1, ccyCode);
  const [binding, setBinding] = useState(null);
  const [earnRules, setEarnRules] = useState([]);
  const [redeemRule, setRedeemRule] = useState(null);
  const [effective, setEffective] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // A binge admin (not super-admin) is locked out when the super-admin has
  // taken over this venue's loyalty config.
  const readOnly = !!binding?.adminConfigLocked && !isSuperAdmin;

  const emptyEarn = {
    tierCode: '',
    ruleType: 'FLAT_PER_AMOUNT',
    pointsNumerator: 10,
    amountDenominator: 1,
    tierMultiplier: 1.0,
    qcMultiplier: 1.0,
    minBookingAmount: '',
    capPerBooking: '',
    effectiveFrom: '',
    effectiveTo: '',
  };
  const [earnDraft, setEarnDraft] = useState(emptyEarn);

  const emptyRedeem = {
    pointsPerCurrencyUnit: 100,
    minRedemptionPoints: 0,
    maxRedemptionPercent: 100,
    tierBonusPctJson: '',
    effectiveFrom: '',
    effectiveTo: '',
  };
  const [redeemDraft, setRedeemDraft] = useState(emptyRedeem);

  useEffect(() => { if (binge?.id) load(); /* eslint-disable-next-line */ }, [binge?.id]);

  async function load() {
    setLoading(true);
    try {
      const b = await loyaltyV2.getBinding(binge.id).catch(() => null);
      setBinding(b);
      if (b?.id) {
        const [rules, rr, eff] = await Promise.all([
          loyaltyV2.listEarnRules(b.id).catch(() => []),
          loyaltyV2.getRedeemRule(b.id).catch(() => null),
          loyaltyV2.getEffectiveRedeem(b.id).catch(() => null),
        ]);
        setEarnRules(rules || []);
        setRedeemRule(rr);
        setEffective(eff);
        if (rr) setRedeemDraft({ ...emptyRedeem, ...rr });
        else setRedeemDraft(emptyRedeem);
      } else {
        setEarnRules([]);
        setRedeemRule(null);
        setEffective(null);
      }
    } finally { setLoading(false); }
  }

  async function handleEnable() {
    setSaving(true);
    try {
      await loyaltyV2.enableBinding(binge.id);
      toast.success('Loyalty enabled for this binge');
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to enable loyalty');
    } finally { setSaving(false); }
  }

  async function handleDisable() {
    if (!binding?.id) return;
    const ok = await confirm({
      title: 'Disable loyalty for this binge?',
      message: 'Earning and redemption will stop immediately. Existing member balances and transaction history are fully preserved.',
      confirmLabel: 'Disable loyalty',
      variant: 'danger',
    });
    if (!ok) return;
    setSaving(true);
    try {
      await loyaltyV2.disableBinding(binding.id);
      toast.success('Loyalty disabled');
      await load();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Failed to disable loyalty');
    } finally { setSaving(false); }
  }

  async function handleSaveEarnRule(e) {
    e.preventDefault();
    if (!binding?.id) return;
    setSaving(true);
    try {
      await loyaltyV2.upsertEarnRule(binding.id, sanitizeEarn(earnDraft));
      toast.success('Earning rule saved');
      setEarnDraft(emptyEarn);
      await load();
    } catch (e2) {
      toast.error(e2?.response?.data?.message || 'Save failed');
    } finally { setSaving(false); }
  }

  async function handleSaveRedeemRule(e) {
    e.preventDefault();
    if (!binding?.id) return;
    setSaving(true);
    try {
      await loyaltyV2.upsertRedeemRule(binding.id, sanitizeRedeem(redeemDraft));
      toast.success('Redemption rule saved');
      await load();
    } catch (e2) {
      toast.error(e2?.response?.data?.message || 'Save failed');
    } finally { setSaving(false); }
  }

  async function handleResetRedeem() {
    if (!binding?.id) return;
    const ok = await confirm({
      title: 'Reset to the country default?',
      message: `This venue will stop using its custom redemption rate and instead follow its country's live point value set by the super admin. You can set a custom rate again anytime.`,
      confirmLabel: 'Use country default',
      variant: 'primary',
    });
    if (!ok) return;
    setSaving(true);
    try {
      await loyaltyV2.resetRedeemRule(binding.id);
      toast.success('Now inheriting the country default point value');
      await load();
    } catch (e2) {
      toast.error(e2?.response?.data?.message || 'Reset failed');
    } finally { setSaving(false); }
  }

  const frozen = binding?.legacyFrozen;
  const sourceLabel = {
    BINGE_RULE: 'Custom rate for this venue',
    COUNTRY_CONFIG: `Inheriting ${effective?.countryIso2 || 'country'} default`,
    PROGRAM_DEFAULT: 'Using the program default',
  };

  return (
    <div className="adm-modal">
      <div className="adm-modal-content" style={{ maxWidth: 820 }}>
        <div className="adm-modal-header">
          <div>
            <h2>Loyalty — {binge?.name}</h2>
            <p style={{ margin: 0, color: '#64748b' }}>
              Configure how this venue earns &amp; redeems SK Membership points.
            </p>
          </div>
          <button type="button" className="btn btn-secondary" onClick={onClose}>Close</button>
        </div>

        {loading ? <p>Loading…</p> : (
          <>
            {/* Super-admin governance lock */}
            {readOnly && (
              <div className="lyl-lock-banner">
                <span aria-hidden="true">🔒</span>
                <span>
                  <strong>Managed by the super admin.</strong> This venue's loyalty
                  earning &amp; redemption are set centrally. Ask a super admin to
                  change the rules or to unlock self-service for your team.
                </span>
              </div>
            )}
            {binding?.adminConfigLocked && isSuperAdmin && (
              <div className="lyl-lock-banner" style={{ background: '#ede9fe', borderColor: '#8b5cf6', color: '#5b21b6' }}>
                <span aria-hidden="true">🛡️</span>
                <span>
                  <strong>Config lock is ON.</strong> Regular admins for this venue
                  can't change loyalty — only super admins (you) can. Toggle the lock
                  from Loyalty Center → Binges.
                </span>
              </div>
            )}

            {/* Binding status + enable/disable */}
            <section className="adm-section">
              <h3>Binding
                <HelpTip text="A binding connects this venue to the loyalty program. Without an enabled binding, the venue neither earns nor redeems points." />
              </h3>
              {!binding && (
                <>
                  <p>Loyalty is <strong>not enabled</strong> for this binge.</p>
                  <button className="btn btn-primary" disabled={saving || readOnly} onClick={handleEnable}>
                    Enable loyalty
                  </button>
                </>
              )}
              {binding && (
                <div className="adm-binding-row">
                  <p>
                    Status: <strong>{binding.status}</strong>
                    {frozen && <span style={{ color: '#b45309', marginLeft: 8 }}>
                      (legacy snapshot — enable to move this binge onto v2 rules)
                    </span>}
                  </p>
                  {frozen && (
                    <button className="btn btn-primary" disabled={saving || readOnly} onClick={handleEnable}>
                      Enable v2 loyalty
                    </button>
                  )}
                  {!frozen && binding.status !== 'DISABLED' && (
                    <button className="btn btn-danger" disabled={saving || readOnly} onClick={handleDisable}>
                      Disable loyalty
                    </button>
                  )}
                  {binding.status === 'DISABLED' && (
                    <button className="btn btn-primary" disabled={saving || readOnly} onClick={handleEnable}>
                      Re-enable
                    </button>
                  )}
                </div>
              )}
            </section>

            {binding && !frozen && binding.status !== 'DISABLED' && (
              <>
                {/* Earn rules */}
                <section className="adm-section">
                  <h3>Earning rules
                    <HelpTip text="How customers earn points here. Add tier-specific rules for richer earning at higher tiers; a rule with a blank tier applies to everyone." />
                  </h3>
                  {earnRules.length === 0 && <p style={{ color: '#64748b' }}>No earning rules active. Add one below.</p>}
                  {earnRules.length > 0 && (
                    <table className="adm-table" style={{ fontSize: '0.9rem' }}>
                      <thead>
                        <tr>
                          <th>Tier</th>
                          <th>Points</th>
                          <th>Tier×</th>
                          <th>QC×</th>
                          <th>Min {ccy}</th>
                          <th>Cap</th>
                          <th>Window</th>
                        </tr>
                      </thead>
                      <tbody>
                        {earnRules.map((r) => (
                          <tr key={r.id}>
                            <td>{r.tierCode || 'ALL'}</td>
                            <td>{r.pointsNumerator}/{r.amountDenominator}</td>
                            <td>{r.tierMultiplier}</td>
                            <td>{r.qcMultiplier}</td>
                            <td>{r.minBookingAmount || '—'}</td>
                            <td>{r.capPerBooking || '—'}</td>
                            <td>
                              {fmt(r.effectiveFrom)} → {fmt(r.effectiveTo) || 'open'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}

                  {!readOnly && (
                    <form onSubmit={handleSaveEarnRule} className="adm-form-grid" style={{ marginTop: 16 }}>
                      <h4 style={{ gridColumn: '1 / -1', margin: 0 }}>Add earning rule</h4>
                      <LabeledInput label="Tier (blank = ALL)" value={earnDraft.tierCode}
                        help="Leave blank to apply to every member. Enter a tier code (e.g. GOLD) to set a different rate for just that tier."
                        onChange={(v) => setEarnDraft({ ...earnDraft, tierCode: v })} />
                      <LabeledInput label="Points numerator" type="number" value={earnDraft.pointsNumerator}
                        help={`Points earned per the amount below. "10 points per ${oneUnit}" = 10 here, 1 as the amount.`}
                        onChange={(v) => setEarnDraft({ ...earnDraft, pointsNumerator: Number(v) })} />
                      <LabeledInput label={`Amount denominator (${ccy})`} type="number" step="0.01" value={earnDraft.amountDenominator}
                        help={`The spend, in ${ccyCode}, that earns the points above. With 10 points and amount 1, a customer earns 10 points for every ${oneUnit} of the bill.`}
                        onChange={(v) => setEarnDraft({ ...earnDraft, amountDenominator: Number(v) })} />
                      <LabeledInput label="Tier multiplier" type="number" step="0.01" value={earnDraft.tierMultiplier}
                        help="Boosts earning for this rule. 1.5 = members earn 50% more points. Use with a tier code to reward higher tiers."
                        onChange={(v) => setEarnDraft({ ...earnDraft, tierMultiplier: Number(v) })} />
                      <LabeledInput label="QC multiplier" type="number" step="0.01" value={earnDraft.qcMultiplier}
                        help="Multiplies the 'qualifying credits' that drive tier promotions (separate from spendable points). 1.0 = normal pace to the next tier."
                        onChange={(v) => setEarnDraft({ ...earnDraft, qcMultiplier: Number(v) })} />
                      <LabeledInput label="Min booking amount" type="number" step="0.01" value={earnDraft.minBookingAmount}
                        help="Bookings below this amount earn nothing from this rule. Leave blank for no minimum."
                        onChange={(v) => setEarnDraft({ ...earnDraft, minBookingAmount: v })} />
                      <LabeledInput label="Cap per booking" type="number" value={earnDraft.capPerBooking}
                        help="Maximum points a single booking can earn from this rule. Leave blank for no cap — stops one huge booking minting excessive points."
                        onChange={(v) => setEarnDraft({ ...earnDraft, capPerBooking: v })} />
                      <LabeledInput label="Effective from" type="datetime-local" value={earnDraft.effectiveFrom}
                        help="When this rule goes live. Leave blank to start now."
                        onChange={(v) => setEarnDraft({ ...earnDraft, effectiveFrom: v })} />
                      <LabeledInput label="Effective to" type="datetime-local" value={earnDraft.effectiveTo}
                        help="When this rule stops. Leave blank to run until you change it. Saving a new rule closes the old one — history stays auditable."
                        onChange={(v) => setEarnDraft({ ...earnDraft, effectiveTo: v })} />
                      <div style={{ gridColumn: '1 / -1' }}>
                        <button type="submit" className="btn btn-primary" disabled={saving}>Save rule</button>
                      </div>
                    </form>
                  )}
                </section>

                {/* Redeem rule */}
                <section className="adm-section">
                  <h3>Redemption
                    <HelpTip text="How customers spend points for a discount here. If you don't set a custom rate, the venue follows its country's live point value set by the super admin." />
                  </h3>

                  {/* Effective / inherited terms banner */}
                  {effective && (
                    <div className="lyl-inherit-note">
                      <strong>{sourceLabel[effective.source] || 'Current rate'}</strong>
                      <span className="lyl-source-pill">{effective.source}</span>
                      <div style={{ marginTop: 6 }}>
                        {Number(effective.pointsPerCurrencyUnit).toLocaleString()} pts = {oneUnit} off ·
                        {' '}min {Number(effective.minRedemptionPoints).toLocaleString()} pts ·
                        {' '}up to {Number(effective.maxRedemptionPercent)}% of a booking.
                      </div>
                      {effective.source !== 'BINGE_RULE' && (
                        <div style={{ marginTop: 6, color: 'var(--text-muted, #64748b)' }}>
                          This venue has no custom redemption rate, so it tracks its
                          country's value automatically. Set a rate below to override.
                        </div>
                      )}
                      {effective.source === 'BINGE_RULE' && !readOnly && (
                        <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 8 }}
                          onClick={handleResetRedeem} disabled={saving}>
                          Reset to country default
                        </button>
                      )}
                    </div>
                  )}

                  {!readOnly && (
                    <form onSubmit={handleSaveRedeemRule} className="adm-form-grid">
                      <h4 style={{ gridColumn: '1 / -1', margin: 0 }}>
                        {redeemRule ? 'Edit custom redemption rate' : 'Set a custom redemption rate'}
                      </h4>
                      <LabeledInput label={`Points per ${ccy}`} type="number" value={redeemDraft.pointsPerCurrencyUnit}
                        help={`How many points a customer spends for ${oneUnit} off. 100 = they need 100 points to knock ${oneUnit} off the bill. Lower = points are worth more.`}
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, pointsPerCurrencyUnit: Number(v) })} />
                      <LabeledInput label="Min redemption points" type="number" value={redeemDraft.minRedemptionPoints}
                        help="The smallest number of points redeemable on one booking. Blocks trivial redemptions. 0 = no minimum."
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, minRedemptionPoints: Number(v) })} />
                      <LabeledInput label="Max redemption % of booking" type="number" step="0.01"
                        value={redeemDraft.maxRedemptionPercent}
                        help="The most of a single booking's price that points may cover. 50 = points pay at most half; the rest is charged normally."
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, maxRedemptionPercent: Number(v) })} />
                      <LabeledInput label="Tier bonus JSON" value={redeemDraft.tierBonusPctJson || ''}
                        placeholder='{"GOLD":"5","PLATINUM":"10"}'
                        help='Give higher tiers a better rate here. {"GOLD":5,"PLATINUM":10} = Gold points worth 5% more, Platinum 10% more at this venue.'
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, tierBonusPctJson: v })} />
                      <LabeledInput label="Effective from" type="datetime-local" value={redeemDraft.effectiveFrom || ''}
                        help="When this rate goes live. Leave blank to start now."
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, effectiveFrom: v })} />
                      <LabeledInput label="Effective to" type="datetime-local" value={redeemDraft.effectiveTo || ''}
                        help="When this rate stops. Leave blank to run until changed. Saving replaces the current rate; history stays auditable."
                        onChange={(v) => setRedeemDraft({ ...redeemDraft, effectiveTo: v })} />
                      <div style={{ gridColumn: '1 / -1' }}>
                        <button type="submit" className="btn btn-primary" disabled={saving}>Save custom rate</button>
                      </div>
                    </form>
                  )}
                </section>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/** "?" help circle with a hover/focus tooltip. Accessible: the dot is a button
 *  labelled by the text, and the bubble is a tooltip. */
function HelpTip({ text }) {
  const [open, setOpen] = useState(false);
  return (
    <span
      className="lyl-help"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        className="lyl-help-dot"
        aria-label={text}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onClick={(e) => { e.preventDefault(); setOpen((o) => !o); }}
      >?</button>
      {open && <span className="lyl-help-bubble" role="tooltip">{text}</span>}
    </span>
  );
}

function LabeledInput({ label, value, onChange, type = 'text', step, placeholder, help, disabled }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', fontSize: '0.85rem', gap: 4 }}>
      <span style={{ display: 'inline-flex', alignItems: 'center' }}>
        {label}
        {help && <HelpTip text={help} />}
      </span>
      <input
        type={type}
        step={step}
        value={value ?? ''}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

function sanitizeEarn(d) {
  return {
    tierCode: d.tierCode?.trim()?.toUpperCase() || null,
    ruleType: d.ruleType || 'FLAT_PER_AMOUNT',
    pointsNumerator: Number(d.pointsNumerator) || 0,
    amountDenominator: Number(d.amountDenominator) || 1,
    tierMultiplier: Number(d.tierMultiplier) || 1,
    qcMultiplier: Number(d.qcMultiplier) || 1,
    minBookingAmount: d.minBookingAmount ? Number(d.minBookingAmount) : null,
    capPerBooking: d.capPerBooking ? Number(d.capPerBooking) : null,
    effectiveFrom: d.effectiveFrom || null,
    effectiveTo: d.effectiveTo || null,
  };
}

function sanitizeRedeem(d) {
  return {
    pointsPerCurrencyUnit: Number(d.pointsPerCurrencyUnit) || 100,
    minRedemptionPoints: Number(d.minRedemptionPoints) || 0,
    maxRedemptionPercent: Number(d.maxRedemptionPercent) || 100,
    tierBonusPctJson: d.tierBonusPctJson?.trim() || null,
    effectiveFrom: d.effectiveFrom || null,
    effectiveTo: d.effectiveTo || null,
  };
}

function fmt(ts) {
  if (!ts) return '';
  try { return new Date(ts).toLocaleDateString(); } catch { return ts; }
}
