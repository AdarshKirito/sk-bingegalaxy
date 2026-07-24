import { useState, useEffect } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiEdit2, FiPlus, FiToggleLeft, FiToggleRight, FiTrash2, FiX, FiZap } from 'react-icons/fi';
import './AdminPages.css';

const DAYS = [
  { value: '', label: 'All days' },
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' },
];

const fmtTime = (totalMinutes) => {
  const h = Math.floor(totalMinutes / 60) % 24;
  const m = totalMinutes % 60;
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
};

const parseTime = (timeStr) => {
  const [h, m] = (timeStr || '00:00').split(':').map(Number);
  return h * 60 + (m || 0);
};

const emptyForm = {
  name: '', dayOfWeek: '', startMinute: 1080, endMinute: 1380, multiplier: 1.5, label: '',
  dateFrom: '', dateTo: '', leadTimeMaxHours: '', leadTimeMinHours: '', occupancyThresholdPct: '', priority: 100,
};

export default function AdminSurgeRules() {
  const [rules, setRules] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);

  const fetchRules = async () => {
    try {
      const res = await adminService.getSurgeRules();
      setRules(toArray(res.data?.data));
    } catch {
      toast.error('Failed to load surge rules');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchRules(); }, []);

  const resetForm = () => {
    setForm(emptyForm);
    setShowForm(false);
    setEditId(null);
  };

  const handleEdit = (rule) => {
    setEditId(rule.id);
    setForm({
      name: rule.name || '',
      dayOfWeek: rule.dayOfWeek ?? '',
      startMinute: rule.startMinute ?? 0,
      endMinute: rule.endMinute ?? 1440,
      multiplier: Number(rule.multiplier ?? 1.5),
      label: rule.label || '',
      dateFrom: rule.dateFrom || '',
      dateTo: rule.dateTo || '',
      leadTimeMaxHours: rule.leadTimeMaxHours ?? '',
      leadTimeMinHours: rule.leadTimeMinHours ?? '',
      occupancyThresholdPct: rule.occupancyThresholdPct ?? '',
      priority: rule.priority ?? 100,
    });
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (saving) return;
    if (!form.name.trim()) { toast.error('Rule name is required'); return; }
    if (!Number.isFinite(form.multiplier) || form.multiplier < 0.1 || form.multiplier > 5) { toast.error('Multiplier must be a number between 0.1 and 5.0'); return; }
    if (Number(form.startMinute) >= Number(form.endMinute)) { toast.error('End time must be after start time'); return; }
    if (form.dateFrom && form.dateTo && form.dateFrom > form.dateTo) { toast.error('Date from must be on or before date to'); return; }
    setSaving(true);
    const payload = {
      ...form,
      dayOfWeek: form.dayOfWeek === '' ? null : Number(form.dayOfWeek),
      startMinute: Number(form.startMinute),
      endMinute: Number(form.endMinute),
      multiplier: Number(form.multiplier),
      dateFrom: form.dateFrom || null,
      dateTo: form.dateTo || null,
      leadTimeMaxHours: form.leadTimeMaxHours === '' ? null : Number(form.leadTimeMaxHours),
      leadTimeMinHours: form.leadTimeMinHours === '' ? null : Number(form.leadTimeMinHours),
      occupancyThresholdPct: form.occupancyThresholdPct === '' ? null : Number(form.occupancyThresholdPct),
      priority: Number(form.priority) || 100,
    };
    try {
      if (editId) {
        await adminService.updateSurgeRule(editId, payload);
        toast.success('Surge rule updated');
      } else {
        await adminService.createSurgeRule(payload);
        toast.success('Surge rule created');
      }
      resetForm();
      fetchRules();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to save surge rule');
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (id) => {
    try {
      await adminService.toggleSurgeRule(id);
      toast.success('Rule status toggled');
      fetchRules();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to toggle rule');
    }
  };

  const handleDelete = async (rule) => {
    if (rule.active) { toast.error('Deactivate the rule before deleting'); return; }
    if (!confirm(`Delete rule "${rule.name}" permanently?`)) return;
    try {
      await adminService.deleteSurgeRule(rule.id);
      toast.success('Rule deleted');
      fetchRules();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to delete rule');
    }
  };

  const dayLabel = (dow) => DAYS.find(d => d.value === dow)?.label || 'All days';

  if (loading) return <div className="loading"><div className="spinner"></div></div>;

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiZap style={{ verticalAlign: '-2px' }} /> Surge Pricing Rules</h1>
          <p>
            Dynamic pricing rules: time windows, seasonal dates, last-minute premiums, early-bird
            discounts (multiplier below 1), and demand triggers that fire once a date is X% booked.
            When several rules match, the lowest priority number wins (ties go to the strongest multiplier).
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => showForm ? resetForm() : setShowForm(true)}>
          {showForm ? <><FiX /> Cancel</> : <><FiPlus /> Add Rule</>}
        </button>
      </div>

      {showForm && (
        <section className="adm-form card" style={{ marginBottom: '1.5rem' }}>
          <h3>{editId ? 'Edit Surge Rule' : 'Create Surge Rule'}</h3>
          <form onSubmit={handleSubmit}>
            <div className="adm-grid-2">
              <div className="input-group">
                <label>Rule Name *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., Weekend Evening Rush" />
              </div>
              <div className="input-group">
                <label>Day of Week</label>
                <select value={form.dayOfWeek} onChange={(e) => setForm({ ...form, dayOfWeek: e.target.value })}>
                  {DAYS.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
                </select>
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Leave as "All days" to apply every day</span>
              </div>
              <div className="input-group">
                <label>Start Time</label>
                <input type="time" value={fmtTime(form.startMinute)} onChange={(e) => setForm({ ...form, startMinute: parseTime(e.target.value) })} />
              </div>
              <div className="input-group">
                <label>End Time</label>
                <input type="time" value={fmtTime(form.endMinute)} onChange={(e) => setForm({ ...form, endMinute: parseTime(e.target.value) })} />
              </div>
              <div className="input-group">
                <label>Multiplier (0.1 – 5.0)</label>
                <input type="number" step="0.05" min="0.1" max="5" value={form.multiplier} onChange={(e) => setForm({ ...form, multiplier: Number(e.target.value) || '' })} />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>1.5 = 50% premium · 0.9 = 10% early-bird discount</span>
              </div>
              <div className="input-group">
                <label>Display Label</label>
                <input value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} placeholder="e.g., Peak Hours" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Shown to customers during booking</span>
              </div>
              <div className="input-group">
                <label>Date From (optional)</label>
                <input type="date" value={form.dateFrom} onChange={(e) => setForm({ ...form, dateFrom: e.target.value })} />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Seasonal/event window start (e.g. festival week)</span>
              </div>
              <div className="input-group">
                <label>Date To (optional)</label>
                <input type="date" value={form.dateTo} onChange={(e) => setForm({ ...form, dateTo: e.target.value })} />
              </div>
              <div className="input-group">
                <label>Last-minute window (hours)</label>
                <input type="number" min="0" max="8760" value={form.leadTimeMaxHours}
                  onChange={(e) => setForm({ ...form, leadTimeMaxHours: e.target.value })} placeholder="e.g. 6" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Applies only when the slot starts within X hours (last-minute premium)</span>
              </div>
              <div className="input-group">
                <label>Early-bird floor (hours)</label>
                <input type="number" min="0" max="8760" value={form.leadTimeMinHours}
                  onChange={(e) => setForm({ ...form, leadTimeMinHours: e.target.value })} placeholder="e.g. 168" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Applies only when booked at least X hours ahead — pair with a &lt;1 multiplier for a discount</span>
              </div>
              <div className="input-group">
                <label>Demand trigger (% booked)</label>
                <input type="number" min="1" max="100" value={form.occupancyThresholdPct}
                  onChange={(e) => setForm({ ...form, occupancyThresholdPct: e.target.value })} placeholder="e.g. 70" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Applies only once the date is at least X% occupied (leave blank = always)</span>
              </div>
              <div className="input-group">
                <label>Priority</label>
                <input type="number" min="1" max="1000" value={form.priority}
                  onChange={(e) => setForm({ ...form, priority: e.target.value })} />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>When rules overlap, the lowest number wins (ties → strongest multiplier)</span>
              </div>
            </div>
            <div className="adm-form-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : editId ? 'Update Rule' : 'Create Rule'}</button>
            </div>
          </form>
        </section>
      )}

      {rules.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <h3>No surge pricing rules</h3>
          <p style={{ color: 'var(--text-muted)' }}>Create rules to automatically apply higher prices during peak hours or special days.</p>
        </div>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Rule</th>
                <th>Day</th>
                <th>Window</th>
                <th>Triggers</th>
                <th>Multiplier</th>
                <th>Label</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map(rule => (
                <tr key={rule.id} className={rule.active ? '' : 'adm-row-inactive'}>
                  <td>
                    <strong>{rule.name}</strong>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>priority {rule.priority ?? 100}</div>
                  </td>
                  <td>{dayLabel(rule.dayOfWeek)}</td>
                  <td>{fmtTime(rule.startMinute)} – {fmtTime(rule.endMinute)}</td>
                  <td style={{ fontSize: '0.78rem' }}>
                    {(rule.dateFrom || rule.dateTo) && (
                      <div title="Seasonal/event window">📅 {rule.dateFrom || '…'} → {rule.dateTo || '…'}</div>
                    )}
                    {rule.leadTimeMaxHours != null && <div title="Last-minute premium">⏱ within {rule.leadTimeMaxHours}h of start</div>}
                    {rule.leadTimeMinHours != null && <div title="Early-bird window">🐦 booked ≥ {rule.leadTimeMinHours}h ahead</div>}
                    {rule.occupancyThresholdPct != null && <div title="Demand trigger">📈 ≥ {rule.occupancyThresholdPct}% booked</div>}
                    {!rule.dateFrom && !rule.dateTo && rule.leadTimeMaxHours == null && rule.leadTimeMinHours == null && rule.occupancyThresholdPct == null && '—'}
                  </td>
                  <td>
                    <span style={{ fontWeight: 700, color: rule.multiplier > 1 ? '#d97706' : rule.multiplier < 1 ? '#059669' : 'inherit' }}>
                      {Number(rule.multiplier).toFixed(2)}×
                    </span>
                  </td>
                  <td>{rule.label || '—'}</td>
                  <td>
                    <span className={`badge ${rule.active ? 'badge-success' : 'badge-danger'}`}>
                      {rule.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                      <button className="btn btn-secondary btn-sm" onClick={() => handleEdit(rule)}><FiEdit2 /></button>
                      <button className={`btn btn-sm ${rule.active ? 'btn-danger' : ''}`}
                        style={!rule.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                        onClick={() => handleToggle(rule.id)}>
                        {rule.active ? <FiToggleLeft /> : <FiToggleRight />}
                      </button>
                      {!rule.active && (
                        <button className="btn btn-sm adm-danger-btn" onClick={() => handleDelete(rule)}><FiTrash2 /></button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
