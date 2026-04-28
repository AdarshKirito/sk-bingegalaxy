import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { FiBell, FiCheck, FiAlertTriangle, FiAlertCircle, FiInfo } from 'react-icons/fi';
import { adminService } from '../services/endpoints';
import './NotificationsBell.css';

const POLL_INTERVAL_MS = 60_000; // 1 minute — light polling, no SSE dependency.

const severityIcon = (severity) => {
  switch ((severity || '').toUpperCase()) {
    case 'CRITICAL': return <FiAlertCircle aria-hidden="true" />;
    case 'WARNING': return <FiAlertTriangle aria-hidden="true" />;
    default: return <FiInfo aria-hidden="true" />;
  }
};

const formatRelative = (iso) => {
  if (!iso) return '';
  const now = Date.now();
  const then = new Date(iso).getTime();
  const diff = Math.max(0, now - then);
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
};

/**
 * Bell icon + dropdown showing the current admin's in-app notifications.
 * Light polling (every 60 s) keeps the unread badge fresh; clicking a
 * notification marks it read and (optionally) deep-links via actionUrl.
 */
export default function NotificationsBell() {
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const wrapRef = useRef(null);

  const fetchUnread = useCallback(async () => {
    try {
      const res = await adminService.getAdminNotificationsUnreadCount();
      const c = res?.data?.data?.unread ?? 0;
      setUnread(Number(c) || 0);
    } catch { /* silent */ }
  }, []);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await adminService.listAdminNotifications(0, 20);
      const page = res?.data?.data;
      setItems(page?.content || []);
    } catch { /* silent */ } finally {
      setLoading(false);
    }
  }, []);

  // Poll the badge while mounted.
  useEffect(() => {
    fetchUnread();
    const t = setInterval(fetchUnread, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, [fetchUnread]);

  // Refresh the list each time the dropdown opens.
  useEffect(() => {
    if (open) fetchList();
  }, [open, fetchList]);

  // Click-outside close.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const handleClickItem = async (n) => {
    try {
      if (!n.readAt) {
        await adminService.markAdminNotificationRead(n.id);
        setItems((prev) => prev.map((it) => it.id === n.id ? { ...it, readAt: new Date().toISOString() } : it));
        setUnread((u) => Math.max(0, u - 1));
      }
    } catch { /* ignore */ }
    if (n.actionUrl) {
      setOpen(false);
      navigate(n.actionUrl);
    }
  };

  const handleMarkAll = async () => {
    try {
      await adminService.markAllAdminNotificationsRead();
      const now = new Date().toISOString();
      setItems((prev) => prev.map((it) => it.readAt ? it : { ...it, readAt: now }));
      setUnread(0);
    } catch { /* ignore */ }
  };

  return (
    <div className="nb-wrap" ref={wrapRef}>
      <button
        type="button"
        className="nb-trigger"
        aria-label={`Notifications${unread > 0 ? ` (${unread} unread)` : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <FiBell />
        {unread > 0 && <span className="nb-badge">{unread > 99 ? '99+' : unread}</span>}
      </button>

      {open && (
        <div className="nb-panel" role="dialog" aria-label="Notifications">
          <div className="nb-panel-head">
            <strong>Notifications</strong>
            {items.some((n) => !n.readAt) && (
              <button type="button" className="nb-link" onClick={handleMarkAll}>
                <FiCheck /> Mark all read
              </button>
            )}
          </div>

          <div className="nb-list">
            {loading && <div className="nb-empty">Loading…</div>}
            {!loading && items.length === 0 && <div className="nb-empty">You're all caught up.</div>}
            {!loading && items.map((n) => (
              <button
                key={n.id}
                type="button"
                className={`nb-item nb-sev-${(n.severity || 'info').toLowerCase()}${n.readAt ? '' : ' nb-unread'}`}
                onClick={() => handleClickItem(n)}
              >
                <span className="nb-item-icon">{severityIcon(n.severity)}</span>
                <span className="nb-item-body">
                  <span className="nb-item-title">{n.title}</span>
                  <span className="nb-item-msg">{n.message}</span>
                  <span className="nb-item-time">{formatRelative(n.createdAt)}</span>
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
