import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';
import { FiActivity, FiArrowRight, FiCompass, FiEdit2, FiMapPin, FiPlus, FiToggleLeft, FiToggleRight, FiTrash2, FiX } from 'react-icons/fi';
import './AdminPages.css';

export default function BingeManagement() {
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState({ name: '', address: '' });
  const { clearBinge, selectBinge, selectedBinge } = useBinge();
  const navigate = useNavigate();

  const fetchBinges = async () => {
    try {
      const res = await adminService.getAdminBinges();
      setBinges(res.data.data || res.data || []);
    } catch (err) {
      toast.error('Failed to load binges');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchBinges(); }, []);

  const resetForm = () => {
    setForm({ name: '', address: '' });
    setShowForm(false);
    setEditId(null);
  };

  const openCreateForm = () => {
    setEditId(null);
    setForm({ name: '', address: '' });
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      if (editId) {
        await adminService.updateBinge(editId, form);
        toast.success('Binge updated');
      } else {
        await adminService.createBinge(form);
        toast.success('Binge created');
      }
      resetForm();
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save binge');
    }
  };

  const handleEdit = (b) => {
    setEditId(b.id);
    setForm({ name: b.name, address: b.address || '' });
    setShowForm(true);
  };

  const handleToggle = async (id) => {
    try {
      await adminService.toggleBinge(id);
      toast.success('Binge status toggled');
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to toggle binge');
    }
  };

  const handleDelete = async (binge) => {
    if (binge.active) {
      toast.error('Deactivate the binge before deleting it');
      return;
    }
    if (!confirm(`Delete "${binge.name}" permanently? This cannot be undone.`)) return;

    try {
      await adminService.deleteBinge(binge.id);
      if (selectedBinge?.id === binge.id) clearBinge();
      if (editId === binge.id) resetForm();
      toast.success('Binge deleted');
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to delete binge');
    }
  };

  const handleSelect = (binge) => {
    selectBinge({ id: binge.id, name: binge.name, address: binge.address });
    toast.success(`Entered: ${binge.name}`);
    navigate('/admin/dashboard');
  };

  const activeCount = binges.filter((binge) => binge.active).length;
  const inactiveCount = binges.length - activeCount;

  if (loading) {
    return (
      <div className="container adm-shell adm-flow-shell">
        <div className="adm-flow-card adm-flow-empty">
          <span className="adm-empty-icon"><FiCompass /></span>
          <h3>Loading your venues...</h3>
          <p>Preparing the admin workspaces connected to each binge.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="container adm-shell adm-flow-shell">
      <section className="adm-flow-hero">
        <div className="adm-flow-copy">
          <span className="adm-kicker"><FiMapPin /> Venue management</span>
          <h1>Select the binge that anchors your admin workspace.</h1>
          <p>Choose a venue to enter its dashboard, or create a new binge to expand your booking operations without leaving this control panel.</p>
          <div className="adm-flow-badges">
            <span className="adm-badge adm-badge-info">{binges.length} total venues</span>
            <span className={`adm-badge ${activeCount ? 'adm-badge-active' : 'adm-badge-inactive'}`}>{activeCount} active</span>
            <span className={`adm-badge ${inactiveCount ? 'adm-badge-inactive' : 'adm-badge-info'}`}>{inactiveCount} inactive</span>
          </div>
        </div>

        <div className="adm-flow-card adm-flow-summary">
          <span className="adm-kicker"><FiActivity /> Workspace pulse</span>
          <div className="adm-flow-stack">
            <div className="adm-flow-row">
              <span>Ready-to-manage venues</span>
              <strong>{activeCount}</strong>
            </div>
            <div className="adm-flow-row">
              <span>Needs activation</span>
              <strong>{inactiveCount}</strong>
            </div>
            <div className="adm-flow-row">
              <span>Next step</span>
              <strong>{activeCount ? 'Enter dashboard' : 'Create or reactivate'}</strong>
            </div>
          </div>
          <p className="adm-flow-helper">Active venues can be opened immediately. Inactive ones stay editable until you are ready to bring them back into rotation.</p>
          <div className="adm-flow-actions">
            <button
              type="button"
              className="btn btn-primary"
              aria-pressed={showForm}
              onClick={() => (showForm ? resetForm() : openCreateForm())}
            >
              {showForm ? <><FiX /> Cancel</> : <><FiPlus /> Create Binge</>}
            </button>
          </div>
        </div>
      </section>

      {showForm && (
        <section className="adm-form adm-flow-card">
          <h3>{editId ? 'Edit binge details' : 'Create a new binge'}</h3>
          <p className="adm-form-intro">Update the venue name and address shown throughout the admin console and the customer booking flow.</p>
          <form onSubmit={handleSubmit}>
            <div className="adm-grid-2">
              <div className="input-group">
                <label>Binge Name *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., Downtown Arena" />
              </div>
              <div className="input-group">
                <label>Address</label>
                <input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} placeholder="123 Main St, City" />
              </div>
            </div>
            <div className="adm-form-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary">{editId ? 'Update Binge' : 'Create Binge'}</button>
            </div>
          </form>
        </section>
      )}

      {binges.length === 0 ? (
        <div className="adm-empty adm-flow-card adm-flow-empty">
          <span className="adm-empty-icon"><FiMapPin /></span>
          <h3>No binges yet</h3>
          <p>Create your first venue above to get started with bookings and management.</p>
          {!showForm && (
            <button type="button" className="btn btn-primary" onClick={openCreateForm}>
              <FiPlus /> Create your first binge
            </button>
          )}
        </div>
      ) : (
        <div className="adm-venue-grid">
          {binges.map((b) => (
            <article key={b.id} className={`adm-venue-card${b.active ? '' : ' inactive'}`}>
              <div className="adm-venue-card-top">
                <span className="adm-kicker">Venue option</span>
                <span className={`adm-badge ${b.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                  {b.active ? 'Active' : 'Inactive'}
                </span>
              </div>

              <div className="adm-venue-card-copy">
                <h3>{b.name}</h3>
                <p>{b.address || 'Add an address so the venue reads clearly across staff and customer workflows.'}</p>
              </div>

              <p className="adm-venue-card-note">
                {b.active
                  ? 'Dashboard, bookings, availability, and reporting tools are ready for this venue.'
                  : 'Reactivate this venue whenever you want it back in the live admin and booking rotation.'}
              </p>

              <div className="adm-venue-actions">
                {b.active && (
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => handleSelect(b)}>
                    Enter <FiArrowRight />
                  </button>
                )}
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleEdit(b)}>
                  <FiEdit2 style={{ marginRight: 3 }} /> Edit
                </button>
                <button type="button" className={`btn btn-sm ${b.active ? 'btn-danger' : ''}`}
                  style={!b.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                  onClick={() => handleToggle(b.id)}>
                  {b.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                </button>
                {!b.active && (
                  <button type="button" className="btn adm-danger-btn btn-sm" onClick={() => handleDelete(b)}>
                    <FiTrash2 style={{ marginRight: 3 }} /> Delete
                  </button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
