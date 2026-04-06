import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { useBinge } from '../context/BingeContext';
import { toast } from 'react-toastify';

export default function BingeManagement() {
  const [binges, setBinges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState({ name: '', address: '' });
  const { selectBinge } = useBinge();
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
      setForm({ name: '', address: '' });
      setShowForm(false);
      setEditId(null);
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
      toast.error('Failed to toggle binge');
    }
  };

  const handleSelect = (binge) => {
    selectBinge({ id: binge.id, name: binge.name, address: binge.address });
    toast.success(`Entered: ${binge.name}`);
    navigate('/admin/dashboard');
  };

  if (loading) return <div className="container"><p>Loading...</p></div>;

  return (
    <div className="container" style={{ maxWidth: 800, margin: '2rem auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2 style={{ margin: 0 }}>My Binges</h2>
          <p style={{ color: 'var(--text-secondary)', margin: '0.25rem 0 0', fontSize: '0.9rem' }}>Create and manage your venues. Select one to enter its dashboard.</p>
        </div>
        <button className="btn btn-primary" onClick={() => { setShowForm(!showForm); setEditId(null); setForm({ name: '', address: '' }); }}>
          {showForm ? 'Cancel' : '+ Create Binge'}
        </button>
      </div>

      {showForm && (
        <div className="card" style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
          <h3 style={{ margin: '0 0 1rem' }}>{editId ? 'Edit Binge' : 'Create New Binge'}</h3>
          <form onSubmit={handleSubmit}>
            <div className="input-group">
              <label>Binge Name *</label>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., Downtown Arena" />
            </div>
            <div className="input-group">
              <label>Address</label>
              <input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} placeholder="123 Main St, City" />
            </div>
            <button type="submit" className="btn btn-primary" style={{ marginTop: '0.5rem' }}>{editId ? 'Update Binge' : 'Create Binge'}</button>
          </form>
        </div>
      )}

      {binges.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <p style={{ fontSize: '1.2rem', color: 'var(--text-muted)' }}>No binges yet. Create your first venue above!</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: '1rem' }}>
          {binges.map((b) => (
            <div key={b.id} className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1.25rem 1.5rem', opacity: b.active ? 1 : 0.6 }}>
              <div>
                <h3 style={{ margin: 0, fontSize: '1.1rem' }}>{b.name}</h3>
                {b.address && <p style={{ margin: '0.25rem 0 0', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>{b.address}</p>}
                <span className="badge" style={{ marginTop: '0.5rem', background: b.active ? 'rgba(16,185,129,0.1)' : 'rgba(239,68,68,0.1)', color: b.active ? 'var(--success)' : 'var(--danger)' }}>
                  {b.active ? 'Active' : 'Inactive'}
                </span>
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', flexShrink: 0 }}>
                {b.active && (
                  <button className="btn btn-primary btn-sm" onClick={() => handleSelect(b)}>Enter</button>
                )}
                <button className="btn btn-secondary btn-sm" onClick={() => handleEdit(b)}>Edit</button>
                <button className="btn btn-sm" style={{ background: b.active ? 'var(--danger)' : 'var(--success)', color: '#fff' }} onClick={() => handleToggle(b.id)}>
                  {b.active ? 'Deactivate' : 'Activate'}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
