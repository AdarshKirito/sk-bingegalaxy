import { useState, useEffect, useMemo } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import { format } from 'date-fns';
import { FiCalendar, FiChevronLeft, FiChevronRight, FiClock, FiSlash, FiTrash2 } from 'react-icons/fi';
import { venueToday } from '../utils/venueLocale';
import './AdminPages.css';

/** Format total minutes → "HH:MM" */
const fmtMin = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

// 30-min intervals: 00:00 (0) to 23:30 (1410) — full 24h
const ALL_TIMES = Array.from({ length: 48 }, (_, i) => i * 30);

/** "2026-07-10T14:30:00" → { date: '2026-07-10', hm: '14:30' } (venue wall-clock strings, no TZ math). */
const splitLocal = (dt) => {
  const s = String(dt || '');
  return { date: s.slice(0, 10), hm: s.slice(11, 16) };
};

/** True when a room block covers exactly [date 00:00, date+1 00:00) — a full-day block. */
const isFullDayRoomBlock = (b) => {
  const start = splitLocal(b.startAt);
  const end = splitLocal(b.endAt);
  if (start.hm !== '00:00' || end.hm !== '00:00') return false;
  const next = new Date(start.date + 'T00:00:00');
  next.setDate(next.getDate() + 1);
  return end.date === format(next, 'yyyy-MM-dd');
};

/** Every calendar date (yyyy-MM-dd) a room block touches. */
const roomBlockDates = (b) => {
  const start = splitLocal(b.startAt);
  const end = splitLocal(b.endAt);
  const out = [];
  const cur = new Date(start.date + 'T00:00:00');
  // endAt is exclusive: a block ending at exactly 00:00 does not touch that day.
  const last = new Date(end.date + 'T00:00:00');
  if (end.hm === '00:00') last.setDate(last.getDate() - 1);
  for (let i = 0; i < 60 && cur <= last; i++) {
    out.push(format(cur, 'yyyy-MM-dd'));
    cur.setDate(cur.getDate() + 1);
  }
  return out;
};

