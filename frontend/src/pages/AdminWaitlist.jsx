import { useState, useEffect, useMemo } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';
import { FiCalendar, FiClock, FiUsers, FiUser, FiMail, FiPhone, FiCheck, FiSend, FiXCircle, FiStar } from 'react-icons/fi';
import { formatTime12h } from '../utils/format';
import './AdminPages.css';

const fmtTime = (timeStr) => formatTime12h(timeStr) || '--:--';

// Priority presets used by the inline editor. The numeric value drives the
// server-side ordering: higher number = higher position in the queue. The
// 100 "ops override" tier is intentionally outside the loyalty band so
// support agents can guarantee a slot for VIPs without colliding with the
// loyalty tiers.
const PRIORITY_PRESETS = [
  { value: 0,   label: 'Standard' },
  { value: 10,  label: 'Silver' },
  { value: 20,  label: 'Gold' },
  { value: 30,  label: 'Platinum' },
  { value: 100, label: 'Ops override' },
];
const priorityLabel = (val) => {
  const exact = PRIORITY_PRESETS.find(p => p.value === Number(val));
  if (exact) return exact.label;
  if (val == null) return 'Standard';
  return `Custom (${val})`;
};

const STATUS_COLORS = {
  WAITING: 'badge-warning',
  OFFERED: 'badge-info',
  CONVERTED: 'badge-success',
  BOOKED: 'badge-success',
  EXPIRED: 'badge-secondary',
  CANCELLED: 'badge-danger',
};

