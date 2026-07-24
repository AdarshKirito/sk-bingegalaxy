import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { FiMail } from 'react-icons/fi';
import { messageService } from '../services/endpoints';
import './NotificationsBell.css';

const POLL_INTERVAL_MS = 60_000; // light polling, mirrors the admin bell.

/**
 * Compact messages indicator for customers: an envelope with an unread badge that
 * routes to the full inbox at /messages. Kept intentionally minimal (no dropdown) —
 * the inbox page is the place to read/reply; this is only an at-a-glance "you have mail".
 */
export default function CustomerMessagesBell() {
  const [unread, setUnread] = useState(0);
  const navigate = useNavigate();

  const fetchUnread = useCallback(async () => {
    try {
      const res = await messageService.getMyUnreadCount();
      setUnread(Number(res?.data?.data?.unread ?? 0) || 0);
    } catch { /* silent — never block the navbar on a polling failure */ }
  }, []);

  useEffect(() => {
    fetchUnread();
    const t = setInterval(fetchUnread, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, [fetchUnread]);

  return (
    <div className="nb-wrap">
      <button
        type="button"
        className="nb-trigger"
        aria-label={`Messages${unread > 0 ? ` (${unread} unread)` : ''}`}
        onClick={() => navigate('/messages')}
      >
        <FiMail />
        {unread > 0 && <span className="nb-badge">{unread > 99 ? '99+' : unread}</span>}
      </button>
    </div>
  );
}
