import { useState, useEffect } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { format, addDays } from 'date-fns';

export default function AdminBlockedDates() {
  const [blockedDates, setBlockedDates] = useState([]);
  const [blockedSlots, setBlockedSlots] = useState([]);
  const [tab, setTab] = useState('dates');
  const [loading, setLoading] = useState(true);

  const [dateForm, setDateForm] = useState({ date: '', reason: '' });
  const [slotForm, setSlotForm] = useState({ date: '', startHour: '10', endHour: '11', reason: '' });

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
    if (Number(slotForm.startHour) >= Number(slotForm.endHour)) {
      toast.error('End hour must be after start hour.'); return;
    }
    try {
      await adminService.blockSlot({
        date: slotForm.date,
        startHour: Number(slotForm.startHour),
        endHour: Number(slotForm.endHour),
        reason: slotForm.reason,
      });
      toast.success('Slot blocked');
      setSlotForm({ date: '', startHour: '10', endHour: '11', reason: '' });
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  const handleUnblockSlot = async (date, startHour) => {
    try {
      await adminService.unblockSlot(date, startHour);
      toast.success('Slot unblocked');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  return (
    <div className="container">
      <div className="page-header">
        <h1>Block Dates & Slots</h1>
        <p>Manage theater availability</p>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1.5rem' }}>
        <button className={`btn ${tab === 'dates' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('dates')}>Blocked Dates</button>
        <button className={`btn ${tab === 'slots' ? 'btn-primary' : 'btn-secondary'} btn-sm`}
          onClick={() => setTab('slots')}>Blocked Slots</button>
      </div>

      {tab === 'dates' && (
        <>
          <form onSubmit={handleBlockDate} className="card" style={{ marginBottom: '1.5rem', maxWidth: '500px' }}>
            <h3 style={{ marginBottom: '1rem' }}>Block a Date</h3>
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

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            <div className="grid-3">
              {blockedDates.map(d => (
                <div key={d.id} className="card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <p style={{ fontWeight: 600 }}>{d.date}</p>
                      <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{d.reason || 'No reason'}</p>
                    </div>
                    <button className="btn btn-danger btn-sm" onClick={() => handleUnblockDate(d.date)}>Remove</button>
                  </div>
                </div>
              ))}
              {blockedDates.length === 0 && <p style={{ color: 'var(--text-muted)' }}>No blocked dates</p>}
            </div>
          )}
        </>
      )}

      {tab === 'slots' && (
        <>
          <form onSubmit={handleBlockSlot} className="card" style={{ marginBottom: '1.5rem', maxWidth: '500px' }}>
            <h3 style={{ marginBottom: '1rem' }}>Block a Slot</h3>
            <div className="input-group">
              <label>Date</label>
              <input type="date" required value={slotForm.date}
                min={format(new Date(), 'yyyy-MM-dd')}
                onChange={(e) => setSlotForm({ ...slotForm, date: e.target.value })} />
            </div>
            <div className="grid-2">
              <div className="input-group">
                <label>Start Hour</label>
                <select value={slotForm.startHour} onChange={(e) => setSlotForm({ ...slotForm, startHour: e.target.value })}>
                  {Array.from({ length: 15 }, (_, i) => i + 8).map(h => (
                    <option key={h} value={h}>{h}:00</option>
                  ))}
                </select>
              </div>
              <div className="input-group">
                <label>End Hour</label>
                <select value={slotForm.endHour} onChange={(e) => setSlotForm({ ...slotForm, endHour: e.target.value })}>
                  {Array.from({ length: 15 }, (_, i) => i + 9).map(h => (
                    <option key={h} value={h}>{h}:00</option>
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

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            <div className="grid-3">
              {blockedSlots.map(s => (
                <div key={s.id} className="card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <p style={{ fontWeight: 600 }}>{s.date} • {s.startHour}:00 - {s.endHour}:00</p>
                      <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{s.reason || 'No reason'}</p>
                    </div>
                    <button className="btn btn-danger btn-sm" onClick={() => handleUnblockSlot(s.date, s.startHour)}>Remove</button>
                  </div>
                </div>
              ))}
              {blockedSlots.length === 0 && <p style={{ color: 'var(--text-muted)' }}>No blocked slots</p>}
            </div>
          )}
        </>
      )}
    </div>
  );
}
