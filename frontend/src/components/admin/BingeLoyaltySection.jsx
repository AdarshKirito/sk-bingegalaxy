import { useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import loyaltyV2 from '../../services/loyaltyV2';

/**
 * Per-binge loyalty configuration panel.
 *
 * Renders inside the BingeManagement modal when an admin clicks
 * "Loyalty" on a venue card.  Surfaces:
 *  - binge-binding status + enable/disable button
 *  - earning-rule list (add new, incl. per-tier multiplier / cap /
 *    min-amount / effective window)
 *  - redemption-rule form (pointsPerCurrencyUnit, minPoints,
 *    maxRedemptionPercent, tier bonus JSON)
 *
 * Safe to open on a binge that has never been wired to loyalty —
 * the controller returns 404 on the binding call; we treat that as
 * "not enabled" and show a prominent Enable button.  Legacy-frozen
 * bindings (dual-write window) are marked read-only with an
 * explanatory banner instead of edit controls.
 */
export default function BingeLoyaltySection({ binge, onClose }) {
  const [binding, setBinding] = useState(null);
  const [earnRules, setEarnRules] = useState([]);
  const [redeemRule, setRedeemRule] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

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
        const [rules, rr] = await Promise.all([
          loyaltyV2.listEarnRules(b.id).catch(() => []),
          loyaltyV2.getRedeemRule(b.id).catch(() => null),
        ]);
        setEarnRules(rules || []);
        setRedeemRule(rr);
        if (rr) setRedeemDraft({ ...emptyRedeem, ...rr });
      } else {
        setEarnRules([]);
        setRedeemRule(null);
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
    if (!window.confirm('Disable loyalty earning & redeeming for this binge? Existing balances are untouched.')) return;
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

  const frozen = binding?.legacyFrozen;

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
            {/* Binding status + enable/disable */}
            <section className="adm-section">
              <h3>Binding</h3>
              {!binding && (
                <>
                  <p>Loyalty is <strong>not enabled</strong> for this binge.</p>
                  <button className="btn btn-primary" disabled={saving} onClick={handleEnable}>
                    Enable loyalty
                  </button>
                </>
              )}
              {binding && (
                <div className="adm-binding-row">
                  <p>
                    Status: <strong>{binding.status}</strong>
                    {frozen && <span style={{ color: '#b45309', marginLeft: 8 }}>
                      (LEGACY_FROZEN — v1 is authoritative during dual-write; thaw from Super Admin)
                    </span>}
                  </p>
                  {!frozen && binding.status !== 'DISABLED' && (
                    <button className="btn btn-danger" disabled={saving} onClick={handleDisable}>
                      Disable loyalty
                    </button>
                  )}
                  {binding.status === 'DISABLED' && (
                    <button className="btn btn-primary" disabled={saving} onClick={handleEnable}>
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
                  <h3>Earning rules</h3>
                  {earnRules.length === 0 && <p style={{ color: '#64748b' }}>No earning rules active. Add one below.</p>}
                  {earnRules.length > 0 && (
                    <table className="adm-table" style={{ fontSize: '0.9rem' }}>
                      <thead>
                        <tr>
                          <th>Tier</th>
                          <th>Points</th>
                          <th>Tier×</th>
                          <th>QC×</th>
                          <th>Min ₹</th>
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

                  <form onSubmit={handleSaveEarnRule} className="adm-form-grid" style={{ marginTop: 16 }}>
                    <h4 style={{ gridColumn: '1 / -1', margin: 0 }}>Add earning rule</h4>
                    <LabeledInput label="Tier (blank = ALL)" value={earnDraft.tierCode}
                      onChange={(v) => setEarnDraft({ ...earnDraft, tierCode: v })} />
                    <LabeledInput label="Points numerator" type="number" value={earnDraft.pointsNumerator}
                      onChange={(v) => setEarnDraft({ ...earnDraft, pointsNumerator: Number(v) })} />
                    <LabeledInput label="Amount denominator (₹)" type="number" step="0.01" value={earnDraft.amountDenominator}
                      onChange={(v) => setEarnDraft({ ...earnDraft, amountDenominator: Number(v) })} />
                    <LabeledInput label="Tier multiplier" type="number" step="0.01" value={earnDraft.tierMultiplier}
                      onChange={(v) => setEarnDraft({ ...earnDraft, tierMultiplier: Number(v) })} />
                    <LabeledInput label="QC multiplier" type="number" step="0.01" value={earnDraft.qcMultiplier}
                      onChange={(v) => setEarnDraft({ ...earnDraft, qcMultiplier: Number(v) })} />
                    <LabeledInput label="Min booking amount" type="number" step="0.01" value={earnDraft.minBookingAmount}
                      onChange={(v) => setEarnDraft({ ...earnDraft, minBookingAmount: v })} />
                    <LabeledInput label="Cap per booking" type="number" value={earnDraft.capPerBooking}
                      onChange={(v) => setEarnDraft({ ...earnDraft, capPerBooking: v })} />
                    <LabeledInput label="Effective from" type="datetime-local" value={earnDraft.effectiveFrom}
                      onChange={(v) => setEarnDraft({ ...earnDraft, effectiveFrom: v })} />
                    <LabeledInput label="Effective to" type="datetime-local" value={earnDraft.effectiveTo}
                      onChange={(v) => setEarnDraft({ ...earnDraft, effectiveTo: v })} />
                    <div style={{ gridColumn: '1 / -1' }}>
                      <button type="submit" className="btn btn-primary" disabled={saving}>Save rule</button>
                    </div>
                  </form>
                </section>

                {/* Redeem rule */}
                <section className="adm-section">
                  <h3>Redemption rule</h3>
                  <form onSubmit={handleSaveRedeemRule} className="adm-form-grid">
                    <LabeledInput label="Points per ₹" type="number" value={redeemDraft.pointsPerCurrencyUnit}
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, pointsPerCurrencyUnit: Number(v) })} />
                    <LabeledInput label="Min redemption points" type="number" value={redeemDraft.minRedemptionPoints}
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, minRedemptionPoints: Number(v) })} />
                    <LabeledInput label="Max redemption % of booking" type="number" step="0.01"
                      value={redeemDraft.maxRedemptionPercent}
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, maxRedemptionPercent: Number(v) })} />
                    <LabeledInput label="Tier bonus JSON" value={redeemDraft.tierBonusPctJson || ''}
                      placeholder='{"GOLD":"5","PLATINUM":"10"}'
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, tierBonusPctJson: v })} />
                    <LabeledInput label="Effective from" type="datetime-local" value={redeemDraft.effectiveFrom || ''}
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, effectiveFrom: v })} />
                    <LabeledInput label="Effective to" type="datetime-local" value={redeemDraft.effectiveTo || ''}
                      onChange={(v) => setRedeemDraft({ ...redeemDraft, effectiveTo: v })} />
                    <div style={{ gridColumn: '1 / -1' }}>
                      <button type="submit" className="btn btn-primary" disabled={saving}>Save redemption rule</button>
                    </div>
                  </form>
                </section>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function LabeledInput({ label, value, onChange, type = 'text', step, placeholder }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', fontSize: '0.85rem', gap: 4 }}>
      <span>{label}</span>
      <input
        type={type}
        step={step}
        value={value ?? ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

function sanitizeEarn(d) {
  return {
    tierCode: d.tierCode?.trim() || null,
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
