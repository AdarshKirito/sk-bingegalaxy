import { useState, useEffect, useMemo } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { format } from 'date-fns';
import { FiCalendar, FiClock, FiSlash, FiTrash2 } from 'react-icons/fi';
import './AdminPages.css';

/** Format total minutes → "HH:MM" */
const fmtMin = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

/**
 * Display helper: old data stored hours (0-23), new data stores minutes (0-1440).
 * If value < 48, treat it as an hour and multiply by 60.
 */
const toMinutes = (v) => (v != null && v < 48) ? v * 60 : (v || 0);

// 30-min intervals: 08:00 (480) to 22:30 (1350)
const ALL_TIMES = Array.from({ length: 30 }, (_, i) => 480 + i * 30);

export default function AdminBlockedDates() {
  const [blockedDates, setBlockedDates] = useState([]);
  const [blockedSlots, setBlockedSlots] = useState([]);
  const [tab, setTab] = useState('dates');
  const [loading, setLoading] = useState(true);

  const [dateForm, setDateForm] = useState({ date: '', reason: '' });
  const [slotForm, setSlotForm] = useState({ date: '', startMinute: '', endMinute: '', reason: '' });

  // Compute available start times (filter past for today)
  const startOptions = useMemo(() => {
    if (!slotForm.date) return ALL_TIMES;
    const isToday = slotForm.date === format(new Date(), 'yyyy-MM-dd');
    if (!isToday) return ALL_TIMES;
    const now = new Date().getHours() * 60 + new Date().getMinutes();
    return ALL_TIMES.filter(m => m > now);
  }, [slotForm.date]);

  // Compute available end times (must be after selected start, at least 30 min gap)
  const endOptions = useMemo(() => {
    const start = Number(slotForm.startMinute);
    if (!start) return [];
    return ALL_TIMES.filter(m => m > start);
  }, [slotForm.startMinute]);

  // Auto-sync startMinute when date changes or options change
  useEffect(() => {
    if (startOptions.length > 0) {
      const cur = Number(slotForm.startMinute);
      if (!cur || !startOptions.includes(cur)) {
        const now = new Date().getHours() * 60 + new Date().getMinutes();
        const nearest = startOptions.find(m => m >= now) || startOptions[startOptions.length - 1];
        setSlotForm(f => ({ ...f, startMinute: String(nearest), endMinute: '' }));
      }
    }
  }, [startOptions]);

  // Auto-sync endMinute when startMinute changes or endOptions change
  useEffect(() => {
    if (endOptions.length > 0) {
      const cur = Number(slotForm.endMinute);
      if (!cur || !endOptions.includes(cur)) {
        setSlotForm(f => ({ ...f, endMinute: String(endOptions[0]) }));
      }
    }
  }, [endOptions]);

  const fetchData = () => {
    setLoading(true);
    Promise.all([adminService.getBlockedDates(), adminService.getBlockedSlots()])
      .then(([dRes, sRes]) => {
        setBlockedDates(dRes.data.data || []);
        setBlockedSlots(sRes.data.data || []);
      })
      .catch(() => toast.error('Failed to load blocked dates/slots.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleBlockDate = async (e) => {
    e.preventDefault();
    try {
      await adminService.blockDate({ date: dateForm.date, reason: dateForm.reason });
      toast.success('Date blocked');
      setDateForm({ date: '', reason: '' });
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  const handleUnblockDate = async (date) => {
    if (!confirm(`Unblock ${date}?`)) return;
    try {
      await adminService.unblockDate(date);
      toast.success('Date unblocked');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  const handleBlockSlot = async (e) => {
    e.preventDefault();
    const start = Number(slotForm.startMinute);
    const end = Number(slotForm.endMinute);
    if (!slotForm.date) { toast.error('Please select a date.'); return; }
    if (end <= start) { toast.error('End time must be after start time.'); return; }
    try {
      await adminService.blockSlot({
        date: slotForm.date,
        startHour: start,
        endHour: end,
        reason: slotForm.reason,
      });
      toast.success(`Blocked ${fmtMin(start)} – ${fmtMin(end)} on ${slotForm.date}`);
      setSlotForm({ date: '', startMinute: '', endMinute: '', reason: '' });
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to block slot.');
    }
  };

  const handleUnblockSlot = async (date, startHour) => {
    if (!window.confirm(`Unblock the ${startHour}:00 slot on ${date}?`)) return;
    try {
      await adminService.unblockSlot(date, startHour);
      toast.success('Slot unblocked');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiSlash /> Availability</span>
          <h1>Block Dates & Slots</h1>
          <p>Manage theater availability by blocking full dates or specific time slots.</p>
        </div>
      </div>

      <div className="adm-tabs">
        <button className={`adm-tab${tab === 'dates' ? ' active' : ''}`}
          onClick={() => setTab('dates')}><FiCalendar style={{ marginRight: 4, verticalAlign: -2 }} />Blocked Dates</button>
        <button className={`adm-tab${tab === 'slots' ? ' active' : ''}`}
          onClick={() => setTab('slots')}><FiClock style={{ marginRight: 4, verticalAlign: -2 }} />Blocked Slots</button>
      </div>

      {tab === 'dates' && (
        <>
          <div className="adm-form" style={{ maxWidth: '520px' }}>
            <h3><FiCalendar style={{ marginRight: 6, verticalAlign: -2 }} />Block a Date</h3>
            <form onSubmit={handleBlockDate}>
              <div className="input-group">
                <label>Date</label>
                <input type="date" required value={dateForm.date}
                  min={format(new Date(), 'yyyy-MM-dd')}
                  onChange={(e) => setDateForm({ ...dateForm, date: e.target.value })} />
              </div>
              <div className="input-group">
                <label>Reason</label>
                <input value={dateForm.reason} onChange={(e) => setDateForm({ ...dateForm, reason: e.target.value })}
                  placeholder="Maintenance, holiday, etc." />
              </div>
              <button type="submit" className="btn btn-primary btn-sm">Block Date</button>
            </form>
          </div>

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            blockedDates.length === 0 ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiCalendar /></span>
                <h3>No blocked dates</h3>
                <p>All dates are currently available for bookings.</p>
              </div>
            ) : (
              <div className="adm-grid-3">
                {blockedDates.map(d => (
                  <div key={d.id} className="adm-item">
                    <div className="adm-item-body">
                      <span className="adm-item-name">
                        {format(new Date(d.date + 'T00:00:00'), 'MMM dd, yyyy (EEE)')}
                      </span>
                      <span className="adm-item-desc">{d.reason || 'No reason provided'}</span>
                    </div>
                    <div className="adm-item-footer">
                      <button className="btn btn-danger btn-sm" onClick={() => handleUnblockDate(d.date)}>
                        <FiTrash2 style={{ marginRight: 3 }} /> Remove
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )
          )}
        </>
      )}

      {tab === 'slots' && (
        <>
          <div className="adm-form" style={{ maxWidth: '520px' }}>
            <h3><FiClock style={{ marginRight: 6, verticalAlign: -2 }} />Block a Slot</h3>
            <form onSubmit={handleBlockSlot}>
              <div className="input-group">
                <label>Date</label>
                <input type="date" required value={slotForm.date}
                  min={format(new Date(), 'yyyy-MM-dd')}
                  onChange={(e) => setSlotForm({ ...slotForm, date: e.target.value })} />
              </div>
              <div className="grid-2">
                <div className="input-group">
                  <label>Start Time</label>
                  <select value={slotForm.startMinute} onChange={(e) => {
                    setSlotForm(f => ({ ...f, startMinute: e.target.value, endMinute: '' }));
                  }}>
                    {startOptions.map(m => (
                      <option key={m} value={m}>{fmtMin(m)}</option>
                    ))}
                  </select>
                </div>
                <div className="input-group">
                  <label>End Time</label>
                  <select value={slotForm.endMinute} onChange={(e) => setSlotForm({ ...slotForm, endMinute: e.target.value })}>
                    {endOptions.map(m => (
                      <option key={m} value={m}>{fmtMin(m)}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="input-group">
                <label>Reason</label>
                <input value={slotForm.reason} onChange={(e) => setSlotForm({ ...slotForm, reason: e.target.value })}
                  placeholder="Cleaning, private event, etc." />
              </div>
              <button type="submit" className="btn btn-primary btn-sm">Block Slot</button>
            </form>
          </div>

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            blockedSlots.length === 0 ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiClock /></span>
                <h3>No blocked time slots</h3>
                <p>All time slots are currently available for bookings.</p>
              </div>
            ) : (
              <div className="adm-grid-3">
                {blockedSlots.map(s => {
                  const startM = toMinutes(s.startHour);
                  const endM = toMinutes(s.endHour);
                  const durMin = endM - startM;
                  return (
                    <div key={s.id} className="adm-item">
                      <div className="adm-item-body">
                        <span className="adm-item-name">
                          {format(new Date(s.date + 'T00:00:00'), 'MMM dd, EEE')}
                        </span>
                        <span style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--primary)' }}>
                          {fmtMin(startM)} – {fmtMin(endM)}
                        </span>
                        <span className="adm-hint">
                          {durMin >= 60 ? `${Math.floor(durMin / 60)}h${durMin % 60 ? ` ${durMin % 60}m` : ''}` : `${durMin}m`}
                          {s.reason ? ` · ${s.reason}` : ''}
                        </span>
                      </div>
                      <div className="adm-item-footer">
                        <button className="btn btn-danger btn-sm" onClick={() => handleUnblockSlot(s.date, s.startHour)}>
                          <FiTrash2 style={{ marginRight: 3 }} /> Remove
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )
          )}
        </>
      )}
    </div>
  );
}
