import { useState, useEffect, useMemo } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';
import { FiCalendar, FiClock, FiUsers, FiUser, FiMail, FiPhone, FiCheck } from 'react-icons/fi';
import './AdminPages.css';

const fmtTime = (timeStr) => {
  if (!timeStr) return '--:--';
  const parts = String(timeStr).split(':');
  const h = parseInt(parts[0], 10);
  const m = parseInt(parts[1] || '0', 10);
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
};

const STATUS_COLORS = {
  WAITING: 'badge-warning',
  OFFERED: 'badge-info',
  CONVERTED: 'badge-success',
  EXPIRED: 'badge-secondary',
  CANCELLED: 'badge-danger',
};

export default function AdminWaitlist() {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
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

  const statusOrder = ['WAITING', 'OFFERED', 'CONVERTED', 'EXPIRED', 'CANCELLED'];

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiUsers /> Waitlist Management</h1>
          <p>View and manage waitlisted customers for each date.</p>
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
        <div className="admin-toolbar-group">
          <span className="admin-toolbar-count">
            {entries.length} {entries.length === 1 ? 'entry' : 'entries'}
          </span>
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
                        <th>Joined</th>
                        {status === 'OFFERED' && <th>Offer Expires</th>}
                        {status === 'CONVERTED' && <th>Booking Ref</th>}
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
                          <td>{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : '—'}</td>
                          {status === 'OFFERED' && (
                            <td>{entry.offerExpiresAt ? new Date(entry.offerExpiresAt).toLocaleString() : '—'}</td>
                          )}
                          {status === 'CONVERTED' && (
                            <td>
                              {entry.convertedBookingRef ? (
                                <span className="badge badge-success"><FiCheck /> {entry.convertedBookingRef}</span>
                              ) : '—'}
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
