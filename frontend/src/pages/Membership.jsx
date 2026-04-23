import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import loyaltyV2 from '../services/loyaltyV2';
import './CustomerHub.css';

/**
 * Membership — the customer-facing Loyalty v2 dashboard.
 *
 * Shows the member's current tier and point balance, a paginated
 * activity ledger (earn / redeem / expire / adjust entries), and a
 * Status-Match submission form that lets a user uploading proof of
 * top-tier status with a competitor program get matched (pending
 * super-admin review).
 *
 * The page is intentionally read-only for tier data — promotions and
 * demotions happen via background engines, so surfacing a "next
 * action" hint would be misleading.  Instead we show the exact
 * `tierEffectiveUntil` date so members understand how long their
 * current tier is guaranteed.
 */
export default function Membership() {
  const [loading, setLoading] = useState(true);
  const [profile, setProfile] = useState(null);
  const [ledger, setLedger] = useState({ content: [], totalPages: 0, number: 0 });
  const [ledgerPage, setLedgerPage] = useState(0);
  const [statusMatches, setStatusMatches] = useState([]);
  const [smForm, setSmForm] = useState({
    competitorProgramName: '',
    competitorTierName: '',
    requestedTierCode: 'GOLD',
    proofUrl: '',
  });
  const [submitting, setSubmitting] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const [me, myLedger, mine] = await Promise.all([
        loyaltyV2.getMyMembership(),
        loyaltyV2.getMyLedger({ page: ledgerPage, size: 25 }),
        loyaltyV2.listMyStatusMatches(),
      ]);
      setProfile(me);
      setLedger(myLedger);
      setStatusMatches(mine || []);
    } catch (e) {
      toast.error('Could not load your membership');
    } finally {
      setLoading(false);
    }
  }, [ledgerPage]);

  useEffect(() => { reload(); }, [reload]);

  const submitStatusMatch = async (e) => {
    e.preventDefault();
    if (!smForm.competitorProgramName || !smForm.proofUrl) {
      toast.warn('Competitor program name and proof URL are required');
      return;
    }
    setSubmitting(true);
    try {
      await loyaltyV2.submitStatusMatch({
        competitorProgramName: smForm.competitorProgramName,
        competitorTierName: smForm.competitorTierName,
        requestedTierCode: smForm.requestedTierCode,
        proofUrl: smForm.proofUrl,
        proofPayloadJson: null,
      });
      toast.success('Status-match request submitted — we review within 48 h');
      setSmForm({ ...smForm, competitorProgramName: '', competitorTierName: '', proofUrl: '' });
      reload();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Could not submit request');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <div className="customer-hub"><SkeletonGrid count={4} /></div>;

  if (profile && profile.enrolled === false) {
    return (
      <div className="customer-hub">
        <SEO title="Membership" description="Join SK Binge Galaxy membership" />
        <div className="info-card">
          <h2>You're not a member yet</h2>
          <p>
            Book a stay with any participating binge and we'll enroll you
            automatically — you'll get a <strong>welcome bonus</strong> on
            your first booking.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="customer-hub">
      <SEO title="Membership" description="Your SK Binge Galaxy membership" />

      {/* ── Snapshot card ─────────────────────────────────────── */}
      <section className="info-card membership-snapshot">
        <h2>
          <span className={`tier-pill tier-${profile.tierCode?.toLowerCase()}`}>
            {profile.tierCode}
          </span>
          Member #{profile.memberNumber}
        </h2>
        <div className="stat-grid">
          <div><dt>Points balance</dt><dd>{(profile.pointsBalance ?? 0).toLocaleString()}</dd></div>
          <div><dt>Lifetime earned</dt><dd>{(profile.pointsEarnedLifetime ?? 0).toLocaleString()}</dd></div>
          <div><dt>Lifetime redeemed</dt><dd>{(profile.pointsRedeemedLifetime ?? 0).toLocaleString()}</dd></div>
          <div>
            <dt>Tier valid until</dt>
            <dd>{profile.tierEffectiveUntil
              ? new Date(profile.tierEffectiveUntil).toLocaleDateString()
              : 'Lifetime'}</dd>
          </div>
          <div>
            <dt>Qualifying credits (year)</dt>
            <dd>{(profile.qualifyingCreditsWindow ?? 0).toLocaleString()}</dd>
          </div>
        </div>
      </section>

      {/* ── Activity ledger ───────────────────────────────────── */}
      <section className="info-card">
        <h2>Activity</h2>
        {(ledger?.content || []).length === 0
          ? <p className="empty-state">No activity yet — book a stay to earn your first points!</p>
          : (
            <table className="plain-table">
              <thead>
                <tr>
                  <th>When</th><th>Type</th><th>Points</th><th>Reason</th><th>Booking</th>
                </tr>
              </thead>
              <tbody>
                {ledger.content.map((e) => (
                  <tr key={e.id}>
                    <td>{new Date(e.createdAt).toLocaleString()}</td>
                    <td>{e.entryType}</td>
                    <td className={e.pointsDelta >= 0 ? 'credit' : 'debit'}>
                      {e.pointsDelta > 0 ? '+' : ''}{e.pointsDelta}
                    </td>
                    <td>{e.reasonCode}</td>
                    <td>{e.bookingRef || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        {ledger.totalPages > 1 && (
          <div className="pager-row">
            <button disabled={ledgerPage === 0} onClick={() => setLedgerPage((p) => p - 1)}>Prev</button>
            <span>Page {ledgerPage + 1} of {ledger.totalPages}</span>
            <button
              disabled={ledgerPage + 1 >= ledger.totalPages}
              onClick={() => setLedgerPage((p) => p + 1)}
            >Next</button>
          </div>
        )}
      </section>

      {/* ── Status Match ──────────────────────────────────────── */}
      <section className="info-card">
        <h2>Status Match</h2>
        <p>
          Already have elite status with another hospitality program?
          Submit proof and we may match your tier for a 90-day challenge
          window — hit the qualifying-credit threshold during the window
          and the tier becomes yours.
        </p>

        <form className="stacked-form" onSubmit={submitStatusMatch}>
          <label>Competitor program name
            <input
              value={smForm.competitorProgramName}
              onChange={(e) => setSmForm({ ...smForm, competitorProgramName: e.target.value })}
              placeholder="e.g. Marriott Bonvoy"
              required
            />
          </label>
          <label>Your tier with them
            <input
              value={smForm.competitorTierName}
              onChange={(e) => setSmForm({ ...smForm, competitorTierName: e.target.value })}
              placeholder="e.g. Platinum Elite"
            />
          </label>
          <label>Tier you're requesting
            <select
              value={smForm.requestedTierCode}
              onChange={(e) => setSmForm({ ...smForm, requestedTierCode: e.target.value })}
            >
              <option value="SILVER">SILVER</option>
              <option value="GOLD">GOLD</option>
              <option value="PLATINUM">PLATINUM</option>
            </select>
          </label>
          <label>Proof URL (screenshot or statement)
            <input
              type="url"
              value={smForm.proofUrl}
              onChange={(e) => setSmForm({ ...smForm, proofUrl: e.target.value })}
              required
            />
          </label>
          <button className="btn-primary" disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit for review'}
          </button>
        </form>

        {statusMatches.length > 0 && (
          <>
            <h3>Your past requests</h3>
            <ul className="list-flush">
              {statusMatches.map((r) => (
                <li key={r.id}>
                  <strong>{r.competitorProgramName}</strong> → {r.requestedTierCode}
                  <span className={`badge badge-${r.status?.toLowerCase()}`}>{r.status}</span>
                  <time>{new Date(r.createdAt).toLocaleDateString()}</time>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </div>
  );
}
