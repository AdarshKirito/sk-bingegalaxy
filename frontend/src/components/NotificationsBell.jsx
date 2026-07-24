import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { FiBell, FiCheck, FiAlertTriangle, FiAlertCircle, FiInfo, FiTrash2 } from 'react-icons/fi';
import { adminService } from '../services/endpoints';
import PushToggle from './PushToggle';
import './NotificationsBell.css';

// Near-real-time cadence: poll every 15 s, plus an instant refresh on window focus and
// on the cross-component 'notifications:refresh' event (dispatched when the messages page
// marks something read). Keeps the badge fresh without a page reload.
const POLL_INTERVAL_MS = 15_000;

// Authoritative role label (server-stamped senderRole) — shown on message items so a
// spoofed display name can't disguise who actually sent it.
const roleLabel = (r) => ({ SUPER_ADMIN: 'Super Admin', ADMIN: 'Admin', CUSTOMER: 'Customer', SYSTEM: 'System' }[r] || r || '');

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

  const prevUnreadRef = useRef(0);
  const openRef = useRef(false);
  openRef.current = open;

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

  const fetchUnread = useCallback(async ({ notify = false } = {}) => {
    try {
      const res = await adminService.getAdminNotificationsUnreadCount();
      const c = Number(res?.data?.data?.unread ?? 0) || 0;
      // Popup when the count goes UP (a new message/notification arrived) — near-real-time.
      if (notify && c > prevUnreadRef.current) {
        const delta = c - prevUnreadRef.current;
        toast.info(`🔔 ${delta} new notification${delta > 1 ? 's' : ''}`, { autoClose: 4000 });
        if (openRef.current) fetchList(); // keep an open dropdown live
      }
      prevUnreadRef.current = c;
      setUnread(c);
    } catch { /* silent */ }
  }, [fetchList]);

  // Poll the badge while mounted; refresh instantly on window focus and on the
  // cross-component 'notifications:refresh' event (fired when messages are read).
  useEffect(() => {
    fetchUnread();
    const t = setInterval(() => fetchUnread({ notify: true }), POLL_INTERVAL_MS);
    const onFocus = () => fetchUnread();
    const onVisible = () => { if (document.visibilityState === 'visible') fetchUnread(); };
    const onRefresh = () => { fetchUnread(); if (openRef.current) fetchList(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('notifications:refresh', onRefresh);
    return () => {
      clearInterval(t);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('notifications:refresh', onRefresh);
    };
  }, [fetchUnread, fetchList]);

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

  // Clicking a system notification opens it in the message inbox (full, viewable) via
  // ?notif=<id>, rather than jumping straight to a page — so every notification is readable.
  const handleClickItem = async (n) => {
    try {
      if (!n.readAt) {
        await adminService.markAdminNotificationRead(n.id);
        setItems((prev) => prev.map((it) => it.id === n.id ? { ...it, readAt: new Date().toISOString() } : it));
        setUnread((u) => Math.max(0, u - 1));
        prevUnreadRef.current = Math.max(0, prevUnreadRef.current - 1);
      }
    } catch { /* ignore */ }
    setOpen(false);
    navigate(`/admin/messages?notif=${n.id}`);
  };

  const handleMarkAll = async () => {
    try {
      await adminService.markAllAdminNotificationsRead();
      const now = new Date().toISOString();
      setItems((prev) => prev.map((it) => it.readAt ? it : { ...it, readAt: now }));
      setUnread(0);
    } catch { /* ignore */ }
  };

  const handleDelete = async (n, e) => {
    e.stopPropagation();
    try {
      await adminService.deleteAdminNotification(n.id);
      setItems((prev) => prev.filter((it) => it.id !== n.id));
      if (!n.readAt) setUnread((u) => Math.max(0, u - 1));
    } catch { /* ignore */ }
  };

  const handleClearRead = async () => {
    try {
      await adminService.clearReadAdminNotifications();
      setItems((prev) => prev.filter((it) => !it.readAt));
    } catch { /* ignore */ }
  };

  const openMessages = (threadId) => {
    setOpen(false);
    navigate(threadId ? `/admin/messages?thread=${threadId}` : '/admin/messages');
  };

  // Clicking a message notification marks it read (so it clears from the bell) then opens it.
  const handleClickMessage = async (n) => {
    if (!n.readAt) {
      try { await adminService.markAdminNotificationRead(n.id); } catch { /* ignore */ }
      setItems((prev) => prev.map((it) => (it.id === n.id ? { ...it, readAt: new Date().toISOString() } : it)));
      setUnread((u) => Math.max(0, u - 1));
      prevUnreadRef.current = Math.max(0, prevUnreadRef.current - 1);
    }
    openMessages(n.threadId);
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
            <span className="nb-head-actions">
              {items.some((n) => !n.readAt) && (
                <button type="button" className="nb-link" onClick={handleMarkAll}>
                  <FiCheck /> Mark all read
                </button>
              )}
              {items.some((n) => n.readAt) && (
                <button type="button" className="nb-link" onClick={handleClearRead}>
                  <FiTrash2 /> Clear read
                </button>
              )}
            </span>
          </div>

          <div className="nb-list">
            {loading && <div className="nb-empty">Loading…</div>}
            {!loading && items.length === 0 && <div className="nb-empty">You're all caught up.</div>}
            {!loading && items.map((n) => (
              <div
                key={n.id}
                className={`nb-item nb-sev-${(n.severity || 'info').toLowerCase()}${n.readAt ? '' : ' nb-unread'}`}
                role="button"
                tabIndex={0}
                onClick={() => (n.system === false ? handleClickMessage(n) : handleClickItem(n))}
                onKeyDown={(e) => { if (e.key === 'Enter') (n.system === false ? handleClickMessage(n) : handleClickItem(n)); }}
              >
                <span className="nb-item-icon">{severityIcon(n.severity)}</span>
                <span className="nb-item-body">
                  <span className="nb-item-title">
                    {n.system === false && n.senderName ? `${n.senderName}: ` : ''}{n.title}
                    {n.system === false && <span className="nb-role-badge">{roleLabel(n.senderRole)}</span>}
                  </span>
                  <span className="nb-item-msg">{n.message}</span>
                  <span className="nb-item-time">{formatRelative(n.createdAt)}</span>
                </span>
                <button type="button" className="nb-item-del" aria-label="Delete" title="Delete"
                  onClick={(e) => handleDelete(n, e)}>
                  <FiTrash2 />
                </button>
              </div>
            ))}
          </div>

          <div className="nb-panel-foot">
            <PushToggle compact />
            <button type="button" className="nb-viewall" onClick={() => openMessages()}>
              View all messages →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
