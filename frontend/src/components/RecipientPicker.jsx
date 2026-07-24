import { useState, useEffect, useRef } from 'react';
import { FiX, FiUsers, FiUser, FiShield } from 'react-icons/fi';
import { authService } from '../services/endpoints';
import './RecipientPicker.css';

/**
 * Gmail/Slack-style recipient token field for the messaging composer.
 *
 * Scales to any number of customers because it NEVER lists everyone: the user types,
 * the server returns the top matches, and picking one turns it into a removable chip.
 * The dropdown surfaces broadcast "groups" (All Super Admins / Admins / Customers) first,
 * then matching Staff and Customers — each capped, with a "keep typing to narrow" hint.
 *
 * `value` is the selected-recipients array `[{ key, recipientUserId, recipientRole,
 * recipientName }]` (recipientUserId === null means a whole-group broadcast).
 * `onChange(next)` replaces it. Fully keyboard-driven: ↑/↓ to move, Enter to add,
 * Backspace (empty input) to remove the last chip, Esc to close.
 */

const roleLabel = (r) => ({ SUPER_ADMIN: 'Super Admin', ADMIN: 'Admin', CUSTOMER: 'Customer' }[r] || r || '');
const keyOf = (role, id) => `${role}#${id == null ? 'ALL' : id}`;
const fullName = (u) => `${u.firstName || ''} ${u.lastName || ''}`.trim() || u.email || 'User';
const MAX_PER_GROUP = 6;

