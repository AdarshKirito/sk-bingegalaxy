import { useState, useEffect } from 'react';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiEdit2, FiPlus, FiToggleLeft, FiToggleRight, FiTrash2, FiX } from 'react-icons/fi';
import './AdminPages.css';

const ROOM_TYPES = ['MAIN_HALL', 'PRIVATE_ROOM', 'VIP_LOUNGE', 'OUTDOOR', 'MEETING_ROOM'];

const emptyForm = { name: '', roomType: 'MAIN_HALL', capacity: 10, description: '', sortOrder: 0 };

export default function AdminVenueRooms() {
  const [rooms, setRooms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyForm);

  const fetchRooms = async () => {
    try {
      const res = await adminService.getVenueRooms();
      setRooms(res.data.data || []);
    } catch {
      toast.error('Failed to load venue rooms');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchRooms(); }, []);

  const resetForm = () => {
    setForm(emptyForm);
    setShowForm(false);
    setEditId(null);
  };

  const handleEdit = (room) => {
    setEditId(room.id);
    setForm({
      name: room.name,
      roomType: room.roomType || 'MAIN_HALL',
      capacity: room.capacity || 10,
      description: room.description || '',
      sortOrder: room.sortOrder || 0,
    });
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Room name is required'); return; }
    try {
      if (editId) {
        await adminService.updateVenueRoom(editId, form);
        toast.success('Room updated');
      } else {
        await adminService.createVenueRoom(form);
        toast.success('Room created');
      }
      resetForm();
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to save room');
    }
  };

  const handleToggle = async (id) => {
    try {
      await adminService.toggleVenueRoom(id);
      toast.success('Room status toggled');
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to toggle room');
    }
  };

  const handleDelete = async (room) => {
    if (room.active) { toast.error('Deactivate the room before deleting'); return; }
    if (!confirm(`Delete room "${room.name}" permanently?`)) return;
    try {
      await adminService.deleteVenueRoom(room.id);
      toast.success('Room deleted');
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to delete room');
    }
  };

  if (loading) return <div className="loading"><div className="spinner"></div></div>;

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1>Venue Rooms</h1>
          <p>Manage bookable rooms and spaces within this venue. Customers can optionally choose a room when booking.</p>
        </div>
        <button className="btn btn-primary" onClick={() => showForm ? resetForm() : setShowForm(true)}>
          {showForm ? <><FiX /> Cancel</> : <><FiPlus /> Add Room</>}
        </button>
      </div>

      {showForm && (
        <section className="adm-form card" style={{ marginBottom: '1.5rem' }}>
          <h3>{editId ? 'Edit Room' : 'Create Room'}</h3>
          <form onSubmit={handleSubmit}>
            <div className="adm-grid-2">
              <div className="input-group">
                <label>Room Name *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., VIP Lounge A" />
              </div>
              <div className="input-group">
                <label>Room Type</label>
                <select value={form.roomType} onChange={(e) => setForm({ ...form, roomType: e.target.value })}>
                  {ROOM_TYPES.map(t => <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              <div className="input-group">
                <label>Capacity (max guests)</label>
                <input type="number" min="1" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: Number(e.target.value) })} />
              </div>
              <div className="input-group">
                <label>Sort Order</label>
                <input type="number" min="0" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} />
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <label>Description</label>
                <textarea rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Optional description visible to customers" />
              </div>
            </div>
            <div className="adm-form-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary">{editId ? 'Update Room' : 'Create Room'}</button>
            </div>
          </form>
        </section>
      )}

      {rooms.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <h3>No rooms configured</h3>
          <p style={{ color: 'var(--text-muted)' }}>Create rooms to let customers pick their preferred space during booking.</p>
        </div>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Room</th>
                <th>Type</th>
                <th>Capacity</th>
                <th>Order</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rooms.map(room => (
                <tr key={room.id} className={room.active ? '' : 'adm-row-inactive'}>
                  <td>
                    <strong>{room.name}</strong>
                    {room.description && <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', margin: '0.2rem 0 0' }}>{room.description}</p>}
                  </td>
                  <td>{(room.roomType || '').replace(/_/g, ' ')}</td>
                  <td>{room.capacity}</td>
                  <td>{room.sortOrder}</td>
                  <td>
                    <span className={`badge ${room.active ? 'badge-success' : 'badge-danger'}`}>
                      {room.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                      <button className="btn btn-secondary btn-sm" onClick={() => handleEdit(room)}><FiEdit2 /></button>
                      <button className={`btn btn-sm ${room.active ? 'btn-danger' : ''}`}
                        style={!room.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                        onClick={() => handleToggle(room.id)}>
                        {room.active ? <FiToggleLeft /> : <FiToggleRight />}
                      </button>
                      {!room.active && (
                        <button className="btn btn-sm adm-danger-btn" onClick={() => handleDelete(room)}><FiTrash2 /></button>
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
