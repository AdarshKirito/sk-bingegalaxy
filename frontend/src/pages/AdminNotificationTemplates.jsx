import { useEffect, useMemo, useState, useCallback } from 'react';
import { toast } from 'react-toastify';
import {
  FiMail, FiMessageSquare, FiSend, FiPlus, FiEdit2, FiTrash2, FiCheck,
  FiRefreshCw, FiSave, FiX, FiZap, FiCopy,
} from 'react-icons/fi';
import { notificationService, toArray } from '../services/endpoints';
import { useConfirm } from '../components/ui/ConfirmProvider';
import './AdminPages.css';

/**
 * Admin notification templates console.
 *
 * Two backing stores:
 *   • Email/SMS templates  — versioned, channel-scoped (EMAIL/SMS). Each
 *     {name,channel} pair has many versions; one is "active" and used for
 *     outbound sends. Activating a new version is a one-click flip.
 *   • WhatsApp templates   — Twilio Content SIDs. Pre-approved by Meta and
 *     referenced by templateName so booking events can fire HSM messages.
 *
 * UX principles applied:
 *   • Two top tabs split the two stores so admins never confuse them.
 *   • Master/detail layout: list left, editor right. Saves are in-place.
 *   • Channel is rendered as a chip, version as a small badge, active as a
 *     green pill so state is glanceable in the list.
 *   • All destructive actions confirm via window.confirm with explicit copy.
 *   • Errors surface as toasts; in-flight saves disable the editor.
 */

const TABS = [
  { id: 'email-sms', label: 'Email / SMS', icon: <FiMail /> },
  { id: 'whatsapp',  label: 'WhatsApp',    icon: <FiMessageSquare /> },
];

const EMAIL_SMS_CHANNELS = ['EMAIL', 'SMS'];

const blankTemplate = () => ({
  id: null,
  name: '',
  channel: 'EMAIL',
  version: 1,
  subject: '',
  content: '',
  active: false,
});

const blankWhatsApp = () => ({
  id: null,
  templateName: '',
  contentSid: '',
  description: '',
  active: true,
});

