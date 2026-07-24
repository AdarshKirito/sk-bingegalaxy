import { useEffect, useMemo, useState } from 'react';
import { adminService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { useAuth } from '../context/AuthContext';
import { formatInZone } from '../services/timeFormat';
import { invalidateModuleAccess } from '../hooks/useModuleAccess';
import { toast } from 'react-toastify';
import { FiInfo, FiLock, FiShield, FiUnlock, FiSave } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Binge About / Access / Permissions.
 *
 * Everyone with access to the binge sees WHAT it is (identity + lifecycle
 * audit: created/approved, by whom, when) and WHICH options are enabled,
 * disabled or locked for its admin — so a missing menu item is explained, not
 * mysterious. SUPER_ADMIN additionally edits the matrix inline (enable /
 * disable / lock per module, with remarks) and the binge-level access remarks.
 * Backend enforcement (403) is independent of this page.
 */

const MODULE_LABELS = {
  REPORTS: 'Reports', MESSAGES: 'Messages', VENUE: 'Venue', ROOMS: 'Rooms',
  EVENT_TYPES: 'Event Types', RATE_CODES: 'Rate Codes', SURGE_RULES: 'Surge Rules',
  BLOCKED_DATES: 'Blocked Dates', SLOT_HOLDS: 'Slot Holds', PEOPLE: 'People',
  USERS: 'Users', WAITLIST: 'Waitlist', CUSTOMER_FREEZES: 'Customer Freezes',
  RISK_FLAGS: 'Risk Flags', SUPPORT_CONSOLE: 'Support Console',
  DISPUTES: 'Disputes', FAILED_REFUNDS: 'Failed Refunds',
};

const STATE_BADGE = {
  ENABLED:  { label: 'Enabled',  cls: 'adm-badge adm-badge-active' },
  DISABLED: { label: 'Disabled', cls: 'adm-badge adm-badge-inactive' },
  LOCKED:   { label: 'Locked by Super Admin', cls: 'adm-badge adm-badge-inactive' },
};

export default function AdminBingeAbout() {
  const { selectedBinge } = useBinge();
  const { isSuperAdmin } = useAuth();
  const [about, setAbout] = useState(null);
  const [loading, setLoading] = useState(true);
  const [savingKey, setSavingKey] = useState(null);
  const [remarks, setRemarks] = useState('');
  const [savingRemarks, setSavingRemarks] = useState(false);

  const bingeId = selectedBinge?.id;
  const tz = about?.timezone || selectedBinge?.timezone || 'Asia/Kolkata';
  const dt = (v) => (v ? formatInZone(v, tz) : '—');

  const load = () => {
    if (!bingeId) return;
    setLoading(true);
    adminService.getBingeAbout(bingeId)
      .then((res) => {
        const data = res.data?.data || res.data;
        setAbout(data);
        setRemarks(data?.accessRemarks || '');
      })
      .catch((err) => toast.error(err.userMessage || err.response?.data?.message || 'Failed to load the About page'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [bingeId]); // eslint-disable-line react-hooks/exhaustive-deps

  const moduleStates = useMemo(() => about?.moduleStates || [], [about]);
  const enabledCount = moduleStates.filter((m) => m.state === 'ENABLED').length;
  const disabledList = moduleStates.filter((m) => m.state === 'DISABLED');
  const lockedList = moduleStates.filter((m) => m.state === 'LOCKED');

  const setModule = async (moduleKey, enabled, locked) => {
    setSavingKey(moduleKey);
    try {
      await adminService.setBingeModulePermission(bingeId, moduleKey, { enabled, locked });
      toast.success(`${MODULE_LABELS[moduleKey] || moduleKey} ${locked ? 'locked' : enabled ? 'enabled' : 'disabled'}`);
      invalidateModuleAccess(bingeId); // menu cache — affected admin sees it next fetch
      load();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to update the permission');
    } finally {
      setSavingKey(null);
    }
  };

  const saveRemarks = async () => {
    setSavingRemarks(true);
    try {
      await adminService.setBingeAccessRemarks(bingeId, remarks);
      toast.success('Remarks saved');
      load();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save remarks');
    } finally {
      setSavingRemarks(false);
    }
  };

  if (!bingeId) {
    return (
      <div className="container adm-shell">
        <div className="adm-empty"><h3>Select a binge first</h3></div>
      </div>
    );
  }

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiInfo /> About</span>
          <h1>{about?.name || selectedBinge?.name}</h1>
          <p>Identity, lifecycle audit and option access for this binge.</p>
        </div>
      </div>

      {loading ? <div className="loading"><div className="spinner"></div></div> : about && (
        <>
          {/* ── Identity & lifecycle ── */}
          <div className="adm-form" style={{ marginBottom: '1rem' }}>
            <h3 style={{ marginTop: 0 }}><FiInfo style={{ marginRight: 6, verticalAlign: -2 }} />Binge details</h3>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '0.6rem 1.2rem', fontSize: '0.9rem' }}>
              <div><span style={{ color: 'var(--text-muted)' }}>Binge ID</span><br /><strong>#{about.bingeId}</strong></div>
              <div><span style={{ color: 'var(--text-muted)' }}>Status</span><br />
                <strong>
                  <span className={`adm-badge ${about.status === 'APPROVED' && about.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                    {about.status}{about.active === false ? ' · inactive' : ''}
                  </span>
                </strong>
              </div>
              <div><span style={{ color: 'var(--text-muted)' }}>Created</span><br /><strong>{dt(about.createdAt)}</strong></div>
              <div><span style={{ color: 'var(--text-muted)' }}>Created by</span><br />
                <strong>{about.adminName || `Admin #${about.createdByAdminId}`}</strong>
                {about.adminEmail && <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>{about.adminEmail}</div>}
              </div>
              <div><span style={{ color: 'var(--text-muted)' }}>Approved by</span><br />
                <strong>{about.approvedByUserId ? `Super Admin #${about.approvedByUserId}` : '—'}</strong>
              </div>
              <div><span style={{ color: 'var(--text-muted)' }}>Approved on</span><br /><strong>{dt(about.approvedAt)}</strong></div>
              <div><span style={{ color: 'var(--text-muted)' }}>Last updated</span><br /><strong>{dt(about.updatedAt)}</strong></div>
              <div><span style={{ color: 'var(--text-muted)' }}>Timezone / currency</span><br />
                <strong>{about.timezone || '—'} · {about.currency || '—'}</strong>
              </div>
              {about.address && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <span style={{ color: 'var(--text-muted)' }}>Address</span><br /><strong>{about.address}</strong>
                </div>
              )}
            </div>
          </div>

          {/* ── Option access summary ── */}
          <div className="adm-form" style={{ marginBottom: '1rem' }}>
            <h3 style={{ marginTop: 0 }}><FiShield style={{ marginRight: 6, verticalAlign: -2 }} />Option access</h3>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: 0 }}>
              {enabledCount} of {moduleStates.length} options enabled for this binge's admin
              {disabledList.length > 0 && ` · disabled: ${disabledList.map((m) => MODULE_LABELS[m.module] || m.module).join(', ')}`}
              {lockedList.length > 0 && ` · locked: ${lockedList.map((m) => MODULE_LABELS[m.module] || m.module).join(', ')}`}
              {disabledList.length === 0 && lockedList.length === 0 && ' — no restrictions.'}
            </p>

            <div style={{ overflowX: 'auto' }}>
              <table className="adm-table" style={{ width: '100%', fontSize: '0.85rem' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left' }}>Option</th>
                    <th style={{ textAlign: 'left' }}>Status</th>
                    <th style={{ textAlign: 'left' }}>Remarks</th>
                    <th style={{ textAlign: 'left' }}>Last changed</th>
                    {isSuperAdmin && <th style={{ textAlign: 'left' }}>Change</th>}
                  </tr>
                </thead>
                <tbody>
                  {moduleStates.map((m) => {
                    const badge = STATE_BADGE[m.state] || STATE_BADGE.ENABLED;
                    return (
                      <tr key={m.module}>
                        <td style={{ fontWeight: 600 }}>{MODULE_LABELS[m.module] || m.module}</td>
                        <td>
                          <span className={badge.cls}>
                            {m.state === 'LOCKED' && <FiLock style={{ marginRight: 3, verticalAlign: -2 }} />}
                            {badge.label}
                          </span>
                        </td>
                        <td style={{ color: 'var(--text-muted)' }}>{m.remarks || '—'}</td>
                        <td style={{ color: 'var(--text-muted)' }}>
                          {/* Every admin sees WHEN an option changed; WHO changed it is a
                              super-admin-only detail (regular admins don't need to know
                              which super-admin flipped the switch). */}
                          {m.lastChangedAt
                            ? `${dt(m.lastChangedAt)}${isSuperAdmin && m.lastChangedBy ? ` · by #${m.lastChangedBy}` : ''}`
                            : '—'}
                        </td>
                        {isSuperAdmin && (
                          <td>
                            <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                              {m.state !== 'ENABLED' && (
                                <button className="btn btn-secondary btn-sm" disabled={savingKey === m.module}
                                  onClick={() => setModule(m.module, true, false)}>
                                  <FiUnlock style={{ marginRight: 2 }} />Enable
                                </button>
                              )}
                              {m.state === 'ENABLED' && (
                                <button className="btn btn-secondary btn-sm" disabled={savingKey === m.module}
                                  onClick={() => setModule(m.module, false, false)}>
                                  Disable
                                </button>
                              )}
                              {m.state !== 'LOCKED' && (
                                <button className="btn btn-danger btn-sm" disabled={savingKey === m.module}
                                  title="Disable AND prevent anyone below Super Admin from re-enabling it"
                                  onClick={() => setModule(m.module, false, true)}>
                                  <FiLock style={{ marginRight: 2 }} />Lock
                                </button>
                              )}
                            </div>
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {!isSuperAdmin && (disabledList.length > 0 || lockedList.length > 0) && (
              <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 0 }}>
                Disabled/locked options were set by the Super Admin — contact them if you need access.
              </p>
            )}
          </div>

          {/* ── Remarks ── */}
          <div className="adm-form">
            <h3 style={{ marginTop: 0 }}>Remarks</h3>
            {isSuperAdmin ? (
              <>
                <textarea rows={3} maxLength={1000} value={remarks}
                  onChange={(e) => setRemarks(e.target.value)}
                  placeholder="Access / operational notes about this binge (visible to its admin)…"
                  style={{ width: '100%', resize: 'vertical' }} />
                <button className="btn btn-primary btn-sm" style={{ marginTop: '0.5rem' }}
                  disabled={savingRemarks} onClick={saveRemarks}>
                  <FiSave style={{ marginRight: 3 }} />{savingRemarks ? 'Saving…' : 'Save remarks'}
                </button>
              </>
            ) : (
              <p style={{ color: about.accessRemarks ? 'inherit' : 'var(--text-muted)', marginBottom: 0 }}>
                {about.accessRemarks || 'No remarks.'}
              </p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
