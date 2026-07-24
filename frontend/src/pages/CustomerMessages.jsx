import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { messageService } from '../services/endpoints';
import useAuthStore from '../stores/authStore';
import useBingeStore from '../stores/bingeStore';
import { toast } from 'react-toastify';
import { FiSend, FiTrash2, FiArrowLeft, FiEdit3, FiInbox, FiCornerUpLeft, FiX } from 'react-icons/fi';
import { AttachButton, AttachmentPreview, AttachmentView } from '../components/MessageAttachment';
import PushToggle from '../components/PushToggle';
import { formatServerDateTime } from '../services/timeFormat';
import './AdminMessages.css';

const roleLabel = (r) => ({ SUPER_ADMIN: 'Support', ADMIN: 'Staff', CUSTOMER: 'You', SYSTEM: 'System' }[r] || r || '');
const fmt = (iso) => formatServerDateTime(iso);

/**
 * Customer-facing message inbox. Mirrors the admin mailbox but self-scoped: a
 * customer sees only their own mail and can reply within threads they participate in.
 *
 * New conversations route by WHERE the customer is (server-resolved recipient):
 * inside a venue's dashboard → that venue's admin; outside any venue → platform
 * Support (SUPER_ADMIN).
 */
export default function CustomerMessages() {
  const user = useAuthStore((s) => s.user);
  const { selectedBinge } = useBingeStore();
  const myName = (user ? `${user.firstName || ''} ${user.lastName || ''}`.trim() : '') || user?.email || 'Me';

  const [tab, setTab] = useState('inbox');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [thread, setThread] = useState(null);
  const [replyText, setReplyText] = useState('');
  const [replyAttach, setReplyAttach] = useState(null);
  const [sending, setSending] = useState(false);
  const [compose, setCompose] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = tab === 'sent'
        ? await messageService.listMySent(0, 50)
        : await messageService.listMyMessages(0, 50);
      setItems(res?.data?.data?.content || []);
    } catch { toast.error('Failed to load messages'); } finally { setLoading(false); }
  }, [tab]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const openThread = useCallback(async (threadId) => {
    if (!threadId) return;
    try {
      const res = await messageService.getMyThread(threadId);
      const msgs = res?.data?.data || [];
      setThread({ id: threadId, messages: msgs });
      msgs.filter((m) => !m.mine && !m.readAt).forEach((m) =>
        messageService.markMyRead(m.id).catch(() => {}));
    } catch { toast.error('Failed to open conversation'); }
  }, []);

  useEffect(() => {
    const t = searchParams.get('thread');
    if (t) openThread(Number(t));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openItem = (m) => {
    if (m.system) {
      if (!m.readAt) {
        messageService.markMyRead(m.id).catch(() => {});
        setItems((prev) => prev.map((x) => (x.id === m.id ? { ...x, readAt: new Date().toISOString() } : x)));
      }
      return;
    }
    openThread(m.threadId || m.id);
  };

  const closeThread = () => { setThread(null); setReplyText(''); setReplyAttach(null); setSearchParams({}); };

  const handleReply = async () => {
    if ((!replyText.trim() && !replyAttach) || !thread) return;
    const last = thread.messages[thread.messages.length - 1];
    setSending(true);
    try {
      await messageService.replyMyMessage(last.id, {
        senderName: myName, message: replyText.trim(),
        attachmentUrl: replyAttach?.url, attachmentType: replyAttach?.type, attachmentName: replyAttach?.name,
      });
      setReplyText('');
      setReplyAttach(null);
      await openThread(thread.id);
    } catch (e) { toast.error(e?.response?.data?.message || 'Failed to send reply'); }
    finally { setSending(false); }
  };

  const handleDelete = async (m, e) => {
    e.stopPropagation();
    try { await messageService.deleteMyMessage(m.id); setItems((prev) => prev.filter((x) => x.id !== m.id)); }
    catch { toast.error('Failed to delete'); }
  };

  const onSent = () => {
    setCompose(false);
    if (tab === 'sent') fetchList();
    toast.success(selectedBinge ? `Message sent to ${selectedBinge.name}` : 'Message sent to Support');
  };

  return (
    <div className="msg-page">
      <div className="msg-header">
        <div>
          <h1><FiInbox /> Messages</h1>
          <p className="page-subtitle">Read replies from our team and contact Support.</p>
        </div>
        <div className="msg-header-actions">
          <PushToggle compact />
          <button className="btn btn-primary" onClick={() => setCompose(true)}>
            <FiEdit3 /> {selectedBinge ? `Message ${selectedBinge.name}` : 'Contact Support'}
          </button>
        </div>
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
                      {tab === 'sent'
                        ? `To: ${roleLabel(m.recipientRole)}`
                        : (m.system ? 'System' : (m.senderName || roleLabel(m.senderRole)))}
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
          {!thread ? (
            <div className="msg-empty-thread">
              <FiInbox size={40} />
              <p>Select a conversation to read it, or contact Support.</p>
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

      {compose && (
        <SupportModal
          myName={myName}
          binge={selectedBinge}
          onClose={() => setCompose(false)}
          onSent={onSent}
        />
      )}
    </div>
  );
}

/**
 * Open a new conversation. When the customer is inside a venue (binge prop set)
 * the message routes to that venue's admin; otherwise to platform Support. The
 * actual recipient is resolved server-side from the binge id.
 */
function SupportModal({ myName, binge, onClose, onSent }) {
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [attach, setAttach] = useState(null);
  const [sending, setSending] = useState(false);
  const target = binge ? binge.name : 'Support';

  const submit = async () => {
    if (!message.trim() && !attach) { toast.error('Add a message or an attachment'); return; }
    setSending(true);
    try {
      await messageService.contactSupport({
        senderName: myName,
        title: title.trim() || '(no subject)',
        message: message.trim(),
        attachmentUrl: attach?.url, attachmentType: attach?.type, attachmentName: attach?.name,
        bingeId: binge?.id ?? null,
      });
      onSent();
    } catch (e) { toast.error(e?.response?.data?.message || 'Failed to send'); }
    finally { setSending(false); }
  };

  return (
    <div className="msg-modal-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }} role="dialog" aria-modal="true" aria-label={`Message ${target}`}>
      <div className="msg-modal" onMouseDown={(e) => e.stopPropagation()}>
        <div className="msg-modal-head">
          <h3>{binge ? `Message ${target}` : 'Contact Support'}</h3>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><FiX /></button>
        </div>
        <div className="msg-modal-body">
          <p className="msg-attach-hint" style={{ marginBottom: 8 }}>
            {binge
              ? `Your message goes to the ${target} team — they run this venue and can resolve venue matters fastest.`
              : 'Your message goes to platform Support.'}
          </p>
          <label className="msg-field-label">Subject</label>
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Subject (optional)" maxLength={200} />
          <label className="msg-field-label">Message</label>
          <textarea rows={5} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="How can we help?" maxLength={1000} />
          <div className="msg-compose-attach">
            <AttachButton onUploaded={setAttach} disabled={sending} />
            <span className="msg-attach-hint">Attach a photo or video (max 25 MB)</span>
          </div>
          <AttachmentPreview attachment={attach} onRemove={() => setAttach(null)} />
        </div>
        <div className="msg-modal-foot">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={sending} onClick={submit}><FiSend /> {sending ? 'Sending…' : 'Send'}</button>
        </div>
      </div>
    </div>
  );
}