export default function AdminWaitlist() {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [busyEntryId, setBusyEntryId] = useState(null);
  const [selectedDate, setSelectedDate] = useState(format(new Date(), 'yyyy-MM-dd'));

  const fetchWaitlist = async (date) => {
    setLoading(true);
    try {
      const res = await adminService.getWaitlistForDate(date);
      setEntries(toArray(res.data?.data));
    } catch {
      toast.error('Failed to load waitlist');
      setEntries([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (selectedDate) fetchWaitlist(selectedDate);
  }, [selectedDate]);

  const handleOffer = async (entry) => {
    if (!window.confirm(`Send a waitlist offer to ${entry.customerName || 'this customer'}? They will receive an email with a time-limited booking window.`)) return;
    setBusyEntryId(entry.id);
    try {
      await adminService.offerWaitlistEntry(entry.id);
      toast.success('Offer sent');
      await fetchWaitlist(selectedDate);
    } catch (e) {
      toast.error(e.response?.data?.message || e.userMessage || 'Failed to send offer');
    } finally {
      setBusyEntryId(null);
    }
  };

  const handleCancel = async (entry) => {
    if (!window.confirm(`Cancel waitlist entry for ${entry.customerName || 'this customer'}? This cannot be undone.`)) return;
    setBusyEntryId(entry.id);
    try {
      await adminService.cancelWaitlistEntry(entry.id);
      toast.success('Waitlist entry cancelled');
      await fetchWaitlist(selectedDate);
    } catch (e) {
      toast.error(e.response?.data?.message || e.userMessage || 'Failed to cancel entry');
    } finally {
      setBusyEntryId(null);
    }
  };

  // Re-prioritise a waitlist entry. The server re-computes positions after
  // each change so we just refresh the list once the PATCH succeeds.
  const handlePriorityChange = async (entry, nextPriority) => {
    const value = Number(nextPriority);
    if (Number.isNaN(value)) return;
    if (value === Number(entry.priority || 0)) return;
    setBusyEntryId(entry.id);
    try {
      await adminService.updateWaitlistPriority(entry.id, value);
      toast.success(`Priority updated to ${priorityLabel(value)}`);
      await fetchWaitlist(selectedDate);
    } catch (e) {
      toast.error(e.response?.data?.message || e.userMessage || 'Failed to update priority');
    } finally {
      setBusyEntryId(null);
    }
  };

  const dateOptions = useMemo(() => {
    const dates = [];
    for (let i = 0; i < 30; i++) {
      dates.push(format(addDays(new Date(), i), 'yyyy-MM-dd'));
    }
    return dates;
  }, []);

  const grouped = useMemo(() => {
    const map = {};
    for (const entry of entries) {
      const status = entry.status || 'WAITING';
      if (!map[status]) map[status] = [];
      map[status].push(entry);
    }
    return map;
  }, [entries]);

  const statusOrder = ['WAITING', 'OFFERED', 'BOOKED', 'CONVERTED', 'EXPIRED', 'CANCELLED'];
  const totals = useMemo(() => ({
    waiting: (grouped.WAITING || []).length,
    offered: (grouped.OFFERED || []).length,
    booked: ((grouped.BOOKED || []).length + (grouped.CONVERTED || []).length),
    expired: (grouped.EXPIRED || []).length,
    cancelled: (grouped.CANCELLED || []).length,
  }), [grouped]);

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiUsers /> Waitlist Management</h1>
          <p>Manage waitlisted customers — offer slots, cancel entries, and audit history.</p>
        </div>
      </div>

      <div className="admin-toolbar">
        <div className="admin-toolbar-group">
          <label className="admin-toolbar-label"><FiCalendar /> Date</label>
          <select
            value={selectedDate}
            onChange={(e) => setSelectedDate(e.target.value)}
            className="admin-select"
          >
            {dateOptions.map(d => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </div>
        <div className="admin-toolbar-group" style={{ gap: '0.5rem' }}>
          <span className={`badge ${STATUS_COLORS.WAITING}`}>Waiting: {totals.waiting}</span>
          <span className={`badge ${STATUS_COLORS.OFFERED}`}>Offered: {totals.offered}</span>
          <span className={`badge ${STATUS_COLORS.BOOKED}`}>Converted: {totals.booked}</span>
          <span className={`badge ${STATUS_COLORS.EXPIRED}`}>Expired: {totals.expired}</span>
          <span className={`badge ${STATUS_COLORS.CANCELLED}`}>Cancelled: {totals.cancelled}</span>
        </div>
      </div>

      {loading ? (
        <div className="admin-loading">Loading waitlist...</div>
      ) : entries.length === 0 ? (
        <div className="admin-empty-state">
          <FiUsers size={48} />
          <h3>No waitlist entries</h3>
          <p>No customers are on the waitlist for {selectedDate}.</p>
        </div>
      ) : (
        <div className="waitlist-sections">
          {statusOrder.map(status => {
            const group = grouped[status];
            if (!group || group.length === 0) return null;
            const isActive = status === 'WAITING' || status === 'OFFERED';
            return (
              <div key={status} className="waitlist-status-group">
                <h3 className="waitlist-status-heading">
                  <span className={`badge ${STATUS_COLORS[status] || 'badge-secondary'}`}>{status}</span>
                  <span className="waitlist-status-count">{group.length}</span>
                </h3>
                <div className="adm-table-wrap">
                  <table className="adm-table">
                    <thead>
                      <tr>
                        <th>#</th>
                        <th>Customer</th>
                        <th>Event</th>
                        <th>Preferred Time</th>
                        <th>Duration</th>
                        <th>Guests</th>
                        <th><FiStar /> Priority</th>
                        <th>Joined</th>
                        {status === 'OFFERED' && <th>Offer Expires</th>}
                        {(status === 'CONVERTED' || status === 'BOOKED') && <th>Booking Ref</th>}
                        {isActive && <th>Actions</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {group.map((entry, idx) => (
                        <tr key={entry.id}>
                          <td>{entry.position || idx + 1}</td>
                          <td>
                            <div className="waitlist-customer">
                              <strong><FiUser /> {entry.customerName || 'Unknown'}</strong>
                              {entry.customerEmail && <small><FiMail /> {entry.customerEmail}</small>}
                              {entry.customerPhone && <small><FiPhone /> {entry.customerPhone}</small>}
                            </div>
                          </td>
                          <td>{entry.eventType?.name || '—'}</td>
                          <td><FiClock /> {fmtTime(entry.preferredStartTime)}</td>
                          <td>{entry.durationMinutes ? `${entry.durationMinutes}m` : '—'}</td>
                          <td>{entry.numberOfGuests}</td>
                          <td>
                            {status === 'WAITING' ? (
                              <select
                                className="admin-select"
                                style={{ fontSize: '0.8rem', padding: '0.25rem 0.4rem' }}
                                value={Number(entry.priority || 0)}
                                disabled={busyEntryId === entry.id}
                                onChange={(e) => handlePriorityChange(entry, e.target.value)}
                                title="Higher priority moves the entry up the queue"
                              >
                                {PRIORITY_PRESETS.some(p => p.value === Number(entry.priority || 0))
                                  ? null
                                  : <option value={Number(entry.priority || 0)}>Custom ({entry.priority})</option>}
                                {PRIORITY_PRESETS.map(p => (
                                  <option key={p.value} value={p.value}>{p.label}</option>
                                ))}
                              </select>
                            ) : (
                              <span className="badge badge-secondary" style={{ fontSize: '0.75rem' }}>
                                {priorityLabel(entry.priority)}
                              </span>
                            )}
                          </td>
                          <td>{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : '—'}</td>
                          {status === 'OFFERED' && (
                            <td>{entry.offerExpiresAt ? new Date(entry.offerExpiresAt).toLocaleString() : '—'}</td>
                          )}
                          {(status === 'CONVERTED' || status === 'BOOKED') && (
                            <td>
                              {entry.convertedBookingRef ? (
                                <span className="badge badge-success"><FiCheck /> {entry.convertedBookingRef}</span>
                              ) : '—'}
                            </td>
                          )}
                          {isActive && (
                            <td style={{ display: 'flex', gap: '0.4rem' }}>
                              {status === 'WAITING' && (
                                <button
                                  className="btn btn-sm btn-primary"
                                  onClick={() => handleOffer(entry)}
                                  disabled={busyEntryId === entry.id}
                                  title="Send a time-limited offer email to this customer"
                                >
                                  <FiSend /> {busyEntryId === entry.id ? '…' : 'Offer'}
                                </button>
                              )}
                              <button
                                className="btn btn-sm btn-danger"
                                onClick={() => handleCancel(entry)}
                                disabled={busyEntryId === entry.id}
                              >
                                <FiXCircle /> Cancel
                              </button>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
