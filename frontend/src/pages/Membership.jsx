import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'react-toastify';
import {
  FiAward, FiTrendingUp, FiStar, FiGift, FiShield, FiClock,
  FiArrowDown, FiArrowUp, FiExternalLink, FiRefreshCw,
  FiZap, FiCoffee, FiHeadphones, FiCalendar,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import loyaltyV2 from '../services/loyaltyV2';
import './Membership.css';

/**
 * Membership — customer-facing Loyalty v2 dashboard.
 *
 * Renders a premium tier "wallet card" hero, key stat tiles, a tier-benefits
 * grid, a timeline-style activity ledger, and a Status-Match submission form
 * with request history. Tier progression is estimated client-side using
 * standard qualifying-credit thresholds — the server endpoint doesn't expose
 * a pointsToNextTier field on this snapshot, so we derive it here.
 */

const TIER_ORDER = ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'DIAMOND'];
const TIER_THRESHOLDS = {
  BRONZE: 0,
  SILVER: 5_000,
  GOLD: 15_000,
  PLATINUM: 35_000,
  DIAMOND: 75_000,
};

const TIER_BENEFITS = {
  BRONZE: [
    { icon: <FiStar />,   title: '1x points per ₹100', body: 'Base earn rate on every booking.' },
    { icon: <FiGift />,   title: 'Birthday surprise',  body: 'A small gift in your booking month.' },
  ],
  SILVER: [
    { icon: <FiStar />,       title: '1.25x points',        body: '25% bonus points on every booking.' },
    { icon: <FiCoffee />,     title: 'Welcome refreshments',body: 'Complimentary beverage on arrival.' },
    { icon: <FiCalendar />,   title: 'Priority booking',    body: 'Access dates 24h before general release.' },
  ],
  GOLD: [
    { icon: <FiStar />,       title: '1.5x points',         body: '50% bonus points on every booking.' },
    { icon: <FiZap />,        title: 'Late checkout',       body: '2-hour complimentary extension when available.' },
    { icon: <FiHeadphones />, title: 'Priority support',    body: 'Skip the queue with gold-line WhatsApp.' },
    { icon: <FiShield />,     title: 'Flex-cancel',         body: 'Reduced cancellation fees within policy.' },
  ],
  PLATINUM: [
    { icon: <FiStar />,       title: '2x points',           body: 'Double points on every booking.' },
    { icon: <FiGift />,       title: 'Room upgrade',        body: 'Complimentary upgrade subject to availability.' },
    { icon: <FiAward />,      title: 'Annual choice gift',  body: 'Pick one perk each anniversary year.' },
    { icon: <FiHeadphones />, title: 'Dedicated concierge', body: 'A named contact for planning any event.' },
  ],
  DIAMOND: [
    { icon: <FiStar />,       title: '3x points',           body: 'Top earn rate — triple points everywhere.' },
    { icon: <FiAward />,      title: 'Guaranteed upgrade',  body: 'Pre-confirmed upgrade on every stay.' },
    { icon: <FiGift />,       title: 'Invite-only events',  body: 'Access exclusive tastings & private screenings.' },
    { icon: <FiShield />,     title: 'Waived fees',         body: 'No cancellation fees inside the flex window.' },
  ],
};

