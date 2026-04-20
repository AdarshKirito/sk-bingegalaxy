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

const emptyForm = { name: '', dayOfWeek: '', startMinute: 1080, endMinute: 1380, multiplier: 1.5, label: '' };

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
    });
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (saving) return;
    if (!form.name.trim()) { toast.error('Rule name is required'); return; }
    if (!Number.isFinite(form.multiplier) || form.multiplier < 1 || form.multiplier > 5) { toast.error('Multiplier must be a number between 1.0 and 5.0'); return; }
    if (Number(form.startMinute) >= Number(form.endMinute)) { toast.error('End time must be after start time'); return; }
    setSaving(true);
    const payload = {
      ...form,
      dayOfWeek: form.dayOfWeek === '' ? null : Number(form.dayOfWeek),
      startMinute: Number(form.startMinute),
      endMinute: Number(form.endMinute),
      multiplier: Number(form.multiplier),
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
          <p>Define time-based pricing multipliers. When a booking falls within a surge window, the highest matching multiplier applies to the total.</p>
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
                <label>Multiplier (1.0 – 5.0)</label>
                <input type="number" step="0.1" min="1" max="5" value={form.multiplier} onChange={(e) => setForm({ ...form, multiplier: Number(e.target.value) || '' })} />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>e.g., 1.5 = 50% price increase</span>
              </div>
              <div className="input-group">
                <label>Display Label</label>
                <input value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} placeholder="e.g., Peak Hours" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Shown to customers during booking</span>
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
                <th>Multiplier</th>
                <th>Label</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map(rule => (
                <tr key={rule.id} className={rule.active ? '' : 'adm-row-inactive'}>
                  <td><strong>{rule.name}</strong></td>
                  <td>{dayLabel(rule.dayOfWeek)}</td>
                  <td>{fmtTime(rule.startMinute)} – {fmtTime(rule.endMinute)}</td>
                  <td>
                    <span style={{ fontWeight: 700, color: rule.multiplier > 1 ? '#d97706' : 'inherit' }}>
                      {Number(rule.multiplier).toFixed(1)}×
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
