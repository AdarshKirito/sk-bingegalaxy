import { useEffect, useMemo, useState, useCallback } from 'react';
import { format } from 'date-fns';
import { availabilityService, bookingService, adminService } from '../../services/endpoints';

/**
 * Smart slot-suggestions panel. Surfaces alternatives when the user's
 * preferred date/time is unavailable:
 *
 *   • Nearest times on the same date (if any free)
 *   • Earliest free slot on the next 5 non-fully-blocked dates
 *   • Cheapest off-peak alternatives (no surge → lowest multiplier)
 *
 * All computation is client-side using data the wizard already loads
 * (availability[], surgeRules[]) plus on-demand per-date slot fetches.
 * Suggestions are de-duplicated and capped at 6 cards so the panel is
 * never overwhelming. Each card is a single click to apply.
 *
 * Props:
 *   desiredDate       — yyyy-MM-dd the user originally tried
 *   desiredStartMin   — minutes-from-midnight the user originally tried (number or '')
 *   durationMinutes   — booking duration the user picked
 *   availability      — DayAvailabilityDto[] (next ~30/60d)
 *   surgeRules        — active surge rules
 *   isAdmin           — pick correct booked-slots endpoint
 *   editBookingRef    — exclude the in-edit booking from "booked"
 *   onPick(d, m)      — apply suggestion (sets bookingDate + startTime)
 *   fmtTime, fmtDuration
 *   maxDates          — how many alternative dates to scan (default 5)
 */
