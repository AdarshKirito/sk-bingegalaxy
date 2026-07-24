import { useEffect, useMemo, useState } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import {
  FiGlobe, FiMapPin, FiClock, FiZap, FiEdit3, FiCheckCircle, FiArrowRight, FiRefreshCw,
} from 'react-icons/fi';
import TimezonePicker, { zonePreview } from '../components/TimezonePicker';
import { venueZoneLabel } from '../utils/format';
import './AdminPages.css';
import './AdminVenueTimezones.css';

/**
 * Super-admin console to assign an IANA timezone to one or more venues at once.
 *
 * Two ways to pick the zone:
 *  - Auto — the super-admin's own current location (browser timezone), one click.
 *  - Manual — search/select any zone in the world via the shared TimezonePicker.
 *
 * A venue's timezone governs how every booking date/time, schedule, and check-in
 * window is interpreted, so changing it is high-blast-radius — the Apply action is
 * confirmed and the same server-side permission gate as the single-venue editor
 * still applies (a native super-admin always passes).
 */
export default function AdminVenueTimezones() {
  const confirm = useConfirm();
  const [venues, setVenues] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(() => new Set());
  const [mode, setMode] = useState('auto'); // 'auto' | 'manual'
  const [manualZone, setManualZone] = useState('');
  const [applying, setApplying] = useState(false);
  const [nowTick, setNowTick] = useState(0); // refresh live previews periodically

  // The super-admin's own browser timezone — the "auto" target.
  const autoZone = useMemo(() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
    catch { return 'UTC'; }
  }, []);

  const targetZone = mode === 'auto' ? autoZone : manualZone.trim();

  const fetchVenues = () => {
    setLoading(true);
    adminService.getAdminBinges()
      .then((res) => setVenues(toArray(res.data?.data)))
      .catch(() => toast.error('Failed to load venues'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchVenues(); }, []);

  // Tick every 30s so the "current time there" previews stay live without spamming renders.
  useEffect(() => {
    const id = setInterval(() => setNowTick((t) => t + 1), 30_000);
    return () => clearInterval(id);
  }, []);

  const allSelected = venues.length > 0 && selected.size === venues.length;
  const toggleAll = () => {
    setSelected(allSelected ? new Set() : new Set(venues.map((v) => v.id)));
  };
  const toggleOne = (id) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const targetPreview = targetZone ? zonePreview(targetZone) : null;
  const canApply = selected.size > 0 && !!targetZone && !applying;

  const handleApply = async () => {
    if (!canApply) return;
    const ids = [...selected];
    const zoneLabel = venueZoneLabel(targetZone);
    const ok = await confirm({
      title: `Set timezone for ${ids.length} venue${ids.length > 1 ? 's' : ''}?`,
      message:
        `All selected venues will use ${targetZone} (${zoneLabel}).\n\n` +
        'This is how every booking date, time, schedule and check-in window at these ' +
        'venues is interpreted. Existing bookings keep their wall-clock time but are now ' +
        'read in the new zone — change this only when the venue is genuinely in this zone.',
      confirmLabel: `Apply to ${ids.length} venue${ids.length > 1 ? 's' : ''}`,
      variant: 'warning',
    });
    if (!ok) return;

    setApplying(true);
    try {
      const res = await adminService.bulkAssignBingeTimezone(ids, targetZone);
      const data = res.data?.data;
      const updated = data?.updatedCount ?? ids.length;
      const notFound = data?.notFoundBingeIds?.length || 0;
      toast.success(
        `${updated} venue${updated === 1 ? '' : 's'} set to ${zoneLabel}` +
        (notFound ? ` · ${notFound} skipped (no longer exist)` : ''),
      );
      setSelected(new Set());
      fetchVenues();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to assign timezone');
    } finally {
      setApplying(false);
    }
  };

  return (
    <div className="admin-page vtz-page">
      <div className="admin-page-header">
        <div>
          <h1><FiGlobe /> Venue Timezones</h1>
          <p className="page-subtitle">
            Assign a timezone to one or more venues. A venue&apos;s timezone controls how all
            its booking times, schedules and check-in windows are read.
          </p>
        </div>
        <button className="btn btn-secondary" onClick={fetchVenues} disabled={loading}>
          <FiRefreshCw /> Refresh
        </button>
      </div>

      {/* ── Assignment controls ─────────────────────────────── */}
      <div className="vtz-assign card">
        <div className="vtz-mode">
          <span className="vtz-mode__label">Timezone source</span>
          <div className="vtz-segmented" role="tablist" aria-label="Timezone source">
            <button
              role="tab"
              aria-selected={mode === 'auto'}
              className={`vtz-seg ${mode === 'auto' ? 'is-active' : ''}`}
              onClick={() => setMode('auto')}
            >
              <FiZap /> Auto — my location
            </button>
            <button
              role="tab"
              aria-selected={mode === 'manual'}
              className={`vtz-seg ${mode === 'manual' ? 'is-active' : ''}`}
              onClick={() => setMode('manual')}
            >
              <FiEdit3 /> Manual — pick any zone
            </button>
          </div>
        </div>

        <div className="vtz-source-detail">
          {mode === 'auto' ? (
            <div className="vtz-auto">
              <span className="vtz-auto__zone"><FiMapPin /> {autoZone}</span>
              <span className="vtz-auto__hint">Detected from your browser&apos;s current location.</span>
            </div>
          ) : (
            <div className="vtz-manual">
              <label htmlFor="vtz-picker" className="vtz-manual__label">Search world timezones</label>
              <TimezonePicker id="vtz-picker" value={manualZone} onChange={setManualZone} />
            </div>
          )}
        </div>

        <div className="vtz-apply-bar">
          <div className="vtz-apply-summary">
            {targetZone ? (
              <>
                <span className="vtz-target">
                  <FiGlobe /> <strong>{venueZoneLabel(targetZone)}</strong>
                  <span className="vtz-target__id">{targetZone}</span>
                </span>
                {targetPreview && <span className="vtz-target__preview"><FiClock /> {targetPreview}</span>}
              </>
            ) : (
              <span className="vtz-target vtz-target--empty">Pick a timezone above</span>
            )}
          </div>
          <button className="btn btn-primary vtz-apply-btn" onClick={handleApply} disabled={!canApply}>
            <FiCheckCircle />
            {applying
              ? 'Applying…'
              : `Apply to ${selected.size} selected`}
          </button>
        </div>
      </div>

      {/* ── Venues table ────────────────────────────────────── */}
      {loading ? (
        <p>Loading venues…</p>
      ) : venues.length === 0 ? (
        <p className="vtz-empty">No venues found.</p>
      ) : (
        <div className="admin-table-wrap">
          <table className="admin-table vtz-table">
            <thead>
              <tr>
                <th className="vtz-check">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    aria-label="Select all venues"
                    onChange={toggleAll}
                  />
                </th>
                <th>Venue</th>
                <th>Location</th>
                <th>Current timezone</th>
                <th>Current time there</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {venues.map((v) => {
                const isSel = selected.has(v.id);
                const willChange = isSel && targetZone && targetZone !== v.timezone;
                return (
                  <tr key={v.id} className={isSel ? 'is-selected' : ''}>
                    <td className="vtz-check" onClick={(e) => e.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={isSel}
                        aria-label={`Select ${v.name}`}
                        onChange={() => toggleOne(v.id)}
                      />
                    </td>
                    <td>
                      <strong>{v.name}</strong>
                      {v.active === false && <span className="badge badge-muted vtz-inactive">Inactive</span>}
                    </td>
                    <td className="vtz-loc">
                      {[v.city, v.country].filter(Boolean).join(', ') || '—'}
                    </td>
                    <td>
                      <span className="vtz-zone-id">{v.timezone || '—'}</span>
                      <span className="vtz-zone-label">{v.timezone ? venueZoneLabel(v.timezone) : ''}</span>
                    </td>
                    <td className="vtz-preview">{v.timezone ? (zonePreview(v.timezone) || '—') : '—'}</td>
                    <td className="vtz-change">
                      {willChange && (
                        <span className="vtz-change__pill">
                          <FiArrowRight /> {venueZoneLabel(targetZone)}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