export default function RecipientPicker({ value, onChange, isSuperAdmin }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [staff, setStaff] = useState([]);
  const [cust, setCust] = useState([]);
  const [staffMore, setStaffMore] = useState(false);
  const [custMore, setCustMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeIdx, setActiveIdx] = useState(0);
  const wrapRef = useRef(null);
  const inputRef = useRef(null);

  const selectedKeys = new Set(value.map((v) => v.key));

  // Broadcast groups (segments), filtered by the query and by what's already picked.
  const q = query.trim();
  const qLower = q.toLowerCase();
  const groups = [
    { key: keyOf('SUPER_ADMIN', null), recipientRole: 'SUPER_ADMIN', recipientUserId: null, recipientName: 'All Super Admins', kind: 'group' },
    ...(isSuperAdmin ? [{ key: keyOf('ADMIN', null), recipientRole: 'ADMIN', recipientUserId: null, recipientName: 'All Admins', kind: 'group' }] : []),
    { key: keyOf('CUSTOMER', null), recipientRole: 'CUSTOMER', recipientUserId: null, recipientName: 'All Customers', kind: 'group' },
  ].filter((g) => !selectedKeys.has(g.key) && (q === '' || g.recipientName.toLowerCase().includes(qLower)));

  // Debounced directory search — only while the dropdown is open.
  useEffect(() => {
    if (!open) return;
    let live = true;
    setLoading(true);
    const term = query.trim();
    const t = setTimeout(async () => {
      let s = [];
      let c = [];
      try { const r = await authService.searchStaff(term); s = r?.data?.data?.content || []; } catch { s = []; }
      if (term.length >= 2) {
        try { const r = await authService.searchCustomers(term); c = r?.data?.data?.content || []; } catch { c = []; }
      }
      if (!live) return;
      setStaffMore(s.length > MAX_PER_GROUP);
      setCustMore(c.length > MAX_PER_GROUP);
      setStaff(s.slice(0, MAX_PER_GROUP).map((u) => ({
        key: keyOf(u.role, u.id), recipientUserId: u.id, recipientRole: u.role,
        recipientName: fullName(u), sub: u.email,
      })));
      setCust(c.slice(0, MAX_PER_GROUP).map((u) => ({
        key: keyOf('CUSTOMER', u.id), recipientUserId: u.id, recipientRole: 'CUSTOMER',
        recipientName: fullName(u), sub: [u.email, u.phone].filter(Boolean).join(' · '),
      })));
      setLoading(false);
    }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [query, open]);

  // Flat suggestion list (groups → staff → customers), minus already-selected.
  const suggestions = [
    ...groups.map((g) => ({ ...g, section: 'Groups' })),
    ...staff.filter((s) => !selectedKeys.has(s.key)).map((s) => ({ ...s, section: 'Staff' })),
    ...cust.filter((c) => !selectedKeys.has(c.key)).map((c) => ({ ...c, section: 'Customers' })),
  ];

  useEffect(() => { setActiveIdx(0); }, [query, open, staff.length, cust.length]);

  // Close on outside click. Uses 'click' (not 'mousedown') so the in-flow dropdown doesn't
  // collapse mid-press and shift the layout out from under the pointer — the click resolves
  // on its original target first, then we close.
  useEffect(() => {
    const onDoc = (e) => { if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('click', onDoc);
    return () => document.removeEventListener('click', onDoc);
  }, []);

  const add = (item) => {
    if (!item) return;
    onChange([...value, {
      key: item.key, recipientUserId: item.recipientUserId,
      recipientRole: item.recipientRole, recipientName: item.recipientName,
    }]);
    setQuery('');
    setActiveIdx(0);
    setOpen(true);
    inputRef.current?.focus();
  };
  const removeKey = (key) => onChange(value.filter((v) => v.key !== key));

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActiveIdx((i) => Math.min(i + 1, suggestions.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActiveIdx((i) => Math.max(i - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); add(suggestions[activeIdx]); }
    else if (e.key === 'Backspace' && query === '' && value.length) { removeKey(value[value.length - 1].key); }
    else if (e.key === 'Escape') { setOpen(false); }
  };

  const iconFor = (item) => item.kind === 'group'
    ? <FiUsers aria-hidden="true" />
    : item.recipientRole === 'CUSTOMER' ? <FiUser aria-hidden="true" /> : <FiShield aria-hidden="true" />;

  return (
    <div className="rp-wrap" ref={wrapRef}>
      <div className="rp-field" onClick={() => { setOpen(true); inputRef.current?.focus(); }}>
        {value.map((v) => (
          <span key={v.key} className={`rp-chip ${v.recipientUserId == null ? 'rp-chip-group' : ''}`}>
            {v.recipientUserId == null && <FiUsers className="rp-chip-ico" aria-hidden="true" />}
            <span className="rp-chip-name">{v.recipientName}</span>
            <span className="rp-chip-role">{roleLabel(v.recipientRole)}</span>
            <button type="button" className="rp-chip-x" aria-label={`Remove ${v.recipientName}`}
              onClick={(e) => { e.stopPropagation(); removeKey(v.key); }}><FiX /></button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="rp-input"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder={value.length ? 'Add another…' : 'Type a name, email, or group (e.g. All Customers)…'}
          autoComplete="off"
          aria-label="Add recipients"
        />
      </div>

      {open && (
        <div className="rp-dropdown" role="listbox">
          {loading && suggestions.length === 0 && <div className="rp-hint">Searching…</div>}
          {!loading && suggestions.length === 0 && (
            <div className="rp-hint">
              No matches{q.length < 2 ? ' — type 2+ characters to search customers.' : '.'}
            </div>
          )}
          {suggestions.map((s, idx) => {
            const header = idx === 0 || suggestions[idx - 1].section !== s.section;
            return (
              <div key={s.key}>
                {header && <div className="rp-section">{s.section}</div>}
                <button
                  type="button"
                  role="option"
                  aria-selected={idx === activeIdx}
                  className={`rp-option ${idx === activeIdx ? 'active' : ''}`}
                  onMouseEnter={() => setActiveIdx(idx)}
                  onClick={() => add(s)}
                >
                  <span className={`rp-avatar ${s.kind === 'group' ? 'rp-avatar-group' : ''}`}>{iconFor(s)}</span>
                  <span className="rp-opt-main">
                    <span className="rp-opt-name">{s.recipientName}</span>
                    {s.sub && <span className="rp-opt-sub">{s.sub}</span>}
                  </span>
                  {s.kind !== 'group' && <span className="rp-opt-badge">{roleLabel(s.recipientRole)}</span>}
                </button>
              </div>
            );
          })}
          {(staffMore || custMore) && (
            <div className="rp-hint rp-more">Showing top matches — keep typing to narrow.</div>
          )}
        </div>
      )}
    </div>
  );
}