export default function SlotSuggestionsPanel({
  desiredDate, desiredStartMin, durationMinutes,
  availability, surgeRules, isAdmin, editBookingRef,
  onPick, fmtTime, fmtDuration, maxDates = 5,
}) {
  const [perDate, setPerDate] = useState({}); // { [date]: { rawSlots, bookedSlots } }
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const desiredMin = Number.isFinite(Number(desiredStartMin)) && desiredStartMin !== ''
    ? Number(desiredStartMin) : null;
  const dur = Number(durationMinutes) || 0;

  // Pick the candidate dates: desiredDate first, then the next N non-fully-blocked.
  const candidateDates = useMemo(() => {
    const set = new Set();
    const ordered = [];
    if (desiredDate) { set.add(desiredDate); ordered.push(desiredDate); }
    const sorted = (availability || [])
      .filter(d => !d.fullyBlocked && d.date)
      .map(d => d.date)
      .sort();
    for (const d of sorted) {
      if (set.has(d)) continue;
      ordered.push(d);
      set.add(d);
      if (ordered.length >= maxDates + 1) break;
    }
    return ordered;
  }, [desiredDate, availability, maxDates]);

  const fetchDate = useCallback(async (date) => {
    const slotsApi = isAdmin ? adminService.getBookedSlots : bookingService.getBookedSlots;
    const [slotsRes, bookedRes] = await Promise.all([
      availabilityService.getSlots(date),
      slotsApi(date),
    ]);
    return {
      rawSlots: slotsRes.data?.data?.availableSlots || [],
      bookedSlots: bookedRes.data?.data || [],
    };
  }, [isAdmin]);

  useEffect(() => {
    let cancelled = false;
    if (!candidateDates.length || !dur) return undefined;
    setLoading(true);
    setError(null);
    Promise.all(candidateDates.map(async (d) => {
      try {
        const data = await fetchDate(d);
        return [d, data];
      } catch {
        return [d, { rawSlots: [], bookedSlots: [] }];
      }
    })).then(entries => {
      if (cancelled) return;
      const next = {};
      entries.forEach(([d, v]) => { next[d] = v; });
      setPerDate(next);
    }).catch(() => { if (!cancelled) setError('Could not load alternatives'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [candidateDates, dur, fetchDate]);

  // Replicate BookingWizard.durationSlots logic for an arbitrary date.
  const slotsForDate = useCallback((date) => {
    const entry = perDate[date];
    if (!entry) return [];
    const { rawSlots, bookedSlots } = entry;
    const bookedHalfHours = new Set();
    bookedSlots.forEach(bs => {
      if (editBookingRef && bs.bookingRef === editBookingRef) return;
      const start = bs.startMinute != null ? bs.startMinute : 0;
      const d = bs.durationMinutes != null ? bs.durationMinutes : ((bs.durationHours || 0) * 60);
      if (!d) return;
      for (let m = start; m < start + d; m += 30) bookedHalfHours.add(Math.floor(m / 30));
    });
    const today = format(new Date(), 'yyyy-MM-dd');
    const isToday = date === today;
    const nowMinutes = new Date().getHours() * 60 + new Date().getMinutes();
    const out = [];
    for (let startMin = 0; startMin + dur <= 24 * 60; startMin += 30) {
      if (isToday && startMin < nowMinutes + 15) continue;
      let ok = true;
      for (let m = startMin; m < startMin + dur; m += 30) {
        const slot = rawSlots.find(s => (s.startMinute != null ? s.startMinute : 0) === m);
        if (!slot || !slot.available || bookedHalfHours.has(Math.floor(m / 30))) { ok = false; break; }
      }
      if (ok) out.push(startMin);
    }
    return out;
  }, [perDate, editBookingRef, dur]);

  // Surge multiplier for a (date, startMin); 1.0 = no surge.
  const surgeFor = useCallback((date, startMin) => {
    if (!Array.isArray(surgeRules) || !surgeRules.length) return 1;
    const isoDow = (() => {
      const dow = new Date(date + 'T00:00:00').getDay();
      return dow === 0 ? 7 : dow;
    })();
    let best = 1;
    for (const r of surgeRules) {
      if (r.dayOfWeek && r.dayOfWeek !== isoDow) continue;
      if (startMin >= r.startMinute && startMin < r.endMinute) {
        const m = Number(r.multiplier) || 1;
        if (m > best) best = m;
      }
    }
    return best;
  }, [surgeRules]);

  // Build the ranked candidate list across all candidate dates.
  const suggestions = useMemo(() => {
    if (loading) return [];
    const all = [];
    const desiredDateNum = desiredDate ? Date.parse(desiredDate + 'T00:00:00') : null;
    candidateDates.forEach(date => {
      const slots = slotsForDate(date);
      if (!slots.length) return;
      // Per date, take up to 4 free slots ranked by proximity to desired time.
      const pickedOnDate = slots
        .map(m => ({ date, startMin: m }))
        .sort((a, b) => {
          if (desiredMin == null) return a.startMin - b.startMin;
          return Math.abs(a.startMin - desiredMin) - Math.abs(b.startMin - desiredMin);
        })
        .slice(0, 4);
      pickedOnDate.forEach(p => all.push(p));
    });
    // Score: cheaper first, then closer to (desiredDate, desiredMin).
    const scored = all.map(c => {
      const mult = surgeFor(c.date, c.startMin);
      const dateNum = Date.parse(c.date + 'T00:00:00');
      const dateDist = desiredDateNum != null && Number.isFinite(dateNum)
        ? Math.abs((dateNum - desiredDateNum) / 86400000) : 0;
      const timeDist = desiredMin != null ? Math.abs(c.startMin - desiredMin) / 30 : 0;
      // Multiplier dominates (×100), then proximity.
      const score = (mult - 1) * 100 + dateDist + (timeDist * 0.05);
      const isOffPeak = mult <= 1;
      const isSameDay = c.date === desiredDate;
      let kind;
      if (isSameDay && isOffPeak) kind = 'same-day-off-peak';
      else if (isSameDay)         kind = 'same-day';
      else if (isOffPeak)         kind = 'off-peak';
      else                        kind = 'alt-date';
      return { ...c, mult, score, kind };
    });
    scored.sort((a, b) => a.score - b.score);
    // De-duplicate (date, startMin) and cap at 6.
    const seen = new Set();
    const out = [];
    for (const s of scored) {
      const key = `${s.date}|${s.startMin}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(s);
      if (out.length >= 6) break;
    }
    return out;
  }, [loading, candidateDates, slotsForDate, surgeFor, desiredDate, desiredMin]);

  if (!dur) return null;

  return (
    <div className="slot-suggestions" role="region" aria-label="Smart slot suggestions" style={{
      marginTop: '0.85rem', padding: '0.9rem 1rem',
      borderRadius: '10px', border: '1px solid var(--border, #e5e7eb)',
      background: 'var(--surface, #fff)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Smart suggestions</strong>
        {loading && <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Searching alternatives…</span>}
      </div>
      {!loading && suggestions.length === 0 && (
        <p style={{ fontSize: '0.85rem', color: 'var(--text-muted, #666)', margin: 0 }}>
          {error
            ? error
            : 'No alternative slots found in the next few days. Try a shorter duration, or join the waitlist below.'}
        </p>
      )}
      {!loading && suggestions.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: '0.5rem' }}>
          {suggestions.map(s => {
            const dateLabel = format(new Date(s.date + 'T00:00:00'), 'EEE, MMM dd');
            const timeLabel = `${fmtTime(s.startMin)} – ${fmtTime(s.startMin + dur)}`;
            const isCheaper = s.mult <= 1;
            const badge = s.kind === 'same-day-off-peak' ? 'Same day · off-peak'
                        : s.kind === 'same-day'          ? 'Same day'
                        : s.kind === 'off-peak'          ? 'Off-peak'
                        : 'Next available';
            return (
              <button
                key={`${s.date}|${s.startMin}`}
                type="button"
                onClick={() => onPick && onPick(s.date, s.startMin)}
                style={{
                  textAlign: 'left', padding: '0.65rem 0.75rem',
                  borderRadius: '8px', border: '1px solid var(--border, #e5e7eb)',
                  background: 'var(--surface-muted, rgba(99,102,241,0.04))',
                  cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: '0.2rem',
                }}>
                <span style={{ fontWeight: 600 }}>{dateLabel}</span>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary, #555)' }}>{timeLabel}</span>
                <span style={{ fontSize: '0.7rem', display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                  <span style={{
                    padding: '0.1rem 0.45rem', borderRadius: '999px',
                    background: isCheaper ? 'rgba(16,185,129,0.12)' : 'rgba(234,179,8,0.16)',
                    color: isCheaper ? '#047857' : '#92400e', fontWeight: 600,
                  }}>{badge}</span>
                  {!isCheaper && (
                    <span style={{ color: 'var(--text-muted, #888)' }}>{s.mult}× peak</span>
                  )}
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