export default function Membership() {
  const [loading, setLoading]           = useState(true);
  const [refreshing, setRefreshing]     = useState(false);
  const [profile, setProfile]           = useState(null);
  const [ledger, setLedger]             = useState({ content: [], totalPages: 0, number: 0 });
  const [ledgerPage, setLedgerPage]     = useState(0);
  const [statusMatches, setStatusMatches] = useState([]);
  const [smForm, setSmForm] = useState({
    competitorProgramName: '',
    competitorTierName: '',
    requestedTierCode: 'GOLD',
    proofUrl: '',
  });
  const [submitting, setSubmitting] = useState(false);

  const reload = useCallback(async ({ silent = false } = {}) => {
    if (silent) setRefreshing(true); else setLoading(true);
    try {
      const me = await loyaltyV2.getMyMembership();
      setProfile(me || { enrolled: false });
      if (me && me.enrolled !== false) {
        const [myLedger, mine] = await Promise.all([
          loyaltyV2.getMyLedger({ page: ledgerPage, size: 25 }).catch(() => null),
          loyaltyV2.listMyStatusMatches().catch(() => []),
        ]);
        setLedger(
          myLedger && Array.isArray(myLedger.content)
            ? myLedger
            : { content: [], totalPages: 0, number: 0 }
        );
        setStatusMatches(Array.isArray(mine) ? mine : []);
      } else {
        setLedger({ content: [], totalPages: 0, number: 0 });
        setStatusMatches([]);
      }
    } catch {
      toast.error('Could not load your membership');
      setProfile({ enrolled: false });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [ledgerPage]);

  useEffect(() => { reload(); }, [reload]);

  const tierKey = (profile?.tierCode || 'BRONZE').toUpperCase();
  const idx = Math.max(0, TIER_ORDER.indexOf(tierKey));
  const nextTier = idx < TIER_ORDER.length - 1 ? TIER_ORDER[idx + 1] : null;
  const qc = Number(profile?.qualifyingCreditsWindow || 0);
  const nextThreshold = nextTier ? TIER_THRESHOLDS[nextTier] : TIER_THRESHOLDS[tierKey];
  const prevThreshold = TIER_THRESHOLDS[tierKey] || 0;
  const progressPct = useMemo(() => {
    if (!nextTier) return 100;
    const span = Math.max(1, nextThreshold - prevThreshold);
    return Math.min(100, Math.max(0, ((qc - prevThreshold) / span) * 100));
  }, [qc, nextTier, nextThreshold, prevThreshold]);

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
      toast.success('Status-match request submitted — we review within 48 hours');
      setSmForm({ ...smForm, competitorProgramName: '', competitorTierName: '', proofUrl: '' });
      reload({ silent: true });
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Could not submit request');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="membership-page">
        <SkeletonGrid count={5} columns={1} />
      </div>
    );
  }

  if (!profile || profile.enrolled === false) {
    return (
      <div className="membership-page">
        <SEO title="Membership" description="Join SK Binge Galaxy membership" />
        <div className="join-panel">
          <FiAward style={{ fontSize: 42 }} />
          <h2>Join the SK Binge Galaxy Circle</h2>
          <p>
            Members earn points on every booking, unlock tier-based perks, and get
            priority access to experiences. Enrollment is automatic on your first
            booking — and comes with a welcome bonus.
          </p>
          <div className="join-perks">
            <div className="join-perk"><strong>Earn on every stay</strong><span>Base 1x, up to 3x at Diamond.</span></div>
            <div className="join-perk"><strong>Priority access</strong><span>See dates before anyone else.</span></div>
            <div className="join-perk"><strong>Milestone gifts</strong><span>Annual bonuses, birthday surprises.</span></div>
            <div className="join-perk"><strong>Status Match</strong><span>Already elite elsewhere? Bring it over.</span></div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="membership-page">
      <SEO title="Membership" description="Your SK Binge Galaxy membership" />

      {/* ── Hero wallet card ─────────────────────────────────── */}
      <section className={`membership-hero tier-${tierKey.toLowerCase()}`}>
        <div className="membership-hero-top">
          <div>
            <div className="membership-hero-kicker">SK Binge Galaxy Member</div>
            <h1>{tierKey.charAt(0) + tierKey.slice(1).toLowerCase()} Tier</h1>
            <div className="membership-hero-member">#{profile.memberNumber}</div>
          </div>
          <div className="membership-hero-balance">
            <span className="points">{(profile.pointsBalance ?? 0).toLocaleString()}</span>
            <span className="label">Points balance</span>
          </div>
        </div>

        <div className="membership-progress">
          {nextTier ? (
            <>
              <div className="membership-progress-track">
                <div className="membership-progress-fill" style={{ width: `${progressPct}%` }} />
              </div>
              <div className="membership-progress-meta">
                <span>{qc.toLocaleString()} / {nextThreshold.toLocaleString()} qualifying credits</span>
                <strong>{Math.max(0, nextThreshold - qc).toLocaleString()} to {nextTier}</strong>
              </div>
            </>
          ) : (
            <div className="membership-progress-meta">
              <span>You've reached the highest tier.</span>
              <strong>Welcome to the inner circle.</strong>
            </div>
          )}
        </div>

        <div className="membership-hero-validity">
          <FiClock style={{ verticalAlign: '-2px', marginRight: 6 }} />
          Tier valid until{' '}
          <strong>
            {profile.tierEffectiveUntil
              ? new Date(profile.tierEffectiveUntil).toLocaleDateString(undefined, {
                  year: 'numeric', month: 'short', day: 'numeric',
                })
              : 'lifetime'}
          </strong>
        </div>
      </section>

      {/* ── Stat tiles ──────────────────────────────────────── */}
      <section className="membership-stats">
        <StatTile label="Lifetime earned"    value={profile.pointsEarnedLifetime}   sub="Points earned since joining" />
        <StatTile label="Lifetime redeemed"  value={profile.pointsRedeemedLifetime} sub="Points spent on rewards" />
        <StatTile label="Qualifying credits" value={qc}                             sub="Rolling 12-month window" />
        <StatTile label="Lifetime credits"   value={profile.lifetimeCredits}        sub="Drives all-time milestones" />
      </section>

      {/* ── Tier benefits ───────────────────────────────────── */}
      <section className="membership-section">
        <div className="membership-section-head">
          <div>
            <h2>Your {tierKey.charAt(0) + tierKey.slice(1).toLowerCase()} benefits</h2>
            <p>Perks you unlock at this tier.</p>
          </div>
        </div>
        <div className="membership-benefits">
          {(TIER_BENEFITS[tierKey] || TIER_BENEFITS.BRONZE).map((b) => (
            <div key={b.title} className="benefit-card">
              <div className="benefit-icon">{b.icon}</div>
              <div>
                <h4>{b.title}</h4>
                <p>{b.body}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* ── Activity ledger ─────────────────────────────────── */}
      <section className="membership-section">
        <div className="membership-section-head">
          <div>
            <h2>Activity</h2>
            <p>Every point you've earned, redeemed or adjusted.</p>
          </div>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => reload({ silent: true })}
            disabled={refreshing}
          >
            <FiRefreshCw style={{ marginRight: 6 }} />
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>

        {(ledger?.content || []).length === 0 ? (
          <div className="membership-empty">
            <h3>No activity yet</h3>
            <p>Book your first stay to earn your welcome bonus.</p>
          </div>
        ) : (
          <ul className="ledger-list">
            {ledger.content.map((e) => <LedgerRow key={e.id} entry={e} />)}
          </ul>
        )}

        {ledger.totalPages > 1 && (
          <div className="ledger-pager">
            <button className="btn btn-secondary btn-sm" disabled={ledgerPage === 0}
                    onClick={() => setLedgerPage((p) => p - 1)}>← Prev</button>
            <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>
              Page {ledgerPage + 1} of {ledger.totalPages}
            </span>
            <button className="btn btn-secondary btn-sm" disabled={ledgerPage + 1 >= ledger.totalPages}
                    onClick={() => setLedgerPage((p) => p + 1)}>Next →</button>
          </div>
        )}
      </section>

      {/* ── Status Match ────────────────────────────────────── */}
      <section className="membership-section">
        <div className="membership-section-head">
          <div>
            <h2>Status Match</h2>
            <p>Bring your elite status from another hospitality program.</p>
          </div>
        </div>

        <form className="sm-form" onSubmit={submitStatusMatch}>
          <div className="sm-hint">
            Submit proof (screenshot or statement URL) of your current top-tier
            status with another program. We'll match it for a 90-day challenge —
            hit the qualifying threshold during the window and the tier is yours.
          </div>
          <label>Competitor program
            <input value={smForm.competitorProgramName} required
              onChange={(e) => setSmForm({ ...smForm, competitorProgramName: e.target.value })}
              placeholder="e.g. Marriott Bonvoy" />
          </label>
          <label>Your tier there
            <input value={smForm.competitorTierName}
              onChange={(e) => setSmForm({ ...smForm, competitorTierName: e.target.value })}
              placeholder="e.g. Platinum Elite" />
          </label>
          <label>Tier you're requesting
            <select value={smForm.requestedTierCode}
              onChange={(e) => setSmForm({ ...smForm, requestedTierCode: e.target.value })}>
              <option value="SILVER">SILVER</option>
              <option value="GOLD">GOLD</option>
              <option value="PLATINUM">PLATINUM</option>
            </select>
          </label>
          <label>Proof URL
            <input type="url" required value={smForm.proofUrl}
              onChange={(e) => setSmForm({ ...smForm, proofUrl: e.target.value })}
              placeholder="https://…" />
          </label>
          <div className="sm-submit">
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Submitting…' : 'Submit for review'}
            </button>
          </div>
        </form>

        {statusMatches.length > 0 && (
          <ul className="sm-history">
            {statusMatches.map((r) => (
              <li key={r.id}>
                <span className="sm-prog">
                  {r.competitorProgramName} → {r.requestedTierCode}
                </span>
                <span className={`sm-status ${String(r.status).toLowerCase()}`}>{r.status}</span>
                <span className="sm-date">
                  {new Date(r.createdAt).toLocaleDateString()}
                  {r.proofUrl && (
                    <a href={r.proofUrl} target="_blank" rel="noreferrer" style={{ marginLeft: 8 }}>
                      <FiExternalLink />
                    </a>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/* ── helpers ───────────────────────────────────────────────── */

function StatTile({ label, value, sub }) {
  return (
    <div className="membership-stat">
      <div className="membership-stat-label">{label}</div>
      <div className="membership-stat-value">{Number(value || 0).toLocaleString()}</div>
      {sub && <div className="membership-stat-sub">{sub}</div>}
    </div>
  );
}

function LedgerRow({ entry }) {
  const isCredit = (entry.pointsDelta ?? 0) > 0;
  const isDebit  = (entry.pointsDelta ?? 0) < 0;
  const tone     = isCredit ? 'credit' : isDebit ? 'debit' : 'neutral';
  const IconComp = isCredit ? FiTrendingUp : isDebit ? FiArrowDown : FiArrowUp;
  const reason   = humanReason(entry.reasonCode, entry.entryType);
  return (
    <li className="ledger-item">
      <div className={`ledger-icon ${tone}`}><IconComp /></div>
      <div>
        <div className="ledger-title">{reason}</div>
        <div className="ledger-sub">
          <span>{new Date(entry.createdAt).toLocaleString()}</span>
          {entry.bookingRef && <span>· Booking {entry.bookingRef}</span>}
          <span>· {entry.entryType}</span>
        </div>
      </div>
      <div className={`ledger-points ${tone}`}>
        {isCredit ? '+' : ''}{Number(entry.pointsDelta || 0).toLocaleString()}
      </div>
    </li>
  );
}

function humanReason(code, type) {
  if (!code) return type || 'Activity';
  const map = {
    EARN_BOOKING:    'Earned on booking',
    EARN_BONUS:      'Bonus points',
    EARN_WELCOME:    'Welcome bonus',
    EARN_BIRTHDAY:   'Birthday bonus',
    REDEEM_BOOKING:  'Redeemed on booking',
    REDEEM_REWARD:   'Redeemed reward',
    EXPIRE:          'Points expired',
    ADJUST_ADMIN:    'Admin adjustment',
    STATUS_MATCH:    'Status match bonus',
  };
  return map[code] || code.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}
