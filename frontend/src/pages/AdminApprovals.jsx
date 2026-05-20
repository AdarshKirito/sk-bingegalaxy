import { useEffect, useState, useCallback } from 'react';
import { adminService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { FiCheck, FiX, FiPlay, FiRefreshCw, FiUser, FiClock } from 'react-icons/fi';
import './AdminPages.css';

/**
 * Maker-checker dashboard: shows risky-action approval requests created by
 * one admin and decided by a *different* one. Same-actor approve / reject is
 * rejected by the server, so the UI doesn't need a client-side guard, but we
 * surface a hint to keep the workflow obvious.
 */
const STATUS_FILTERS = [
  { key: '',          label: 'All' },
  { key: 'PENDING',   label: 'Pending' },
  { key: 'APPROVED',  label: 'Approved · awaiting execution' },
  { key: 'EXECUTED',  label: 'Executed' },
  { key: 'REJECTED',  label: 'Rejected' },
  { key: 'EXPIRED',   label: 'Expired' },
  { key: 'CANCELLED', label: 'Cancelled' },
];

const STATUS_COLOR = {
  PENDING:   { bg: 'rgba(245, 158, 11, 0.15)', fg: '#f59e0b' },
  APPROVED:  { bg: 'rgba(59, 130, 246, 0.15)', fg: '#3b82f6' },
  EXECUTED:  { bg: 'rgba(16, 185, 129, 0.15)', fg: '#10b981' },
  REJECTED:  { bg: 'rgba(239, 68, 68, 0.15)',  fg: '#ef4444' },
  EXPIRED:   { bg: 'rgba(107, 114, 128, 0.15)', fg: '#6b7280' },
  CANCELLED: { bg: 'rgba(107, 114, 128, 0.15)', fg: '#6b7280' },
};

const fmt = (v) => v == null ? '—' : new Date(v).toLocaleString();

const StatusPill = ({ s }) => {
  const c = STATUS_COLOR[s] || STATUS_COLOR.PENDING;
  return <span style={{
    background: c.bg, color: c.fg, padding: '2px 8px', borderRadius: 4,
    fontSize: '0.78rem', fontWeight: 600, letterSpacing: '0.02em',
  }}>{s}</span>;
};

export default function AdminApprovals() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const myEmail = (user?.email || '').toLowerCase();
  const myId = user?.id;

  const [status, setStatus] = useState('PENDING');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [tick, setTick] = useState(0);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.listApprovals(status ? { status } : {});
      const d = res.data?.data || res.data;
      // Backend wraps as { page, size, total, rows: […] }
      setItems(Array.isArray(d) ? d : (d?.rows || d?.content || []));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load approvals');
      setItems([]);
    } finally { setLoading(false); }
  }, [status, tick]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const isOwnRequest = (req) => {
    if (req.requestedById && myId) return Number(req.requestedById) === Number(myId);
    // DTO field is `requestedBy` (email)
    return (req.requestedBy || req.requestedByEmail || '').toLowerCase() === myEmail;
  };

  const decide = async (id, kind) => {
    const isReject = kind === 'reject';
    const isCancel = kind === 'cancel';
    const result = await confirm({
      title: `${kind === 'approve' ? 'Approve' : isReject ? 'Reject' : 'Cancel'} approval #${id}`,
      message: kind === 'approve'
        ? 'Approving will move this request to the executable queue. A different admin must execute the actual action.'
        : isReject
          ? 'Rejecting closes the request permanently. The maker will be notified with your reason.'
          : 'Cancelling withdraws the request. Use this only if you raised it by mistake.',
      confirmLabel: kind === 'approve' ? 'Approve' : isReject ? 'Reject' : 'Cancel request',
      cancelLabel: 'Back',
      variant: kind === 'approve' ? 'primary' : 'danger',
      withReason: true,
      reasonRequired: isReject || isCancel,
      reasonLabel: kind === 'approve' ? 'Approval note (optional)' : 'Reason',
      reasonPlaceholder: kind === 'approve' ? 'Add an optional note for the audit log…' : 'Required — explain why',
    });
    if (!result) return;
    const reason = result.reason || '';
    setBusyId(id);
    try {
      if (kind === 'approve')      await adminService.approveApproval(id, reason);
      else if (kind === 'reject')  await adminService.rejectApproval(id, reason);
      else                          await adminService.cancelApproval(id, reason);
      toast.success(`Approval #${id} ${kind === 'approve' ? 'approved' : kind === 'reject' ? 'rejected' : 'cancelled'}`);
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Action failed');
    } finally { setBusyId(null); }
  };

  const execute = async (req) => {
    if (req.actionType !== 'REFUND_RETRY') {
      toast.error(`Execution for ${req.actionType} is not yet wired.`);
      return;
    }
    const ok = await confirm({
      title: `Execute refund retry #${req.id}?`,
      message: 'This charges the payment gateway again and is irreversible. Make sure the original refund truly failed before retrying.',
      confirmLabel: 'Execute refund retry',
      cancelLabel: 'Cancel',
      variant: 'danger',
    });
    if (!ok) return;
    setBusyId(req.id);
    try {
      const res = await adminService.executeApprovedRefundRetry(req.id);
      const newId = res.data?.data?.refund?.gatewayRefundId;
      toast.success(`Refund retried · gateway id ${newId || 'OK'}`);
      setTick(t => t + 1);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Execution failed');
    } finally { setBusyId(null); }
  };

  return (
    <div className="admin-page" style={{ maxWidth: 1280, margin: '0 auto', padding: '1.25rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <h1 style={{ margin: 0 }}>Maker-checker approvals</h1>
          <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)' }}>
            Risky actions above their threshold need a different admin to approve.
            You cannot approve a request you raised yourself.
          </p>
        </div>
        <button className="btn-secondary" onClick={() => setTick(t => t + 1)} disabled={loading}>
          <FiRefreshCw style={{ verticalAlign: '-2px', marginRight: 6 }} /> Refresh
        </button>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        {STATUS_FILTERS.map(f => (
          <button
            key={f.key || 'all'}
            onClick={() => setStatus(f.key)}
            style={{
              padding: '0.4rem 0.8rem',
              borderRadius: 4, cursor: 'pointer',
              border: '1px solid var(--border)',
              background: status === f.key ? 'var(--primary)' : 'transparent',
              color: status === f.key ? '#fff' : 'var(--text-primary)',
              fontWeight: status === f.key ? 600 : 400,
            }}>{f.label}</button>
        ))}
      </div>

      {loading ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading…</div>
      ) : items.length === 0 ? (
        <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
          No approval requests {status ? `in status ${status}` : ''}.
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '0.75rem' }}>
          {items.map(req => {
            const own = isOwnRequest(req);
            const expired = req.expiresAt && new Date(req.expiresAt).getTime() < Date.now();
            return (
              <div key={req.id} style={{
                border: '1px solid var(--border)', borderRadius: 8, padding: '1rem',
                background: 'var(--surface, #111827)',
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
                  <div style={{ flex: 1, minWidth: 280 }}>
                    <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginBottom: '0.4rem', flexWrap: 'wrap' }}>
                      <strong>#{req.id}</strong>
                      <span style={{ color: 'var(--text-secondary)' }}>·</span>
                      <strong style={{ color: 'var(--primary)' }}>{req.actionType}</strong>
                      <StatusPill s={req.status} />
                      {own && <span style={{
                        background: 'rgba(245, 158, 11, 0.15)', color: '#f59e0b',
                        padding: '2px 8px', borderRadius: 4, fontSize: '0.75rem',
                      }}>YOUR REQUEST</span>}
                      {expired && req.status === 'PENDING' && <span style={{
                        background: 'rgba(107, 114, 128, 0.15)', color: '#6b7280',
                        padding: '2px 8px', borderRadius: 4, fontSize: '0.75rem',
                      }}>TTL elapsed</span>}
                    </div>
                    <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', lineHeight: 1.5 }}>
                      <div><strong>Resource:</strong> {req.resourceType} · {req.resourceId}</div>
                      {req.amount && (
                        <div><strong>Amount:</strong> {req.amount} {req.currency || ''}</div>
                      )}
                      {req.requestReason && <div><strong>Reason:</strong> {req.requestReason}</div>}
                      <div style={{ marginTop: 6, display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                        <span><FiUser style={{ verticalAlign: '-2px' }} /> Requested by {req.requestedBy || req.requestedByEmail || req.requestedById} · {fmt(req.requestedAt)}</span>
                        {(req.reviewedBy || req.reviewedByEmail) && (
                          <span><FiUser style={{ verticalAlign: '-2px' }} /> Reviewed by {req.reviewedBy || req.reviewedByEmail} · {fmt(req.reviewedAt)}</span>
                        )}
                        <span><FiClock style={{ verticalAlign: '-2px' }} /> Expires {fmt(req.expiresAt)}</span>
                      </div>
                      {(req.reviewReason || req.reviewerComment) && (
                        <div style={{ marginTop: 6, padding: '6px 8px', background: 'rgba(255,255,255,0.04)', borderRadius: 4 }}>
                          <strong>Reviewer note:</strong> {req.reviewReason || req.reviewerComment}
                        </div>
                      )}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                    {req.status === 'PENDING' && !own && (
                      <>
                        <button className="btn-primary" disabled={busyId === req.id}
                          onClick={() => decide(req.id, 'approve')}>
                          <FiCheck style={{ verticalAlign: '-2px' }} /> Approve
                        </button>
                        <button className="btn-danger" disabled={busyId === req.id}
                          onClick={() => decide(req.id, 'reject')}>
                          <FiX style={{ verticalAlign: '-2px' }} /> Reject
                        </button>
                      </>
                    )}
                    {req.status === 'PENDING' && own && (
                      <button className="btn-secondary" disabled={busyId === req.id}
                        onClick={() => decide(req.id, 'cancel')}>
                        <FiX style={{ verticalAlign: '-2px' }} /> Cancel
                      </button>
                    )}
                    {req.status === 'APPROVED' && (
                      <button className="btn-primary" disabled={busyId === req.id}
                        onClick={() => execute(req)}>
                        <FiPlay style={{ verticalAlign: '-2px' }} /> Execute
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
