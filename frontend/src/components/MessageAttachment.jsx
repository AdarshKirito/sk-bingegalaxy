import { useRef, useState } from 'react';
import { FiPaperclip, FiX, FiDownload, FiFilm, FiImage } from 'react-icons/fi';
import { toast } from 'react-toastify';
import { uploadMessageAttachment } from '../services/endpoints';
import './MessageAttachment.css';

const MAX_BYTES = 25 * 1024 * 1024;

/**
 * Paperclip button that uploads a chosen image/video and hands the stored
 * `{ url, type, name }` back via `onUploaded`. Used in the composer and reply bars.
 */
export function AttachButton({ onUploaded, disabled }) {
  const inputRef = useRef(null);
  const [busy, setBusy] = useState(false);

  const pick = () => inputRef.current?.click();

  const onFile = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file
    if (!file) return;
    if (file.size > MAX_BYTES) { toast.error('Attachment must be 25 MB or smaller'); return; }
    setBusy(true);
    try {
      const res = await uploadMessageAttachment(file);
      const data = res?.data?.data;
      if (data?.url) onUploaded(data);
      else toast.error('Upload failed');
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Upload failed');
    } finally { setBusy(false); }
  };

  return (
    <>
      <input ref={inputRef} type="file" accept="image/*,video/*" onChange={onFile} hidden />
      <button type="button" className="msg-attach-btn" onClick={pick} disabled={disabled || busy}
        title="Attach photo or video" aria-label="Attach photo or video">
        <FiPaperclip />
      </button>
    </>
  );
}

/** Small chip shown in the composer after a file is attached (before sending). */
export function AttachmentPreview({ attachment, onRemove }) {
  if (!attachment) return null;
  return (
    <div className="msg-attach-preview">
      {attachment.type === 'video' ? <FiFilm /> : <FiImage />}
      <span className="msg-attach-preview-name">{attachment.name || 'Attachment'}</span>
      <button type="button" className="msg-attach-preview-x" onClick={onRemove} aria-label="Remove attachment"><FiX /></button>
    </div>
  );
}

/**
 * Renders an attachment inside a message bubble — inline image or a video player,
 * with a download link. `url` is a server media path; empty renders nothing.
 */
export function AttachmentView({ url, type, name }) {
  if (!url) return null;
  return (
    <div className="msg-attach-view">
      {type === 'video' ? (
        <video className="msg-attach-media" src={url} controls preload="metadata" />
      ) : (
        <a href={url} target="_blank" rel="noopener noreferrer" className="msg-attach-imglink">
          <img className="msg-attach-media" src={url} alt={name || 'attachment'} loading="lazy" />
        </a>
      )}
      <a className="msg-attach-download" href={url} download={name || true} target="_blank" rel="noopener noreferrer">
        <FiDownload /> {name || 'Download'}
      </a>
    </div>
  );
}
