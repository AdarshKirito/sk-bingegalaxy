import { useState, useEffect } from 'react';
import { adminService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import { FiEdit2, FiPlus, FiToggleLeft, FiToggleRight, FiTrash2, FiX, FiCheck, FiSlash, FiClock } from 'react-icons/fi';
import useAuthStore from '../stores/authStore';
import { useConfirm } from '../components/ui/ConfirmProvider';
import './AdminPages.css';

const ROOM_TYPES = ['MAIN_HALL', 'PRIVATE_ROOM', 'VIP_LOUNGE', 'OUTDOOR', 'MEETING_ROOM'];

const emptyForm = {
  name: '',
  roomType: 'MAIN_HALL',
  capacity: 10,
  description: '',
  sortOrder: 0,
  priceAddition: 0,
  active: true,
  imageUrls: [],
};

const STATUS_BADGES = {
  PENDING_APPROVAL: { cls: 'badge-warning', label: 'Pending Approval' },
  APPROVED: { cls: 'badge-success', label: 'Approved' },
  REJECTED: { cls: 'badge-danger', label: 'Rejected' },
};

export default function AdminVenueRooms() {
  const isSuperAdmin = useAuthStore(s => s.isSuperAdmin);
  const confirm = useConfirm();
  const [rooms, setRooms] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  // newline-separated URL editor — keeps the form simple and resilient
  const [imageUrlsText, setImageUrlsText] = useState('');

  // V57: maintenance / hold windows modal
  const [blocksRoom, setBlocksRoom] = useState(null);   // room currently being managed
  const [blocks, setBlocks] = useState([]);
  const [blocksLoading, setBlocksLoading] = useState(false);
  const [blockForm, setBlockForm] = useState({ startAt: '', endAt: '', reason: '' });
  const [blockSaving, setBlockSaving] = useState(false);

  const openBlocks = async (room) => {
    setBlocksRoom(room);
    setBlockForm({ startAt: '', endAt: '', reason: '' });
    setBlocksLoading(true);
    try {
      const res = await adminService.listRoomBlocks(room.id);
      setBlocks(toArray(res.data?.data));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load room blocks');
      setBlocks([]);
    } finally {
      setBlocksLoading(false);
    }
  };

  const closeBlocks = () => {
    setBlocksRoom(null);
    setBlocks([]);
    setBlockForm({ startAt: '', endAt: '', reason: '' });
  };

  const submitBlock = async (e) => {
    e.preventDefault();
    if (blockSaving || !blocksRoom) return;
    if (!blockForm.startAt || !blockForm.endAt) { toast.error('Start and end are required'); return; }
    if (new Date(blockForm.endAt) <= new Date(blockForm.startAt)) {
      toast.error('End must be after start'); return;
    }
    setBlockSaving(true);
    try {
      // datetime-local values are local time without zone; backend stores LocalDateTime — send as-is.
      await adminService.createRoomBlock(blocksRoom.id, {
        startAt: blockForm.startAt,
        endAt: blockForm.endAt,
        reason: blockForm.reason || null,
      });
      toast.success('Block created');
      setBlockForm({ startAt: '', endAt: '', reason: '' });
      const res = await adminService.listRoomBlocks(blocksRoom.id);
      setBlocks(toArray(res.data?.data));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to create block');
    } finally {
      setBlockSaving(false);
    }
  };

  const removeBlock = async (block) => {
    const ok = await confirm({
      title: 'Remove block?',
      message: `Remove this block (${block.startAt} → ${block.endAt})?`,
      confirmLabel: 'Remove',
      variant: 'danger',
    });
    if (!ok) return;
    try {
      await adminService.deleteRoomBlock(block.id);
      toast.success('Block removed');
      setBlocks(blocks.filter(b => b.id !== block.id));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to delete block');
    }
  };

  const fetchRooms = async () => {
    try {
      const res = await adminService.getVenueRooms();
      setRooms(toArray(res.data?.data));
    } catch {
      toast.error('Failed to load venue rooms');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchRooms(); }, []);

  const resetForm = () => {
    setForm(emptyForm);
    setImageUrlsText('');
    setShowForm(false);
    setEditId(null);
  };

  const handleEdit = (room) => {
    setEditId(room.id);
    const imgs = Array.isArray(room.imageUrls) ? room.imageUrls : [];
    setForm({
      name: room.name,
      roomType: room.roomType || 'MAIN_HALL',
      capacity: room.capacity || 10,
      description: room.description || '',
      sortOrder: room.sortOrder || 0,
      priceAddition: Number(room.priceAddition || 0),
      active: room.active !== false,
      imageUrls: imgs,
    });
    setImageUrlsText(imgs.join('\n'));
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (saving) return;
    if (!form.name.trim()) { toast.error('Room name is required'); return; }
    if (!Number.isFinite(form.capacity) || form.capacity < 1) { toast.error('Capacity must be at least 1'); return; }
    if (!Number.isFinite(form.priceAddition) || form.priceAddition < 0) {
      toast.error('Price addition must be ≥ 0'); return;
    }
    const imageUrls = imageUrlsText
      .split(/\r?\n/)
      .map(s => s.trim())
      .filter(Boolean);
    const payload = { ...form, imageUrls };
    setSaving(true);
    try {
      if (editId) {
        await adminService.updateVenueRoom(editId, payload);
        toast.success('Room updated');
      } else {
        await adminService.createVenueRoom(payload);
        toast.success(isSuperAdmin
          ? 'Room created (auto-approved)'
          : 'Room created — awaiting super-admin approval');
      }
      resetForm();
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to save room');
    } finally {
      setSaving(false);
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
    const ok = await confirm({
      title: 'Delete room?',
      message: `Delete room "${room.name}" permanently? This cannot be undone.`,
      confirmLabel: 'Delete',
      variant: 'danger',
    });
    if (!ok) return;
    try {
      await adminService.deleteVenueRoom(room.id);
      toast.success('Room deleted');
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to delete room');
    }
  };

  const handleApprove = async (room) => {
    const ok = await confirm({
      title: 'Approve room?',
      message: `Approve room "${room.name}"? It will become bookable immediately.`,
      confirmLabel: 'Approve',
      variant: 'primary',
    });
    if (!ok) return;
    try {
      await adminService.approveVenueRoom(room.id);
      toast.success('Room approved');
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to approve room');
    }
  };

  const handleReject = async (room) => {
    const result = await confirm({
      title: 'Reject room?',
      message: `Reject "${room.name}". Provide a reason (visible to admins).`,
      confirmLabel: 'Reject',
      variant: 'danger',
      withReason: true,
      reasonLabel: 'Rejection reason',
      reasonRequired: true,
    });
    if (!result) return;
    try {
      await adminService.rejectVenueRoom(room.id, result.reason);
      toast.success('Room rejected');
      fetchRooms();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to reject room');
    }
  };

  if (loading) return <div className="loading"><div className="spinner"></div></div>;

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1>Venue Rooms</h1>
          <p>
            Manage bookable rooms and spaces. Customers can optionally choose a room when booking.
            {!isSuperAdmin && <> New rooms you create require <strong>super-admin approval</strong> before they become bookable.</>}
          </p>
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
                <label>Price Addition (₹)</label>
                <input type="number" min="0" step="0.01" value={form.priceAddition}
                  onChange={(e) => setForm({ ...form, priceAddition: Number(e.target.value) })}
                  placeholder="0.00" />
                <small style={{ color: 'var(--text-muted)' }}>Added to the booking total when this room is selected.</small>
              </div>
              <div className="input-group">
                <label>Sort Order</label>
                <input type="number" min="0" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} />
              </div>
              <div className="input-group">
                <label>
                  <input type="checkbox" checked={form.active}
                    onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Active
                </label>
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <label>Description</label>
                <textarea rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Optional description visible to customers" />
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <label>Photos (one URL per line)</label>
                <textarea rows={3} value={imageUrlsText}
                  onChange={(e) => setImageUrlsText(e.target.value)}
                  placeholder={'https://cdn.example.com/room1.jpg\nhttps://cdn.example.com/room2.jpg'} />
                {imageUrlsText.trim() && (
                  <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem', flexWrap: 'wrap' }}>
                    {imageUrlsText.split(/\r?\n/).map(s => s.trim()).filter(Boolean).slice(0, 8).map((u, i) => (
                      <img key={i} src={u} alt="" style={{ width: 64, height: 48, objectFit: 'cover', borderRadius: 4, border: '1px solid var(--border)' }}
                        onError={(e) => { e.currentTarget.style.opacity = '0.3'; }} />
                    ))}
                  </div>
                )}
              </div>
            </div>
            <div className="adm-form-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : editId ? 'Update Room' : 'Create Room'}</button>
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
                <th>+₹ Price</th>
                <th>Approval</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rooms.map(room => {
                const status = room.status || 'APPROVED';
                const badge = STATUS_BADGES[status] || STATUS_BADGES.APPROVED;
                const firstThumb = Array.isArray(room.imageUrls) && room.imageUrls.length > 0 ? room.imageUrls[0] : null;
                return (
                  <tr key={room.id} className={room.active ? '' : 'adm-row-inactive'}>
                    <td>
                      <div style={{ display: 'flex', gap: '0.6rem', alignItems: 'center' }}>
                        {firstThumb && (
                          <img src={firstThumb} alt="" style={{ width: 48, height: 36, objectFit: 'cover', borderRadius: 4, border: '1px solid var(--border)' }}
                            onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                        )}
                        <div>
                          <strong>{room.name}</strong>
                          {room.description && <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', margin: '0.2rem 0 0' }}>{room.description}</p>}
                        </div>
                      </div>
                    </td>
                    <td>{(room.roomType || '').replace(/_/g, ' ')}</td>
                    <td>{room.capacity}</td>
                    <td>{Number(room.priceAddition || 0) > 0 ? `+₹${Number(room.priceAddition).toLocaleString()}` : '—'}</td>
                    <td>
                      <span className={`badge ${badge.cls}`}>{badge.label}</span>
                      {status === 'REJECTED' && room.approvalRejectionReason && (
                        <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', margin: '0.3rem 0 0' }}>
                          {room.approvalRejectionReason}
                        </p>
                      )}
                    </td>
                    <td>
                      <span className={`badge ${room.active ? 'badge-success' : 'badge-danger'}`}>
                        {room.active ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
                        <button className="btn btn-secondary btn-sm" onClick={() => handleEdit(room)} title="Edit"><FiEdit2 /></button>
                        <button className="btn btn-secondary btn-sm" onClick={() => openBlocks(room)} title="Maintenance blocks"><FiClock /></button>
                        <button className={`btn btn-sm ${room.active ? 'btn-danger' : ''}`}
                          style={!room.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                          onClick={() => handleToggle(room.id)}
                          title={room.active ? 'Deactivate' : 'Activate'}>
                          {room.active ? <FiToggleLeft /> : <FiToggleRight />}
                        </button>
                        {isSuperAdmin && status !== 'APPROVED' && (
                          <button className="btn btn-sm" style={{ background: 'var(--success)', color: '#fff' }}
                            onClick={() => handleApprove(room)} title="Approve">
                            <FiCheck />
                          </button>
                        )}
                        {isSuperAdmin && status !== 'REJECTED' && (
                          <button className="btn btn-sm btn-danger" onClick={() => handleReject(room)} title="Reject">
                            <FiSlash />
                          </button>
                        )}
                        {!room.active && (
                          <button className="btn btn-sm adm-danger-btn" onClick={() => handleDelete(room)} title="Delete"><FiTrash2 /></button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {blocksRoom && (
        <div className="adm-modal-overlay" onClick={closeBlocks}>
          <div className="adm-modal" style={{ maxWidth: 720 }} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <h3 style={{ margin: 0 }}>Maintenance Blocks — {blocksRoom.name}</h3>
              <button className="btn btn-secondary btn-sm" onClick={closeBlocks} title="Close"><FiX /></button>
            </div>
            <div>
              <p style={{ color: 'var(--text-muted)', marginTop: 0 }}>
                While a block is active the room behaves as fully booked. Existing bookings are <strong>not</strong> cancelled — reschedule them separately if needed.
              </p>
              <form onSubmit={submitBlock} className="adm-form" style={{ padding: 0, boxShadow: 'none', marginBottom: '1rem' }}>
                <div className="adm-grid-2">
                  <div className="input-group">
                    <label>Start *</label>
                    <input type="datetime-local" value={blockForm.startAt}
                      onChange={(e) => setBlockForm({ ...blockForm, startAt: e.target.value })} required />
                  </div>
                  <div className="input-group">
                    <label>End *</label>
                    <input type="datetime-local" value={blockForm.endAt}
                      onChange={(e) => setBlockForm({ ...blockForm, endAt: e.target.value })} required />
                  </div>
                  <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                    <label>Reason</label>
                    <input type="text" maxLength={500} value={blockForm.reason}
                      onChange={(e) => setBlockForm({ ...blockForm, reason: e.target.value })}
                      placeholder="e.g., Deep cleaning, AC repair, private hold" />
                  </div>
                </div>
                <div className="adm-form-actions">
                  <button type="submit" className="btn btn-primary" disabled={blockSaving}>
                    {blockSaving ? 'Adding…' : <><FiPlus /> Add Block</>}
                  </button>
                </div>
              </form>

              {blocksLoading ? (
                <div className="loading"><div className="spinner"></div></div>
              ) : blocks.length === 0 ? (
                <p style={{ textAlign: 'center', color: 'var(--text-muted)' }}>No blocks scheduled.</p>
              ) : (
                <table className="adm-table">
                  <thead>
                    <tr><th>Start</th><th>End</th><th>Reason</th><th></th></tr>
                  </thead>
                  <tbody>
                    {blocks.map(b => (
                      <tr key={b.id}>
                        <td>{new Date(b.startAt).toLocaleString()}</td>
                        <td>{new Date(b.endAt).toLocaleString()}</td>
                        <td>{b.reason || '—'}</td>
                        <td>
                          <button className="btn btn-sm btn-danger" onClick={() => removeBlock(b)} title="Delete block">
                            <FiTrash2 />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
