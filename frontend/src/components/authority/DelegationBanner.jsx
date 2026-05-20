import { Link } from 'react-router-dom';
import { FiShield, FiClock } from 'react-icons/fi';
import { useAuth } from '../../context/AuthContext';

/**
 * Persistent visual cue that the current admin is operating under a temporary
 * Authority Handover delegation. Renders nothing for native super-admins or
 * non-delegated admins. Production-grade rationale:
 *
 *  - The grantee should always know they are wielding elevated authority — never
 *    have to guess. This deters accidental misuse and helps with hand-back.
 *  - The badge surfaces remaining time + the granted scope set so the grantee
 *    can plan their work before authority lapses.
 *  - The banner is read-only; granting / revoking lives behind /admin/super/authority,
 *    which the delegated admin cannot see (native super-admin only).
 */
const SCOPE_LABEL = {
  CURRENCIES: 'Currencies',
  NOTIFICATIONS: 'Notifications',
  LOYALTY: 'Loyalty',
  OPS: 'Operations',
  ALL_USERS: 'All users',
  CUSTOMER_EDIT: 'Customer edit',
  ADMIN_REGISTER: 'Admin onboarding',
  HOME_CMS: 'Home CMS',
  ACCOUNT_CMS: 'Account CMS',
  SUPER_DASHBOARD: 'Super dashboard',
};

function fmtRemaining(iso) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return 'expiring';
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export default function DelegationBanner() {
  const { effectiveAuthority } = useAuth();
  if (!effectiveAuthority?.delegated) return null;
  const scopes = Array.isArray(effectiveAuthority.scopes) ? effectiveAuthority.scopes : [];
  if (scopes.length === 0) return null;
  const remaining = fmtRemaining(effectiveAuthority.nextExpiryAt);

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: 'sticky',
        top: 0,
        zIndex: 50,
        display: 'flex',
        flexWrap: 'wrap',
        gap: 12,
        alignItems: 'center',
        padding: '8px 16px',
        background: 'linear-gradient(90deg, rgba(255,193,7,0.18), rgba(255,87,34,0.16))',
        borderBottom: '1px solid rgba(255,193,7,0.45)',
        color: '#5a3b00',
        fontSize: 13,
        fontWeight: 600,
      }}
    >
      <FiShield aria-hidden="true" />
      <span>Delegated authority active.</span>
      <span style={{ fontWeight: 500 }}>
        Scopes:&nbsp;
        {scopes.map((s, i) => (
          <span key={s}>
            {SCOPE_LABEL[s] || s}{i < scopes.length - 1 ? ', ' : ''}
          </span>
        ))}
      </span>
      {remaining && (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 500 }}>
          <FiClock aria-hidden="true" /> {remaining} remaining
        </span>
      )}
      <Link
        to="/admin/platform"
        style={{ marginLeft: 'auto', color: 'inherit', textDecoration: 'underline' }}
      >
        Hand back
      </Link>
    </div>
  );
}
