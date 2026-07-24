import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import useAuthStore from '../stores/authStore';
import { toast } from 'react-toastify';
import { FiSend, FiTrash2, FiArrowLeft, FiEdit3, FiInbox, FiCornerUpLeft, FiX, FiExternalLink, FiInfo } from 'react-icons/fi';
import RecipientPicker from '../components/RecipientPicker';
import { AttachButton, AttachmentPreview, AttachmentView } from '../components/MessageAttachment';
import './AdminMessages.css';

/** Notify the bell (and any listener) that read-state changed so it can refresh now. */
const pingNotifications = () => window.dispatchEvent(new Event('notifications:refresh'));

const roleLabel = (r) => ({ SUPER_ADMIN: 'Super Admin', ADMIN: 'Admin', CUSTOMER: 'Customer', SYSTEM: 'System' }[r] || r || '');
const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : '');

export default function AdminMessages() {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore((s) => s.isSuperAdmin);
  const myName = (user ? `${user.firstName || ''} ${user.lastName || ''}`.trim() : '') || user?.email || 'Me';

  const [tab, setTab] = useState('inbox');       // inbox | sent
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [thread, setThread] = useState(null);    // { id, messages: [] }
  const [detail, setDetail] = useState(null);    // a system notification opened for reading
  const [replyText, setReplyText] = useState('');
  const [replyAttach, setReplyAttach] = useState(null);
  const [sending, setSending] = useState(false);
  const [compose, setCompose] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = tab === 'sent'
        ? await adminService.listSentMessages(0, 50)
        : await adminService.listAdminNotifications(0, 50);
      setItems(res?.data?.data?.content || []);
    } catch { toast.error('Failed to load messages'); } finally { setLoading(false); }
  }, [tab]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const openThread = useCallback(async (threadId) => {
    if (!threadId) return;
    setDetail(null);
    try {
      const res = await adminService.getMessageThread(threadId);
      const msgs = res?.data?.data || [];
      setThread({ id: threadId, messages: msgs });
      // Mark unread inbound messages in this thread as read, then refresh the bell.
      const unread = msgs.filter((m) => !m.mine && !m.readAt);
      if (unread.length) {
        await Promise.all(unread.map((m) => adminService.markAdminNotificationRead(m.id).catch(() => {})));
        setItems((prev) => prev.map((x) => (x.threadId === threadId || x.id === threadId) && !x.readAt ? { ...x, readAt: new Date().toISOString() } : x));
        pingNotifications();
      }
    } catch { toast.error('Failed to open conversation'); }
  }, []);

  // Deep link from the bell: ?thread=123 (open a conversation)
  useEffect(() => {
    const t = searchParams.get('thread');
    if (t) openThread(Number(t));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Deep link from the bell: ?notif=45 (open a specific notification's detail once the
  // inbox list has loaded). Handled once so it doesn't re-open when the user navigates away.
  const notifHandledRef = useRef(false);
  useEffect(() => {
    const n = searchParams.get('notif');
    if (!n || notifHandledRef.current || loading) return;
    const found = items.find((x) => String(x.id) === String(n));
    if (found) { notifHandledRef.current = true; openItem(found); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items, loading, searchParams]);

  const openItem = (m) => {
    if (m.system) {
      // Open a read-only detail view so every notification (binge approvals, alerts…)
      // is fully viewable in the inbox — not just a truncated list preview.
      setThread(null);
      setDetail(m);
      if (!m.readAt) {
        adminService.markAdminNotificationRead(m.id).catch(() => {});
        setItems((prev) => prev.map((x) => (x.id === m.id ? { ...x, readAt: new Date().toISOString() } : x)));
        pingNotifications();
      }
      return;
    }
    openThread(m.threadId || m.id);
  };

  const closeThread = () => { setThread(null); setDetail(null); setReplyText(''); setReplyAttach(null); setSearchParams({}); };

  const handleReply = async () => {
    if ((!replyText.trim() && !replyAttach) || !thread) return;
    const last = thread.messages[thread.messages.length - 1];
    setSending(true);
    try {
      await adminService.replyMessage(last.id, {
        senderName: myName,
        message: replyText.trim(),
        attachmentUrl: replyAttach?.url,
        attachmentType: replyAttach?.type,
        attachmentName: replyAttach?.name,
      });
      setReplyText('');
      setReplyAttach(null);
      await openThread(thread.id);
    } catch (e) { toast.error(e?.response?.data?.message || 'Failed to send reply'); }
    finally { setSending(false); }
  };

  const handleDelete = async (m, e) => {
    e.stopPropagation();
    try { await adminService.deleteAdminNotification(m.id); setItems((prev) => prev.filter((x) => x.id !== m.id)); }
    catch { toast.error('Failed to delete'); }
  };

  const onSent = (count = 1) => {
    setCompose(false);
    if (tab === 'sent') fetchList();
    toast.success(count > 1 ? `Message sent to ${count} recipients` : 'Message sent');
  };

  return (
    <div className="msg-page">
      <div className="msg-header">
        <div>
          <h1><FiInbox /> Messages</h1>
          <p className="page-subtitle">Send and reply to super-admins, admins, and customers.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setCompose(true)}><FiEdit3 /> Compose</button>
      </div>

      <div className="msg-body">
        <aside className="msg-list-pane">
          <div className="msg-tabs">
            <button className={tab === 'inbox' ? 'active' : ''} onClick={() => { setTab('inbox'); closeThread(); }}>Inbox</button>
            <button className={tab === 'sent' ? 'active' : ''} onClick={() => { setTab('sent'); closeThread(); }}>Sent</button>
          </div>
          <div className="msg-list">
            {loading ? <p className="msg-muted">Loading…</p>
              : items.length === 0 ? <p className="msg-muted">No messages.</p>
              : items.map((m) => (
                <div key={m.id}
                  className={`msg-list-item${!m.readAt && tab === 'inbox' ? ' unread' : ''}${thread && (thread.id === (m.threadId || m.id)) ? ' selected' : ''}`}
                  role="button" tabIndex={0}
                  onClick={() => openItem(m)}
                  onKeyDown={(e) => e.key === 'Enter' && openItem(m)}>
                  <div className="msg-list-row">
                    <span className="msg-from">
                      <span>{tab === 'sent'
                        ? `To: ${m.recipientName || roleLabel(m.recipientRole)}`
                        : (m.system ? (m.senderName || 'System') : (m.senderName || roleLabel(m.senderRole)))}</span>
                      {tab === 'inbox' && !m.system && <span className="msg-role-badge">{roleLabel(m.senderRole)}</span>}
                    </span>
                    <span className="msg-time">{fmt(m.createdAt)}</span>
                  </div>
                  <div className="msg-subject">{m.title}</div>
                  <div className="msg-preview">{m.message}</div>
                  <button className="msg-del" aria-label="Delete" title="Delete" onClick={(e) => handleDelete(m, e)}><FiTrash2 /></button>
                </div>
              ))}
          </div>
        </aside>

        <section className="msg-thread-pane">
          {detail ? (
            <>
              <div className="msg-thread-head">
                <button className="btn btn-secondary btn-sm msg-back" onClick={closeThread}><FiArrowLeft /> Back</button>
                <h2>{detail.title || 'Notification'}</h2>
              </div>
              <div className="msg-thread-scroll">
                <div className="msg-detail">
                  <div className="msg-detail-meta">
                    <span className="msg-role-badge"><FiInfo style={{ marginRight: 4 }} />System</span>
                    <span>{fmt(detail.createdAt)}</span>
                  </div>
                  <div className="msg-detail-body">{detail.message}</div>
                  <AttachmentView url={detail.attachmentUrl} type={detail.attachmentType} name={detail.attachmentName} />
                  {detail.actionUrl && (
                    <button className="btn btn-secondary btn-sm" style={{ marginTop: '0.75rem' }}
                      onClick={() => navigate(detail.actionUrl)}>
                      <FiExternalLink /> Go to related
                    </button>
                  )}
                </div>
              </div>
            </>
          ) : !thread ? (
            <div className="msg-empty-thread">
              <FiInbox size={40} />
              <p>Select a conversation or notification to read it, or compose a new message.</p>
            </div>
          ) : (
            <>
              <div className="msg-thread-head">
                <button className="btn btn-secondary btn-sm msg-back" onClick={closeThread}><FiArrowLeft /> Back</button>
                <h2>{thread.messages[0]?.title || 'Conversation'}</h2>
              </div>
              <div className="msg-thread-scroll">
                {thread.messages.map((m) => (
                  <div key={m.id} className={`msg-bubble ${m.mine ? 'mine' : 'theirs'}`}>
                    <div className="msg-bubble-meta">
                      <strong>{m.mine ? 'You' : (m.senderName || roleLabel(m.senderRole))}</strong>
                      {!m.mine && <span className="msg-role-badge">{roleLabel(m.senderRole)}</span>}
                      <span>{fmt(m.createdAt)}</span>
                    </div>
                    {m.message && <div className="msg-bubble-body">{m.message}</div>}
                    <AttachmentView url={m.attachmentUrl} type={m.attachmentType} name={m.attachmentName} />
                  </div>
                ))}
              </div>
              <div className="msg-reply">
                <AttachButton onUploaded={setReplyAttach} disabled={sending} />
                <div className="msg-reply-main">
                  <textarea rows={2} value={replyText} placeholder="Write a reply…"
                    onChange={(e) => setReplyText(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) handleReply(); }} />
                  <AttachmentPreview attachment={replyAttach} onRemove={() => setReplyAttach(null)} />
                </div>
                <button className="btn btn-primary" disabled={sending || (!replyText.trim() && !replyAttach)} onClick={handleReply}>
                  <FiCornerUpLeft /> {sending ? 'Sending…' : 'Reply'}
                </button>
              </div>
            </>
          )}
        </section>
      </div>

      {compose && <ComposeModal myName={myName} isSuperAdmin={isSuperAdmin} onClose={() => setCompose(false)} onSent={onSent} />}
    </div>
  );
}

/**
 * Compose a message to one or more recipients. Recipients are chosen with a
 * Gmail-style token field ({@link RecipientPicker}) that autocompletes staff and
 * customers and offers broadcast groups — scaling cleanly to any customer count.
 * Each selected recipient receives their own thread via a single bulk send.
 */
function ComposeModal({ myName, isSuperAdmin, onClose, onSent }) {
  const [selected, setSelected] = useState([]); // [{ key, recipientUserId, recipientRole, recipientName }]
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [attach, setAttach] = useState(null);
  const [sending, setSending] = useState(false);

  const submit = async () => {
    if (!message.trim() && !attach) { toast.error('Add a message or an attachment'); return; }
    if (selected.length === 0) { toast.error('Pick at least one recipient'); return; }
    // Guard against accidental mass broadcasts (recipientUserId === null = "everyone in role").
    const broadcasts = selected.filter((s) => s.recipientUserId == null);
    if (broadcasts.length > 0) {
      const names = broadcasts.map((b) => b.recipientName).join(', ');
      // eslint-disable-next-line no-alert
      if (!window.confirm(`This will message everyone in: ${names}. Send to all of them?`)) return;
    }
    setSending(true);
    try {
      await adminService.sendBulkMessage({
        recipients: selected.map((s) => ({
          recipientUserId: s.recipientUserId, recipientRole: s.recipientRole, recipientName: s.recipientName,
        })),
        title: title.trim() || '(no subject)',
        message: message.trim(),
        senderName: myName,
        attachmentUrl: attach?.url,
        attachmentType: attach?.type,
        attachmentName: attach?.name,
      });
      onSent(selected.length);
    } catch (e) { toast.error(e?.response?.data?.message || 'Failed to send'); }
    finally { setSending(false); }
  };

  return (
    <div className="msg-modal-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }} role="dialog" aria-modal="true" aria-label="Compose message">
      <div className="msg-modal" onMouseDown={(e) => e.stopPropagation()}>
        <div className="msg-modal-head">
          <h3>New Message</h3>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><FiX /></button>
        </div>
        <div className="msg-modal-body">
          <label className="msg-field-label">To{selected.length > 0 ? ` · ${selected.length} recipient${selected.length > 1 ? 's' : ''}` : ''}</label>
          <RecipientPicker value={selected} onChange={setSelected} isSuperAdmin={isSuperAdmin} />

          <label className="msg-field-label">Subject</label>
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Subject (optional)" maxLength={200} />

          <label className="msg-field-label">Message</label>
          <textarea rows={5} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Write your message…" maxLength={1000} />
          <div className="msg-compose-attach">
            <AttachButton onUploaded={setAttach} disabled={sending} />
            <span className="msg-attach-hint">Attach a photo or video (max 25 MB)</span>
          </div>
          <AttachmentPreview attachment={attach} onRemove={() => setAttach(null)} />
        </div>
        <div className="msg-modal-foot">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={sending || selected.length === 0} onClick={submit}>
            <FiSend /> {sending ? 'Sending…' : `Send${selected.length ? ` to ${selected.length}` : ''}`}
          </button>
        </div>
      </div>
    </div>
  );
}