export default function AdminNotificationTemplates() {
  const confirm = useConfirm();
  const [tab, setTab] = useState('email-sms');

  // ── Email/SMS template state ──────────────────────────────
  const [tmpls, setTmpls]                 = useState([]);
  const [tmplLoading, setTmplLoading]     = useState(false);
  const [tmplFilter, setTmplFilter]       = useState({ name: '', channel: '' });
  const [tmplDraft, setTmplDraft]         = useState(blankTemplate());
  const [tmplBusy, setTmplBusy]           = useState(false);

  // ── WhatsApp template state ───────────────────────────────
  const [waTmpls, setWaTmpls]             = useState([]);
  const [waLoading, setWaLoading]         = useState(false);
  const [waDraft, setWaDraft]             = useState(blankWhatsApp());
  const [waBusy, setWaBusy]               = useState(false);

  // ── Loaders ──────────────────────────────────────────────
  const loadTmpls = useCallback(async () => {
    setTmplLoading(true);
    try {
      const params = {};
      if (tmplFilter.name)    params.name    = tmplFilter.name;
      if (tmplFilter.channel) params.channel = tmplFilter.channel;
      const res = await notificationService.listTemplates(params);
      setTmpls(toArray(res.data?.data));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load templates');
    } finally {
      setTmplLoading(false);
    }
  }, [tmplFilter.name, tmplFilter.channel]);

  const loadWaTmpls = useCallback(async () => {
    setWaLoading(true);
    try {
      const res = await notificationService.listWhatsAppTemplates({ page: 0, size: 100 });
      // Spring Page<T> returns { content: [...] } inside data
      const page = res.data?.data;
      setWaTmpls(toArray(page?.content || page || []));
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load WhatsApp templates');
    } finally {
      setWaLoading(false);
    }
  }, []);

  useEffect(() => { if (tab === 'email-sms') loadTmpls(); }, [tab, loadTmpls]);
  useEffect(() => { if (tab === 'whatsapp')  loadWaTmpls(); }, [tab, loadWaTmpls]);

  // ── Email/SMS — group by name, latest version first ──────
  const groupedTmpls = useMemo(() => {
    const byKey = new Map();
    for (const t of tmpls) {
      const key = `${t.name}::${t.channel}`;
      if (!byKey.has(key)) byKey.set(key, []);
      byKey.get(key).push(t);
    }
    for (const list of byKey.values()) list.sort((a, b) => (b.version ?? 0) - (a.version ?? 0));
    return Array.from(byKey.entries()).map(([key, versions]) => {
      const [name, channel] = key.split('::');
      const active = versions.find(v => v.active) || versions[0];
      return { key, name, channel, versions, active };
    }).sort((a, b) => a.name.localeCompare(b.name));
  }, [tmpls]);

  const onPickTmpl = (t) => setTmplDraft({ ...t, subject: t.subject || '', content: t.content || '' });

  const onSaveTmpl = async () => {
    if (!tmplDraft.name || !tmplDraft.channel) {
      toast.warn('Name and channel are required');
      return;
    }
    if (!tmplDraft.content) {
      toast.warn('Content is required');
      return;
    }
    setTmplBusy(true);
    try {
      // Server accepts an upsert: same {name,channel,version} updates in place,
      // a new version creates a new row. Bumping version is a manual decision.
      await notificationService.upsertTemplate(tmplDraft);
      toast.success(`Saved ${tmplDraft.name} v${tmplDraft.version}`);
      await loadTmpls();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    } finally {
      setTmplBusy(false);
    }
  };

  const onActivate = async (t) => {
    const ok = await confirm({
      title: `Activate ${t.name} v${t.version}?`,
      message: `This deactivates the currently active version on ${t.channel} and immediately starts using v${t.version} for all outbound ${t.channel.toLowerCase()} messages. Recently scheduled messages may already be in flight.`,
      confirmLabel: `Activate v${t.version}`,
      variant: 'primary',
    });
    if (!ok) return;
    setTmplBusy(true);
    try {
      await notificationService.activateTemplate({ name: t.name, channel: t.channel, version: t.version });
      toast.success(`v${t.version} is now active for ${t.name} (${t.channel})`);
      await loadTmpls();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Activate failed');
    } finally {
      setTmplBusy(false);
    }
  };

  const onCloneAsNewVersion = (t) => {
    const next = (t.versions[0]?.version || 1) + 1;
    const base = t.active || t.versions[0];
    setTmplDraft({
      id: null,
      name: base.name,
      channel: base.channel,
      version: next,
      subject: base.subject || '',
      content: base.content || '',
      active: false,
    });
    toast.info(`Drafting v${next} of ${base.name} — edit and Save`);
  };

  // ── WhatsApp handlers ─────────────────────────────────────
  const onPickWa = (t) => setWaDraft({ ...t });

  const onSaveWa = async () => {
    if (!waDraft.templateName || !waDraft.contentSid) {
      toast.warn('Template name and Content SID are required');
      return;
    }
    setWaBusy(true);
    try {
      if (waDraft.id) {
        await notificationService.updateWhatsAppTemplate(waDraft.id, waDraft);
        toast.success('WhatsApp template updated');
      } else {
        await notificationService.createWhatsAppTemplate(waDraft);
        toast.success('WhatsApp template created');
      }
      setWaDraft(blankWhatsApp());
      await loadWaTmpls();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Save failed');
    } finally {
      setWaBusy(false);
    }
  };

  const onDeleteWa = async (t) => {
    const ok = await confirm({
      title: `Delete WhatsApp template “${t.templateName}”?`,
      message: 'Any outbound messages still bound to this Content SID will fail until you re-map them. This action cannot be undone.',
      confirmLabel: 'Delete template',
      variant: 'danger',
    });
    if (!ok) return;
    setWaBusy(true);
    try {
      await notificationService.deleteWhatsAppTemplate(t.id);
      toast.success('Deleted');
      if (waDraft.id === t.id) setWaDraft(blankWhatsApp());
      await loadWaTmpls();
    } catch (err) {
      toast.error(err.response?.data?.message || 'Delete failed');
    } finally {
      setWaBusy(false);
    }
  };

  const copyToClipboard = (val, label = 'Value') => {
    navigator.clipboard?.writeText(val).then(
      () => toast.success(`${label} copied`),
      () => toast.error('Copy failed'),
    );
  };

  return (
    <div className="container adm-shell">
      <div className="adm-page-header">
        <div>
          <h1><FiSend /> Notification Templates</h1>
          <p>Manage versioned email/SMS bodies and WhatsApp Content SIDs that drive every outbound message.</p>
        </div>
      </div>

      <div role="tablist" style={{
        display: 'flex', gap: '0.5rem',
        borderBottom: '1px solid var(--border)',
        marginBottom: '1rem', flexWrap: 'wrap',
      }}>
        {TABS.map(t => {
          const active = t.id === tab;
          return (
            <button
              key={t.id}
              role="tab"
              aria-selected={active}
              onClick={() => setTab(t.id)}
              style={{
                padding: '0.6rem 1rem', border: 'none', cursor: 'pointer',
                background: 'transparent',
                color: active ? 'var(--primary)' : 'var(--text-secondary)',
                borderBottom: `2px solid ${active ? 'var(--primary)' : 'transparent'}`,
                fontWeight: active ? 600 : 500,
                display: 'inline-flex', alignItems: 'center', gap: '0.4rem',
              }}
            >
              {t.icon} {t.label}
            </button>
          );
        })}
      </div>

      {tab === 'email-sms' && (
        <EmailSmsPanel
          loading={tmplLoading}
          groups={groupedTmpls}
          filter={tmplFilter}
          onFilter={setTmplFilter}
          draft={tmplDraft}
          onDraft={setTmplDraft}
          busy={tmplBusy}
          onSave={onSaveTmpl}
          onPick={onPickTmpl}
          onActivate={onActivate}
          onClone={onCloneAsNewVersion}
          onRefresh={loadTmpls}
          onNew={() => setTmplDraft(blankTemplate())}
        />
      )}

      {tab === 'whatsapp' && (
        <WhatsAppPanel
          loading={waLoading}
          rows={waTmpls}
          draft={waDraft}
          onDraft={setWaDraft}
          busy={waBusy}
          onSave={onSaveWa}
          onPick={onPickWa}
          onDelete={onDeleteWa}
          onCopy={copyToClipboard}
          onRefresh={loadWaTmpls}
          onNew={() => setWaDraft(blankWhatsApp())}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Email / SMS panel
// ─────────────────────────────────────────────────────────────
function EmailSmsPanel({
  loading, groups, filter, onFilter, draft, onDraft, busy, onSave,
  onPick, onActivate, onClone, onRefresh, onNew,
}) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(320px, 1fr) minmax(380px, 1.1fr)', gap: '1.25rem' }}>
      {/* List */}
      <section>
        <div className="admin-toolbar" style={{ marginBottom: '0.75rem' }}>
          <div className="admin-toolbar-group">
            <input
              type="text"
              placeholder="Filter by name…"
              value={filter.name}
              onChange={(e) => onFilter(f => ({ ...f, name: e.target.value }))}
              className="admin-select"
              style={{ minWidth: 180 }}
            />
            <select
              value={filter.channel}
              onChange={(e) => onFilter(f => ({ ...f, channel: e.target.value }))}
              className="admin-select"
            >
              <option value="">All channels</option>
              {EMAIL_SMS_CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div className="admin-toolbar-group" style={{ marginLeft: 'auto', gap: '0.4rem' }}>
            <button className="btn btn-secondary btn-sm" onClick={onRefresh} disabled={loading}>
              <FiRefreshCw /> Refresh
            </button>
            <button className="btn btn-primary btn-sm" onClick={onNew}>
              <FiPlus /> New
            </button>
          </div>
        </div>

        {loading ? (
          <div className="admin-loading">Loading templates…</div>
        ) : groups.length === 0 ? (
          <div className="admin-empty-state">
            <FiMail size={40} />
            <h3>No templates yet</h3>
            <p>Create the first email or SMS body using the editor on the right.</p>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
            {groups.map(g => (
              <div key={g.key} className="adm-card" style={{ padding: '0.85rem 1rem' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.45rem', flexWrap: 'wrap' }}>
                  <strong style={{ fontSize: '0.95rem' }}>{g.name}</strong>
                  <span className={`badge ${g.channel === 'EMAIL' ? 'badge-info' : 'badge-warning'}`}>{g.channel}</span>
                  {g.active && <span className="badge badge-success">v{g.active.version} active</span>}
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginLeft: 'auto' }}>
                    {g.versions.length} version{g.versions.length === 1 ? '' : 's'}
                  </span>
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem' }}>
                  {g.versions.map(v => {
                    const isActive = v.active;
                    const isPicked = draft.id === v.id;
                    return (
                      <button
                        key={v.id || `${v.name}-${v.channel}-${v.version}`}
                        type="button"
                        onClick={() => onPick(v)}
                        title={isActive ? 'Active version' : 'View / edit this version'}
                        style={{
                          padding: '0.3rem 0.6rem',
                          borderRadius: 6,
                          border: `1px solid ${isPicked ? 'var(--primary)' : 'var(--border)'}`,
                          background: isPicked ? 'rgba(59, 130, 246, 0.12)' : 'transparent',
                          color: 'var(--text-primary)',
                          cursor: 'pointer',
                          fontSize: '0.8rem',
                          display: 'inline-flex', alignItems: 'center', gap: '0.3rem',
                        }}
                      >
                        v{v.version} {isActive && <FiCheck style={{ color: 'var(--success, #10b981)' }} />}
                      </button>
                    );
                  })}
                  <span style={{ flex: 1 }} />
                  {!g.active?.active || g.active.version !== g.versions[0].version ? (
                    <button className="btn btn-sm btn-secondary" onClick={() => onActivate(g.versions[0])} disabled={busy}
                            title={`Activate v${g.versions[0].version}`}>
                      <FiZap /> Activate latest
                    </button>
                  ) : null}
                  <button className="btn btn-sm btn-secondary" onClick={() => onClone(g)} disabled={busy}>
                    <FiPlus /> New version
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Editor */}
      <section className="adm-card" style={{ padding: '1rem 1.1rem', alignSelf: 'flex-start', position: 'sticky', top: '1rem' }}>
        <h3 style={{ margin: '0 0 0.75rem', display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          {draft.id ? <><FiEdit2 /> Edit version</> : <><FiPlus /> New template</>}
        </h3>

        <div className="adm-form-grid">
          <label className="adm-form-field">
            <span>Name</span>
            <input
              type="text"
              value={draft.name}
              onChange={(e) => onDraft(d => ({ ...d, name: e.target.value }))}
              placeholder="e.g. BOOKING_CONFIRMED"
              disabled={!!draft.id}
              style={{ fontFamily: 'monospace' }}
            />
          </label>
          <label className="adm-form-field">
            <span>Channel</span>
            <select
              value={draft.channel}
              onChange={(e) => onDraft(d => ({ ...d, channel: e.target.value }))}
              disabled={!!draft.id}
            >
              {EMAIL_SMS_CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
          <label className="adm-form-field">
            <span>Version</span>
            <input
              type="number"
              min={1}
              value={draft.version}
              onChange={(e) => onDraft(d => ({ ...d, version: Number(e.target.value) || 1 }))}
              disabled={!!draft.id}
            />
          </label>
          {draft.channel === 'EMAIL' && (
            <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
              <span>Subject</span>
              <input
                type="text"
                value={draft.subject || ''}
                onChange={(e) => onDraft(d => ({ ...d, subject: e.target.value }))}
                placeholder="Subject (supports {{variables}})"
              />
            </label>
          )}
          <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
            <span>Content</span>
            <textarea
              rows={12}
              value={draft.content}
              onChange={(e) => onDraft(d => ({ ...d, content: e.target.value }))}
              placeholder={draft.channel === 'EMAIL'
                ? 'HTML body. Use {{customerName}}, {{bookingRef}}, etc.'
                : 'Plain text body (≤ 480 chars recommended for SMS).'}
              style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}
            />
          </label>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.75rem' }}>
          <button className="btn btn-primary" onClick={onSave} disabled={busy}>
            <FiSave /> {busy ? 'Saving…' : 'Save'}
          </button>
          {draft.id && (
            <button className="btn btn-secondary" onClick={() => onDraft(blankTemplate())} disabled={busy}>
              <FiX /> Cancel
            </button>
          )}
          <span style={{ marginLeft: 'auto', color: 'var(--text-secondary)', fontSize: '0.8rem', alignSelf: 'center' }}>
            {draft.active ? 'Currently active' : 'Inactive (won\u2019t send until activated)'}
          </span>
        </div>
      </section>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// WhatsApp panel
// ─────────────────────────────────────────────────────────────
function WhatsAppPanel({
  loading, rows, draft, onDraft, busy, onSave, onPick, onDelete, onCopy, onRefresh, onNew,
}) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(360px, 1fr) minmax(380px, 1fr)', gap: '1.25rem' }}>
      <section>
        <div className="admin-toolbar" style={{ marginBottom: '0.75rem' }}>
          <div className="admin-toolbar-group" style={{ marginLeft: 'auto', gap: '0.4rem' }}>
            <button className="btn btn-secondary btn-sm" onClick={onRefresh} disabled={loading}>
              <FiRefreshCw /> Refresh
            </button>
            <button className="btn btn-primary btn-sm" onClick={onNew}>
              <FiPlus /> New
            </button>
          </div>
        </div>

        {loading ? (
          <div className="admin-loading">Loading WhatsApp templates…</div>
        ) : rows.length === 0 ? (
          <div className="admin-empty-state">
            <FiMessageSquare size={40} />
            <h3>No WhatsApp templates</h3>
            <p>Add a Twilio Content SID to start sending pre-approved HSM messages.</p>
          </div>
        ) : (
          <div className="adm-table-wrap">
            <table className="adm-table">
              <thead><tr>
                <th>Template</th><th>Content SID</th><th>Status</th><th></th>
              </tr></thead>
              <tbody>
                {rows.map(r => {
                  const picked = draft.id === r.id;
                  return (
                    <tr key={r.id} style={picked ? { background: 'rgba(59, 130, 246, 0.08)' } : {}}>
                      <td>
                        <strong>{r.templateName}</strong>
                        {r.description && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{r.description}</div>}
                      </td>
                      <td>
                        <code style={{ fontSize: '0.8rem' }}>{r.contentSid}</code>{' '}
                        <button type="button" className="btn-icon" title="Copy SID" onClick={() => onCopy(r.contentSid, 'SID')}
                                style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-secondary)' }}>
                          <FiCopy />
                        </button>
                      </td>
                      <td>
                        <span className={`badge ${r.active ? 'badge-success' : 'badge-secondary'}`}>
                          {r.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td style={{ display: 'flex', gap: '0.3rem' }}>
                        <button className="btn btn-sm btn-secondary" onClick={() => onPick(r)} disabled={busy}>
                          <FiEdit2 />
                        </button>
                        <button className="btn btn-sm btn-danger" onClick={() => onDelete(r)} disabled={busy}>
                          <FiTrash2 />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="adm-card" style={{ padding: '1rem 1.1rem', alignSelf: 'flex-start', position: 'sticky', top: '1rem' }}>
        <h3 style={{ margin: '0 0 0.75rem', display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          {draft.id ? <><FiEdit2 /> Edit template</> : <><FiPlus /> New WhatsApp template</>}
        </h3>

        <div className="adm-form-grid">
          <label className="adm-form-field">
            <span>Template name</span>
            <input
              type="text"
              value={draft.templateName}
              onChange={(e) => onDraft(d => ({ ...d, templateName: e.target.value }))}
              placeholder="e.g. booking_confirmed_v2"
              style={{ fontFamily: 'monospace' }}
            />
          </label>
          <label className="adm-form-field">
            <span>Content SID</span>
            <input
              type="text"
              value={draft.contentSid}
              onChange={(e) => onDraft(d => ({ ...d, contentSid: e.target.value }))}
              placeholder="HX..."
              style={{ fontFamily: 'monospace' }}
            />
          </label>
          <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
            <span>Description</span>
            <input
              type="text"
              value={draft.description || ''}
              onChange={(e) => onDraft(d => ({ ...d, description: e.target.value }))}
              placeholder="Internal note — when this template fires, language, etc."
            />
          </label>
          <label className="adm-form-field" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexDirection: 'row' }}>
            <input
              type="checkbox"
              checked={!!draft.active}
              onChange={(e) => onDraft(d => ({ ...d, active: e.target.checked }))}
            />
            <span>Active — outbound senders will use this Content SID</span>
          </label>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.75rem' }}>
          <button className="btn btn-primary" onClick={onSave} disabled={busy}>
            <FiSave /> {busy ? 'Saving…' : 'Save'}
          </button>
          {draft.id && (
            <button className="btn btn-secondary" onClick={() => onDraft(blankWhatsApp())} disabled={busy}>
              <FiX /> Cancel
            </button>
          )}
        </div>
      </section>
    </div>
  );
}