export default function AdminBlockedDates() {
  const confirm = useConfirm();
  const [blockedDates, setBlockedDates] = useState([]);
  const [blockedSlots, setBlockedSlots] = useState([]);
  const [roomBlocks, setRoomBlocks] = useState([]);
  const [rooms, setRooms] = useState([]);
  const [tab, setTab] = useState('calendar');
  const [loading, setLoading] = useState(true);

  // scope: '' = entire venue → availability-service; a room id → booking-service room block.
  const [dateForm, setDateForm] = useState({ date: '', reason: '', scope: '' });
  const [slotForm, setSlotForm] = useState({ date: '', startMinute: '', endMinute: '', reason: '', scope: '' });
  const [saving, setSaving] = useState(false);

  // Calendar state — anchored to the VENUE's calendar, not the browser's.
  const vToday = venueToday(); // yyyy-MM-dd in the venue's timezone
  const [calMonth, setCalMonth] = useState(() => vToday.slice(0, 7)); // yyyy-MM
  const [selectedDay, setSelectedDay] = useState(vToday);

  const roomName = useMemo(() => {
    const map = {};
    rooms.forEach((r) => { map[r.id] = r.name; });
    return (id) => map[id] || `Room #${id}`;
  }, [rooms]);

  // Compute available start times (filter past for today, venue-local)
  const startOptions = useMemo(() => {
    if (!slotForm.date) return ALL_TIMES;
    if (slotForm.date !== vToday) return ALL_TIMES;
    const now = new Date().getHours() * 60 + new Date().getMinutes();
    return ALL_TIMES.filter(m => m > now);
  }, [slotForm.date, vToday]);

  const endOptions = useMemo(() => {
    const start = Number(slotForm.startMinute);
    if (!start && start !== 0) return [];
    return ALL_TIMES.filter(m => m > start).concat([1440]).filter((m, i, a) => a.indexOf(m) === i);
  }, [slotForm.startMinute]);

  useEffect(() => {
    if (startOptions.length > 0) {
      const cur = Number(slotForm.startMinute);
      if (!slotForm.startMinute || !startOptions.includes(cur)) {
        setSlotForm(f => ({ ...f, startMinute: String(startOptions[0]), endMinute: '' }));
      }
    }
  }, [startOptions]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (endOptions.length > 0) {
      const cur = Number(slotForm.endMinute);
      if (!slotForm.endMinute || !endOptions.includes(cur)) {
        setSlotForm(f => ({ ...f, endMinute: String(endOptions[0]) }));
      }
    }
  }, [endOptions]); // eslint-disable-line react-hooks/exhaustive-deps

  const fetchData = () => {
    setLoading(true);
    Promise.all([
      adminService.getBlockedDates(),
      adminService.getBlockedSlots(),
      adminService.listAllRoomBlocks().catch(() => ({ data: { data: [] } })),
      adminService.getVenueRooms().catch(() => ({ data: { data: [] } })),
    ])
      .then(([dRes, sRes, rbRes, roomsRes]) => {
        setBlockedDates(toArray(dRes.data?.data));
        setBlockedSlots(toArray(sRes.data?.data));
        setRoomBlocks(toArray(rbRes.data?.data));
        setRooms(toArray(roomsRes.data?.data).filter((r) => r.active !== false));
      })
      .catch(() => toast.error('Failed to load blocked dates/slots.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  // ── Create ─────────────────────────────────────────────────

  // Blocks stop NEW bookings only — existing ones survive and must be
  // rescheduled by hand, so the server reports how many overlap.
  const warnAffected = (res) => {
    const n = Number(res?.data?.data?.affectedBookings || 0);
    if (n > 0) {
      toast.warn(`⚠ ${n} existing booking${n > 1 ? 's' : ''} overlap${n > 1 ? '' : 's'} this window — they are NOT cancelled. Reschedule them from Bookings.`, { autoClose: 10000 });
    }
  };

  const handleBlockDate = async (e) => {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    try {
      if (dateForm.scope) {
        // Room-scoped: a full-day maintenance/hold window on that room —
        // enforced in real time by the room picker and every booking path.
        const next = new Date(dateForm.date + 'T00:00:00');
        next.setDate(next.getDate() + 1);
        const res = await adminService.createRoomBlock(Number(dateForm.scope), {
          startAt: `${dateForm.date}T00:00:00`,
          endAt: `${format(next, 'yyyy-MM-dd')}T00:00:00`,
          reason: dateForm.reason,
        });
        toast.success(`${roomName(Number(dateForm.scope))} blocked for ${dateForm.date}`);
        warnAffected(res);
      } else {
        await adminService.blockDate({ date: dateForm.date, reason: dateForm.reason });
        toast.success('Date blocked for the entire venue');
      }
      setDateForm({ date: '', reason: '', scope: '' });
      fetchData();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed');
    } finally {
      setSaving(false);
    }
  };

  const handleBlockSlot = async (e) => {
    e.preventDefault();
    if (saving) return;
    const start = Number(slotForm.startMinute);
    const end = Number(slotForm.endMinute);
    if (!slotForm.date) { toast.error('Please select a date.'); return; }
    if (end <= start) { toast.error('End time must be after start time.'); return; }
    setSaving(true);
    try {
      if (slotForm.scope) {
        const endsAtMidnight = end >= 1440;
        let endDate = slotForm.date;
        if (endsAtMidnight) {
          const next = new Date(slotForm.date + 'T00:00:00');
          next.setDate(next.getDate() + 1);
          endDate = format(next, 'yyyy-MM-dd');
        }
        const res = await adminService.createRoomBlock(Number(slotForm.scope), {
          startAt: `${slotForm.date}T${fmtMin(start)}:00`,
          endAt: `${endDate}T${endsAtMidnight ? '00:00' : fmtMin(end)}:00`,
          reason: slotForm.reason,
        });
        toast.success(`${roomName(Number(slotForm.scope))} blocked ${fmtMin(start)} – ${fmtMin(end)} on ${slotForm.date}`);
        warnAffected(res);
      } else {
        await adminService.blockSlot({
          date: slotForm.date,
          startMinute: start,
          endMinute: Math.min(end, 1440),
          reason: slotForm.reason,
        });
        toast.success(`Blocked ${fmtMin(start)} – ${fmtMin(Math.min(end, 1440))} on ${slotForm.date} (entire venue)`);
      }
      setSlotForm({ date: '', startMinute: '', endMinute: '', reason: '', scope: '' });
      fetchData();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to block slot.');
    } finally {
      setSaving(false);
    }
  };

  // ── Remove ─────────────────────────────────────────────────

  const handleUnblockDate = async (d) => {
    const ok = await confirm({
      title: `Unblock ${d.date}?`,
      message: 'The full date will become available for bookings again.',
      confirmLabel: 'Unblock date',
      variant: 'primary',
    });
    if (!ok) return;
    try {
      await adminService.unblockDateById(d.id);
      toast.success('Date unblocked');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  const handleUnblockSlot = async (s) => {
    const ok = await confirm({
      title: `Unblock ${fmtMin(s.startMinute)} slot on ${s.date}?`,
      message: 'The slot will become bookable again from this moment. Pending bookings (if any) are unaffected.',
      confirmLabel: 'Unblock slot',
      variant: 'primary',
    });
    if (!ok) return;
    try {
      await adminService.unblockSlotById(s.id);
      toast.success('Slot unblocked');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  const handleDeleteRoomBlock = async (b) => {
    const ok = await confirm({
      title: `Remove block on ${roomName(b.roomId)}?`,
      message: 'The room becomes bookable for this window again.',
      confirmLabel: 'Remove block',
      variant: 'primary',
    });
    if (!ok) return;
    try {
      await adminService.deleteRoomBlock(b.id);
      toast.success('Room block removed');
      fetchData();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed');
    }
  };

  // ── Calendar derivation ─────────────────────────────────────

  const byDay = useMemo(() => {
    const map = {};
    const push = (day, entry) => { (map[day] = map[day] || []).push(entry); };
    blockedDates.forEach((d) => push(d.date, { kind: 'venue-date', data: d }));
    blockedSlots.forEach((s) => push(s.date, { kind: 'venue-slot', data: s }));
    roomBlocks.forEach((b) => roomBlockDates(b).forEach((day) => push(day, { kind: 'room', data: b })));
    return map;
  }, [blockedDates, blockedSlots, roomBlocks]);

  const calCells = useMemo(() => {
    const [y, m] = calMonth.split('-').map(Number);
    const first = new Date(y, m - 1, 1);
    const startPad = (first.getDay() + 6) % 7; // Monday-first grid
    const daysInMonth = new Date(y, m, 0).getDate();
    const cells = [];
    for (let i = 0; i < startPad; i++) cells.push(null);
    for (let d = 1; d <= daysInMonth; d++) {
      cells.push(`${calMonth}-${String(d).padStart(2, '0')}`);
    }
    return cells;
  }, [calMonth]);

  const shiftMonth = (delta) => {
    const [y, m] = calMonth.split('-').map(Number);
    const d = new Date(y, m - 1 + delta, 1);
    setCalMonth(format(d, 'yyyy-MM'));
  };

  const dayEntries = byDay[selectedDay] || [];

  const scopePicker = (value, onChange) => (
    <div className="input-group">
      <label>Applies to</label>
      <select value={value} onChange={onChange}>
        <option value="">Entire venue (all rooms & events)</option>
        {rooms.map((r) => <option key={r.id} value={r.id}>Room: {r.name}</option>)}
      </select>
      <span className="adm-hint">
        Pick a room to block only that room — customers and admins stop seeing it for the
        blocked dates/times immediately. Leave as “Entire venue” to close the whole venue.
      </span>
    </div>
  );

  const renderEntry = (e, i) => {
    if (e.kind === 'venue-date') {
      return (
        <div key={`vd-${e.data.id}-${i}`} className="adm-item">
          <div className="adm-item-body">
            <span className="adm-item-name">🏢 Entire venue — full day</span>
            <span className="adm-item-desc">{e.data.reason || 'No note'}</span>
          </div>
          <div className="adm-item-footer">
            <button className="btn btn-danger btn-sm" onClick={() => handleUnblockDate(e.data)}>
              <FiTrash2 style={{ marginRight: 3 }} /> Remove
            </button>
          </div>
        </div>
      );
    }
    if (e.kind === 'venue-slot') {
      const s = e.data;
      return (
        <div key={`vs-${s.id}-${i}`} className="adm-item">
          <div className="adm-item-body">
            <span className="adm-item-name">🏢 Entire venue — {fmtMin(s.startMinute)} to {fmtMin(s.endMinute)}</span>
            <span className="adm-item-desc">{s.reason || 'No note'}</span>
          </div>
          <div className="adm-item-footer">
            <button className="btn btn-danger btn-sm" onClick={() => handleUnblockSlot(s)}>
              <FiTrash2 style={{ marginRight: 3 }} /> Remove
            </button>
          </div>
        </div>
      );
    }
    const b = e.data;
    const st = splitLocal(b.startAt);
    const en = splitLocal(b.endAt);
    return (
      <div key={`rb-${b.id}-${i}`} className="adm-item">
        <div className="adm-item-body">
          <span className="adm-item-name">
            🚪 {roomName(b.roomId)} — {isFullDayRoomBlock(b)
              ? 'full day'
              : `${st.date === selectedDay ? st.hm : `${st.date} ${st.hm}`} to ${en.date === selectedDay ? en.hm : `${en.date} ${en.hm}`}`}
          </span>
          <span className="adm-item-desc">{b.reason || 'No note'}</span>
        </div>
        <div className="adm-item-footer">
          <button className="btn btn-danger btn-sm" onClick={() => handleDeleteRoomBlock(b)}>
            <FiTrash2 style={{ marginRight: 3 }} /> Remove
          </button>
        </div>
      </div>
    );
  };

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiSlash /> Availability</span>
          <h1>Block Dates & Slots</h1>
          <p>Close the whole venue or a single room for full dates or specific times. Changes apply to customer and admin booking instantly.</p>
        </div>
      </div>

      <div className="adm-tabs">
        <button className={`adm-tab${tab === 'calendar' ? ' active' : ''}`}
          onClick={() => setTab('calendar')}><FiCalendar style={{ marginRight: 4, verticalAlign: -2 }} />Calendar</button>
        <button className={`adm-tab${tab === 'dates' ? ' active' : ''}`}
          onClick={() => setTab('dates')}><FiCalendar style={{ marginRight: 4, verticalAlign: -2 }} />Blocked Dates</button>
        <button className={`adm-tab${tab === 'slots' ? ' active' : ''}`}
          onClick={() => setTab('slots')}><FiClock style={{ marginRight: 4, verticalAlign: -2 }} />Blocked Slots</button>
      </div>

      {tab === 'calendar' && (
        loading ? <div className="loading"><div className="spinner"></div></div> : (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(300px, 1.2fr) minmax(260px, 1fr)', gap: '1rem', alignItems: 'start' }}>
          <div className="adm-form" style={{ padding: '0.9rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.6rem' }}>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => shiftMonth(-1)} aria-label="Previous month"><FiChevronLeft /></button>
              <strong>{format(new Date(calMonth + '-01T00:00:00'), 'MMMM yyyy')}</strong>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => shiftMonth(1)} aria-label="Next month"><FiChevronRight /></button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 3, fontSize: '0.72rem', textAlign: 'center', color: 'var(--text-muted)', marginBottom: 3 }}>
              {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => <span key={d}>{d}</span>)}
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 3 }}>
              {calCells.map((day, i) => {
                if (!day) return <span key={`pad-${i}`} />;
                const entries = byDay[day] || [];
                const venueClosed = entries.some((e) => e.kind === 'venue-date');
                const slotCount = entries.filter((e) => e.kind === 'venue-slot').length;
                const roomCount = entries.filter((e) => e.kind === 'room').length;
                const isSel = day === selectedDay;
                const isToday = day === vToday;
                return (
                  <button
                    key={day}
                    type="button"
                    onClick={() => setSelectedDay(day)}
                    title={venueClosed ? 'Venue closed' : (slotCount + roomCount > 0 ? `${slotCount} venue slot block(s), ${roomCount} room block(s)` : '')}
                    style={{
                      minHeight: 52, padding: '4px 2px', borderRadius: 8, cursor: 'pointer',
                      border: isSel ? '2px solid var(--primary)' : '1px solid var(--border, rgba(128,128,128,0.25))',
                      background: venueClosed ? 'rgba(239,68,68,0.16)' : 'var(--bg-input, transparent)',
                      color: 'inherit', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                      outline: isToday ? '1px dashed var(--primary)' : 'none', outlineOffset: 1,
                    }}
                  >
                    <span style={{ fontSize: '0.8rem', fontWeight: isToday ? 800 : 500 }}>{Number(day.slice(8))}</span>
                    <span style={{ display: 'flex', gap: 2, flexWrap: 'wrap', justifyContent: 'center' }}>
                      {venueClosed && <span title="Venue closed" style={{ width: 7, height: 7, borderRadius: '50%', background: '#ef4444' }} />}
                      {slotCount > 0 && <span title="Venue time blocks" style={{ width: 7, height: 7, borderRadius: '50%', background: '#f59e0b' }} />}
                      {roomCount > 0 && <span title="Room blocks" style={{ width: 7, height: 7, borderRadius: '50%', background: '#8b5cf6' }} />}
                    </span>
                  </button>
                );
              })}
            </div>
            <div style={{ display: 'flex', gap: '0.9rem', marginTop: '0.6rem', fontSize: '0.72rem', color: 'var(--text-muted)', flexWrap: 'wrap' }}>
              <span><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: '#ef4444', marginRight: 4 }} />Venue closed</span>
              <span><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: '#f59e0b', marginRight: 4 }} />Venue time block</span>
              <span><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: '#8b5cf6', marginRight: 4 }} />Room block</span>
            </div>
          </div>

          <div>
            <div className="adm-form" style={{ padding: '0.9rem', marginBottom: '0.75rem' }}>
              <h3 style={{ marginTop: 0 }}>{format(new Date(selectedDay + 'T00:00:00'), 'EEEE, MMM dd, yyyy')}</h3>
              {dayEntries.length === 0 ? (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>Nothing blocked this day.</p>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  {dayEntries.map(renderEntry)}
                </div>
              )}
              {selectedDay >= vToday && (
                <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.75rem', flexWrap: 'wrap' }}>
                  <button type="button" className="btn btn-secondary btn-sm"
                    onClick={() => { setDateForm((f) => ({ ...f, date: selectedDay })); setTab('dates'); }}>
                    <FiCalendar style={{ marginRight: 3 }} /> Block this day
                  </button>
                  <button type="button" className="btn btn-secondary btn-sm"
                    onClick={() => { setSlotForm((f) => ({ ...f, date: selectedDay })); setTab('slots'); }}>
                    <FiClock style={{ marginRight: 3 }} /> Block a time range
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
        )
      )}

      {tab === 'dates' && (
        <>
          <div className="adm-form" style={{ maxWidth: '520px' }}>
            <h3><FiCalendar style={{ marginRight: 6, verticalAlign: -2 }} />Block a Date</h3>
            <form onSubmit={handleBlockDate}>
              {scopePicker(dateForm.scope, (e) => setDateForm({ ...dateForm, scope: e.target.value }))}
              <div className="input-group">
                <label>Date</label>
                <input type="date" required value={dateForm.date}
                  min={vToday}
                  onChange={(e) => setDateForm({ ...dateForm, date: e.target.value })} />
              </div>
              <div className="input-group">
                <label>Note / reason</label>
                <input value={dateForm.reason} onChange={(e) => setDateForm({ ...dateForm, reason: e.target.value })}
                  placeholder="Maintenance, holiday, private event…" />
              </div>
              <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>{saving ? 'Blocking...' : 'Block Date'}</button>
            </form>
          </div>

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            blockedDates.length === 0 && roomBlocks.filter(isFullDayRoomBlock).length === 0 ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiCalendar /></span>
                <h3>No blocked dates</h3>
                <p>All dates are currently available for bookings.</p>
              </div>
            ) : (
              <div className="adm-grid-3">
                {blockedDates.map(d => (
                  <div key={`vd-${d.id}`} className="adm-item">
                    <div className="adm-item-body">
                      <span className="adm-item-name">
                        {format(new Date(d.date + 'T00:00:00'), 'MMM dd, yyyy (EEE)')}
                      </span>
                      <span className="adm-badge adm-badge-inactive" style={{ alignSelf: 'flex-start' }}>Entire venue</span>
                      <span className="adm-item-desc">{d.reason || 'No note'}</span>
                    </div>
                    <div className="adm-item-footer">
                      <button className="btn btn-danger btn-sm" onClick={() => handleUnblockDate(d)}>
                        <FiTrash2 style={{ marginRight: 3 }} /> Remove
                      </button>
                    </div>
                  </div>
                ))}
                {roomBlocks.filter(isFullDayRoomBlock).map(b => (
                  <div key={`rb-${b.id}`} className="adm-item">
                    <div className="adm-item-body">
                      <span className="adm-item-name">
                        {format(new Date(splitLocal(b.startAt).date + 'T00:00:00'), 'MMM dd, yyyy (EEE)')}
                      </span>
                      <span className="adm-badge adm-badge-info" style={{ alignSelf: 'flex-start' }}>{roomName(b.roomId)}</span>
                      <span className="adm-item-desc">{b.reason || 'No note'}</span>
                    </div>
                    <div className="adm-item-footer">
                      <button className="btn btn-danger btn-sm" onClick={() => handleDeleteRoomBlock(b)}>
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
              {scopePicker(slotForm.scope, (e) => setSlotForm({ ...slotForm, scope: e.target.value }))}
              <div className="input-group">
                <label>Date</label>
                <input type="date" required value={slotForm.date}
                  min={vToday}
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
                      <option key={m} value={m}>{m >= 1440 ? '24:00' : fmtMin(m)}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="input-group">
                <label>Note / reason</label>
                <input value={slotForm.reason} onChange={(e) => setSlotForm({ ...slotForm, reason: e.target.value })}
                  placeholder="Cleaning, private event, etc." />
              </div>
              <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>{saving ? 'Blocking...' : 'Block Slot'}</button>
            </form>
          </div>

          {loading ? <div className="loading"><div className="spinner"></div></div> : (
            blockedSlots.length === 0 && roomBlocks.filter(b => !isFullDayRoomBlock(b)).length === 0 ? (
              <div className="adm-empty">
                <span className="adm-empty-icon"><FiClock /></span>
                <h3>No blocked time slots</h3>
                <p>All time slots are currently available for bookings.</p>
              </div>
            ) : (
              <div className="adm-grid-3">
                {blockedSlots.map(s => {
                  const startM = s.startMinute != null ? s.startMinute : 0;
                  const endM = s.endMinute != null ? s.endMinute : 0;
                  const durMin = endM - startM;
                  return (
                    <div key={`vs-${s.id}`} className="adm-item">
                      <div className="adm-item-body">
                        <span className="adm-item-name">
                          {format(new Date(s.date + 'T00:00:00'), 'MMM dd, EEE')}
                        </span>
                        <span style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--primary-text)' }}>
                          {fmtMin(startM)} – {fmtMin(endM)}
                        </span>
                        <span className="adm-badge adm-badge-inactive" style={{ alignSelf: 'flex-start' }}>Entire venue</span>
                        <span className="adm-hint">
                          {durMin >= 60 ? `${Math.floor(durMin / 60)}h${durMin % 60 ? ` ${durMin % 60}m` : ''}` : `${durMin}m`}
                          {s.reason ? ` · ${s.reason}` : ''}
                        </span>
                      </div>
                      <div className="adm-item-footer">
                        <button className="btn btn-danger btn-sm" onClick={() => handleUnblockSlot(s)}>
                          <FiTrash2 style={{ marginRight: 3 }} /> Remove
                        </button>
                      </div>
                    </div>
                  );
                })}
                {roomBlocks.filter(b => !isFullDayRoomBlock(b)).map(b => {
                  const st = splitLocal(b.startAt);
                  const en = splitLocal(b.endAt);
                  return (
                    <div key={`rb-${b.id}`} className="adm-item">
                      <div className="adm-item-body">
                        <span className="adm-item-name">
                          {format(new Date(st.date + 'T00:00:00'), 'MMM dd, EEE')}
                        </span>
                        <span style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--primary-text)' }}>
                          {st.hm} – {en.date !== st.date ? `${en.date} ` : ''}{en.hm}
                        </span>
                        <span className="adm-badge adm-badge-info" style={{ alignSelf: 'flex-start' }}>{roomName(b.roomId)}</span>
                        <span className="adm-hint">{b.reason || 'No note'}</span>
                      </div>
                      <div className="adm-item-footer">
                        <button className="btn btn-danger btn-sm" onClick={() => handleDeleteRoomBlock(b)}>
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
