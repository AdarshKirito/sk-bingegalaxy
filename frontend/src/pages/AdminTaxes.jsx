import { useEffect, useMemo, useState } from 'react';
import { taxService, toArray } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiPercent, FiPlus, FiEdit2, FiTrash2, FiX, FiPower, FiSearch,
  FiGlobe, FiHome, FiSliders, FiInfo
} from 'react-icons/fi';
import './AdminPages.css';

/* ────────────────────────────────────────────────────────────────────────── */
/*  Constants                                                                  */
/* ────────────────────────────────────────────────────────────────────────── */

// Backend enum: TaxRule.AppliesTo. Friendly labels keep the picker self-documenting.
const APPLIES_TO_OPTIONS = [
  { value: 'TOTAL',  label: 'Total (subtotal)',     hint: 'Applies on the full taxable subtotal' },
  { value: 'BASE',   label: 'Base price only',       hint: 'Booking base — excludes add-ons / guest fees' },
  { value: 'ADDONS', label: 'Add-ons only',          hint: 'Only the add-ons portion is taxed' },
  { value: 'GUEST',  label: 'Guest fees only',       hint: 'Only the per-guest surcharge is taxed' },
];
const TAX_TYPES = ['GENERIC', 'GST', 'VAT', 'SALES_TAX', 'SERVICE_TAX'];
const PRODUCT_TYPES = [
  { value: '', label: '— Any product —' },
  { value: 'BOOKING',   label: 'Booking' },
  { value: 'ADDON',     label: 'Add-on' },
  { value: 'GUEST_FEE', label: 'Guest fee' },
  { value: 'ALL',       label: 'All products' },
];
const CUSTOMER_TYPES = [
  { value: '',    label: '— Any customer —' },
  { value: 'B2C', label: 'B2C (consumer)' },
  { value: 'B2B', label: 'B2B (business)' },
  { value: 'ALL', label: 'All customers' },
];

const emptyForm = {
  name: '',
  description: '',
  ratePercent: '18.00',
  appliesTo: 'TOTAL',
  inclusive: false,
  taxType: 'GENERIC',
  countryCode: '',
  regionCode: '',
  stateCode: '',
  city: '',
  postalCode: '',
  productType: '',
  customerType: '',
  effectiveFrom: '',
  effectiveTo: '',
  priority: 100,
  active: true,
};

const emptyPreview = { subtotal: '5000', baseAmount: '', addOnAmount: '', guestAmount: '' };

/* ────────────────────────────────────────────────────────────────────────── */
/*  Helpers                                                                    */
/* ────────────────────────────────────────────────────────────────────────── */

const fmtRate = (bps) => `${(Number(bps || 0) / 100).toFixed(2)}%`;
const fmtJurisdiction = (r) => {
  const parts = [r.countryCode, r.stateCode || r.regionCode, r.city, r.postalCode].filter(Boolean);
  return parts.length ? parts.join(' / ') : 'Anywhere';
};
const fmtScope = (r) => (r.bingeId ? 'Binge' : 'Global');
const fmtAppliesTo = (v) => APPLIES_TO_OPTIONS.find(o => o.value === v)?.label || v;

/* ────────────────────────────────────────────────────────────────────────── */
/*  Page                                                                       */
/* ────────────────────────────────────────────────────────────────────────── */

export default function AdminTaxes() {
  const [tab, setTab] = useState('rules');           // 'rules' | 'calculator'
  const [rules, setRules] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [scopeFilter, setScopeFilter] = useState('all'); // 'all' | 'binge' | 'global'

  const [modal, setModal] = useState({ open: false, mode: 'create', item: null });
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);

  // Calculator state
  const [calc, setCalc] = useState(emptyPreview);
  const [calcResult, setCalcResult] = useState(null);
  const [calcing, setCalcing] = useState(false);

  /* ── Data fetch ─────────────────────────────────────────── */

  const fetchAll = () => {
    setLoading(true);
    taxService.list()
      .then(res => setRules(toArray(res.data?.data)))
      .catch(() => toast.error('Failed to load tax rules'))
      .finally(() => setLoading(false));
  };
  useEffect(() => { fetchAll(); }, []);

  /* ── Derived list (search + scope filter, then sorted by priority) ─── */

  const visibleRules = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rules
      .filter(r => {
        if (scopeFilter === 'binge'  && !r.bingeId) return false;
        if (scopeFilter === 'global' && r.bingeId)  return false;
        if (!q) return true;
        const hay = [r.name, r.description, r.taxType, r.countryCode, r.stateCode, r.regionCode]
          .filter(Boolean).join(' ').toLowerCase();
        return hay.includes(q);
      })
      .slice() // don't mutate
      .sort((a, b) => (a.priority ?? 100) - (b.priority ?? 100));
  }, [rules, search, scopeFilter]);

  /* ── Modal openers ──────────────────────────────────────── */

  const openCreate = () => {
    setForm(emptyForm);
    setModal({ open: true, mode: 'create', item: null });
  };

  const openEdit = (rule) => {
    setForm({
      name: rule.name || '',
      description: rule.description || '',
      ratePercent: ((Number(rule.rateBps || 0)) / 100).toFixed(2),
      appliesTo: rule.appliesTo || 'TOTAL',
      inclusive: !!rule.inclusive,
      taxType: rule.taxType || 'GENERIC',
      countryCode: rule.countryCode || '',
      regionCode: rule.regionCode || '',
      stateCode: rule.stateCode || '',
      city: rule.city || '',
      postalCode: rule.postalCode || '',
      productType: rule.productType || '',
      customerType: rule.customerType || '',
      effectiveFrom: rule.effectiveFrom ? rule.effectiveFrom.slice(0, 16) : '',
      effectiveTo:   rule.effectiveTo   ? rule.effectiveTo.slice(0, 16)   : '',
      priority: rule.priority ?? 100,
      active: rule.active !== false,
    });
    setModal({ open: true, mode: 'edit', item: rule });
  };

  const closeModal = () => setModal({ open: false, mode: 'create', item: null });

  /* ── Save / toggle / delete ─────────────────────────────── */

  const buildPayload = () => {
    const pct = Number(form.ratePercent);
    return {
      name: form.name.trim(),
      description: form.description.trim() || null,
      rateBps: Math.round(pct * 100),
      appliesTo: form.appliesTo,
      inclusive: !!form.inclusive,
      taxType: form.taxType || 'GENERIC',
      countryCode: form.countryCode.trim().toUpperCase() || null,
      regionCode:  form.regionCode.trim().toUpperCase()  || null,
      stateCode:   form.stateCode.trim().toUpperCase()   || null,
      city:        form.city.trim() || null,
      postalCode:  form.postalCode.trim() || null,
      productType:  form.productType  || null,
      customerType: form.customerType || null,
      effectiveFrom: form.effectiveFrom || null,
      effectiveTo:   form.effectiveTo   || null,
      priority: Number(form.priority) || 100,
      active: !!form.active,
    };
  };

  const handleSave = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Name is required'); return; }
    const pct = Number(form.ratePercent);
    if (Number.isNaN(pct) || pct < 0 || pct > 100) {
      toast.error('Rate % must be between 0 and 100'); return;
    }
    if (form.effectiveFrom && form.effectiveTo
        && new Date(form.effectiveFrom) > new Date(form.effectiveTo)) {
      toast.error('Effective From must be earlier than Effective To'); return;
    }
    setSaving(true);
    try {
      const payload = buildPayload();
      if (modal.mode === 'create') {
        await taxService.create(payload);
        toast.success('Tax rule created');
      } else {
        await taxService.update(modal.item.id, payload);
        toast.success('Tax rule updated');
      }
      closeModal();
      fetchAll();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Save failed');
    } finally { setSaving(false); }
  };

  const handleToggleActive = async (rule) => {
    if (!rule.bingeId) {
      toast.error('Global rules can only be toggled by a super admin');
      return;
    }
    try {
      // PUT with all current values flipped on `active`. The backend bumps
      // ruleVersion automatically so audit trail shows the toggle.
      const payload = {
        name: rule.name,
        description: rule.description,
        rateBps: rule.rateBps,
        appliesTo: rule.appliesTo,
        inclusive: rule.inclusive,
        taxType: rule.taxType,
        countryCode: rule.countryCode,
        regionCode: rule.regionCode,
        stateCode: rule.stateCode,
        city: rule.city,
        postalCode: rule.postalCode,
        productType: rule.productType,
        customerType: rule.customerType,
        effectiveFrom: rule.effectiveFrom,
        effectiveTo: rule.effectiveTo,
        priority: rule.priority,
        active: !rule.active,
      };
      await taxService.update(rule.id, payload);
      toast.success(rule.active ? 'Rule deactivated' : 'Rule activated');
      fetchAll();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Toggle failed');
    }
  };

  const handleDelete = async (rule) => {
    if (!rule.bingeId) {
      toast.error('Global rules can only be deleted by a super admin');
      return;
    }
    if (!window.confirm(`Delete tax rule "${rule.name}"? This cannot be undone.`)) return;
    try {
      await taxService.remove(rule.id);
      toast.success('Tax rule deleted');
      fetchAll();
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Delete failed');
    }
  };

  /* ── Calculator ─────────────────────────────────────────── */

  const runPreview = async (e) => {
    e?.preventDefault?.();
    const subtotal = Number(calc.subtotal);
    if (!(subtotal >= 0)) { toast.error('Subtotal must be ≥ 0'); return; }
    setCalcing(true);
    try {
      const params = { subtotal };
      if (calc.baseAmount  !== '') params.baseAmount  = Number(calc.baseAmount);
      if (calc.addOnAmount !== '') params.addOnAmount = Number(calc.addOnAmount);
      if (calc.guestAmount !== '') params.guestAmount = Number(calc.guestAmount);
      const res = await taxService.preview(params);
      setCalcResult(res.data?.data || null);
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Preview failed');
      setCalcResult(null);
    } finally { setCalcing(false); }
  };

  /* ── Stats ──────────────────────────────────────────────── */

  const stats = useMemo(() => {
    const active = rules.filter(r => r.active);
    const binge  = rules.filter(r => r.bingeId);
    const global = rules.filter(r => !r.bingeId);
    return {
      total: rules.length,
      active: active.length,
      binge: binge.length,
      global: global.length,
    };
  }, [rules]);

  /* ── Render ─────────────────────────────────────────────── */

  return (
    <div className="container adm-shell">
      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiPercent /> Taxes</span>
          <h1>Tax Rules</h1>
          <p>
            Configure tax rules that apply to bookings in this binge. Rules with
            lower <em>priority</em> values are evaluated first; matching active
            rules are summed unless marked <em>inclusive</em>.
          </p>
        </div>
      </div>

      {/* Tabs */}
      <div className="adm-toolbar">
        <div className="adm-tabs">
          <button className={`adm-tab${tab === 'rules' ? ' active' : ''}`}
            onClick={() => setTab('rules')}>
            <FiSliders style={{ marginRight: 6, verticalAlign: -2 }} />
            Rules
          </button>
          <button className={`adm-tab${tab === 'calculator' ? ' active' : ''}`}
            onClick={() => setTab('calculator')}>
            <FiPercent style={{ marginRight: 6, verticalAlign: -2 }} />
            Calculator
          </button>
        </div>
        {tab === 'rules' && (
          <div className="adm-toolbar-right">
            <button className="btn btn-primary btn-sm" onClick={openCreate}>
              <FiPlus /> Add Tax Rule
            </button>
          </div>
        )}
      </div>

      {tab === 'rules' && (
        <RulesTab
          loading={loading}
          rules={visibleRules}
          stats={stats}
          search={search} setSearch={setSearch}
          scopeFilter={scopeFilter} setScopeFilter={setScopeFilter}
          onCreate={openCreate}
          onEdit={openEdit}
          onToggle={handleToggleActive}
          onDelete={handleDelete}
        />
      )}

      {tab === 'calculator' && (
        <CalculatorTab
          calc={calc} setCalc={setCalc}
          result={calcResult}
          loading={calcing}
          onRun={runPreview}
        />
      )}

      {modal.open && (
        <RuleModal
          mode={modal.mode}
          form={form}
          setForm={setForm}
          saving={saving}
          isGlobal={modal.item ? !modal.item.bingeId : false}
          onClose={closeModal}
          onSave={handleSave}
        />
      )}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────── */
/*  Rules tab                                                                  */
/* ────────────────────────────────────────────────────────────────────────── */

function RulesTab({
  loading, rules, stats, search, setSearch, scopeFilter, setScopeFilter,
  onCreate, onEdit, onToggle, onDelete,
}) {
  return (
    <>
      {/* Stats strip */}
      <div className="adm-grid-4" style={{ marginTop: '1rem' }}>
        <div className="adm-stat">
          <div className="adm-stat-label">Total rules</div>
          <div className="adm-stat-value">{stats.total}</div>
        </div>
        <div className="adm-stat">
          <div className="adm-stat-label">Active</div>
          <div className="adm-stat-value" style={{ color: 'var(--success)' }}>{stats.active}</div>
        </div>
        <div className="adm-stat">
          <div className="adm-stat-label">Binge-scoped</div>
          <div className="adm-stat-value">{stats.binge}</div>
        </div>
        <div className="adm-stat">
          <div className="adm-stat-label">Global / platform</div>
          <div className="adm-stat-value">{stats.global}</div>
        </div>
      </div>

      {/* Filters */}
      <div className="adm-toolbar" style={{ marginTop: '1rem' }}>
        <div className="admin-toolbar-group" style={{ flex: 1, maxWidth: 360 }}>
          <span className="admin-toolbar-label">
            <FiSearch style={{ verticalAlign: -2, marginRight: 4 }} />Search
          </span>
          <input
            className="admin-select"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Name, region, tax type…"
            style={{ flex: 1 }}
          />
        </div>
        <div className="admin-toolbar-group">
          <span className="admin-toolbar-label">Scope</span>
          <select className="admin-select" value={scopeFilter}
            onChange={e => setScopeFilter(e.target.value)}>
            <option value="all">All scopes</option>
            <option value="binge">Binge only</option>
            <option value="global">Global only</option>
          </select>
        </div>
      </div>

      {loading ? (
        <div className="admin-loading"><p>Loading tax rules…</p></div>
      ) : rules.length === 0 ? (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiPercent /></span>
          <h3>No tax rules</h3>
          <p>Add one to begin charging tax on bookings in this binge.</p>
          <button className="btn btn-primary btn-sm" onClick={onCreate} style={{ marginTop: '0.75rem' }}>
            <FiPlus /> Add your first rule
          </button>
        </div>
      ) : (
        <div className="adm-table-wrap" style={{ marginTop: '1rem' }}>
          <table className="adm-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Rate</th>
                <th>Applies&nbsp;to</th>
                <th>Jurisdiction</th>
                <th>Scope</th>
                <th>Priority</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map(r => (
                <tr key={r.id} className={r.active ? '' : 'adm-row-inactive'}>
                  <td>
                    <strong>{r.name}</strong>
                    {r.description && (
                      <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
                        {r.description}
                      </div>
                    )}
                  </td>
                  <td>
                    <span className="adm-badge adm-badge-info">{r.taxType || 'GENERIC'}</span>
                  </td>
                  <td>
                    <strong>{fmtRate(r.rateBps)}</strong>
                    {r.inclusive && (
                      <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>inclusive</div>
                    )}
                  </td>
                  <td title={APPLIES_TO_OPTIONS.find(o => o.value === r.appliesTo)?.hint}>
                    {fmtAppliesTo(r.appliesTo)}
                  </td>
                  <td>{fmtJurisdiction(r)}</td>
                  <td>
                    {r.bingeId ? (
                      <span className="adm-badge adm-badge-info" title="Scoped to this binge">
                        <FiHome style={{ verticalAlign: -2, marginRight: 3 }} />Binge
                      </span>
                    ) : (
                      <span className="adm-badge" title="Platform-wide default rule"
                        style={{ background: 'rgba(var(--accent-rgb),0.15)', color: 'var(--accent)' }}>
                        <FiGlobe style={{ verticalAlign: -2, marginRight: 3 }} />Global
                      </span>
                    )}
                  </td>
                  <td style={{ textAlign: 'center' }}>{r.priority ?? 100}</td>
                  <td>
                    <span className={`adm-badge ${r.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                      {r.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    {r.bingeId ? (
                      <>
                        <button className="btn btn-sm btn-secondary" onClick={() => onToggle(r)}
                          title={r.active ? 'Deactivate' : 'Activate'} style={{ marginRight: 4 }}>
                          <FiPower />
                        </button>
                        <button className="btn btn-sm btn-secondary" onClick={() => onEdit(r)}
                          title="Edit" style={{ marginRight: 4 }}>
                          <FiEdit2 />
                        </button>
                        <button className="btn btn-sm btn-danger" onClick={() => onDelete(r)}
                          title="Delete">
                          <FiTrash2 />
                        </button>
                      </>
                    ) : (
                      <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                        super-admin only
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────── */
/*  Calculator tab — uses public preview endpoint                              */
/* ────────────────────────────────────────────────────────────────────────── */

function CalculatorTab({ calc, setCalc, result, loading, onRun }) {
  const set = (k) => (e) => setCalc(c => ({ ...c, [k]: e.target.value }));

  return (
    <div className="adm-grid-2" style={{ marginTop: '1rem', alignItems: 'flex-start' }}>
      <form onSubmit={onRun} className="adm-card" style={{ padding: '1.25rem' }}>
        <h3 style={{ marginTop: 0 }}>
          <FiInfo style={{ verticalAlign: -2, marginRight: 6 }} />
          Tax preview
        </h3>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: 0 }}>
          Runs the same calculator the customer checkout uses. Useful for
          sanity-checking your rules before going live.
        </p>
        <div className="adm-form-grid">
          <label className="adm-form-field">
            <span>Subtotal *</span>
            <input type="number" step="0.01" min="0" required
              value={calc.subtotal} onChange={set('subtotal')} />
          </label>
          <label className="adm-form-field">
            <span>Base amount</span>
            <input type="number" step="0.01" min="0" placeholder="(defaults to subtotal)"
              value={calc.baseAmount} onChange={set('baseAmount')} />
          </label>
          <label className="adm-form-field">
            <span>Add-on amount</span>
            <input type="number" step="0.01" min="0" placeholder="0"
              value={calc.addOnAmount} onChange={set('addOnAmount')} />
          </label>
          <label className="adm-form-field">
            <span>Guest amount</span>
            <input type="number" step="0.01" min="0" placeholder="0"
              value={calc.guestAmount} onChange={set('guestAmount')} />
          </label>
        </div>
        <div className="adm-form-actions" style={{ marginTop: '1rem' }}>
          <button type="submit" className="btn btn-primary btn-sm" disabled={loading}>
            {loading ? 'Calculating…' : 'Run preview'}
          </button>
        </div>
      </form>

      <div className="adm-card" style={{ padding: '1.25rem' }}>
        <h3 style={{ marginTop: 0 }}>Result</h3>
        {!result ? (
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            Run a preview to see the breakdown.
          </p>
        ) : (
          <>
            <div className="adm-grid-3" style={{ marginBottom: '1rem' }}>
              <div className="adm-stat">
                <div className="adm-stat-label">Subtotal</div>
                <div className="adm-stat-value">{Number(result.subtotal || 0).toFixed(2)}</div>
              </div>
              <div className="adm-stat">
                <div className="adm-stat-label">Total tax</div>
                <div className="adm-stat-value" style={{ color: 'var(--success)' }}>
                  +{Number(result.totalTax || 0).toFixed(2)}
                </div>
              </div>
              <div className="adm-stat">
                <div className="adm-stat-label">Inclusive (info)</div>
                <div className="adm-stat-value">
                  {Number(result.totalInclusiveTax || 0).toFixed(2)}
                </div>
              </div>
            </div>
            <div className="adm-table-wrap">
              <table className="adm-table">
                <thead>
                  <tr><th>Rule</th><th>Rate</th><th>Taxable</th><th>Amount</th><th>Mode</th></tr>
                </thead>
                <tbody>
                  {(result.lines || []).map((l, i) => (
                    <tr key={l.ruleId || i}>
                      <td>
                        <strong>{l.name}</strong>
                        {l.taxType && (
                          <span className="adm-badge adm-badge-info" style={{ marginLeft: 6 }}>
                            {l.taxType}
                          </span>
                        )}
                      </td>
                      <td>{fmtRate(l.rateBps)}</td>
                      <td>{Number(l.taxableAmount || 0).toFixed(2)}</td>
                      <td><strong>{Number(l.amount || 0).toFixed(2)}</strong></td>
                      <td>
                        <span className="adm-badge">
                          {l.inclusive ? 'inclusive' : 'exclusive'}
                        </span>
                      </td>
                    </tr>
                  ))}
                  {(!result.lines || result.lines.length === 0) && (
                    <tr>
                      <td colSpan={5} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                        No matching rules — total tax is 0.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {result.provider && (
              <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.5rem' }}>
                Provider: <code>{result.provider}</code>
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────── */
/*  Modal — create/edit rule                                                   */
/* ────────────────────────────────────────────────────────────────────────── */

function RuleModal({ mode, form, setForm, saving, isGlobal, onClose, onSave }) {
  const upd = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));
  const updBool = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.checked }));

  return (
    <div className="adm-modal-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="adm-modal" style={{ maxWidth: 720 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>
            {mode === 'create' ? 'New tax rule' : 'Edit tax rule'}
            {isGlobal && (
              <span className="adm-badge" style={{ marginLeft: 8, fontSize: '0.65rem' }}>
                <FiGlobe style={{ verticalAlign: -2, marginRight: 3 }} />Global
              </span>
            )}
          </h3>
          <button type="button" className="btn btn-sm btn-secondary" onClick={onClose} aria-label="Close">
            <FiX />
          </button>
        </div>

        <form onSubmit={onSave} style={{ marginTop: '1rem' }}>
          {/* Identity */}
          <div className="adm-form-grid">
            <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
              <span>Name *</span>
              <input required value={form.name} onChange={upd('name')}
                placeholder="e.g. CGST 9%" maxLength={120} />
            </label>
            <label className="adm-form-field" style={{ gridColumn: '1 / -1' }}>
              <span>Description</span>
              <input value={form.description} onChange={upd('description')}
                placeholder="Optional internal note" maxLength={500} />
            </label>
          </div>

          {/* Rate + structure */}
          <h4 style={{ margin: '1.25rem 0 0.5rem', fontSize: '0.85rem',
                       textTransform: 'uppercase', letterSpacing: '0.05em',
                       color: 'var(--text-secondary)' }}>
            Rate & structure
          </h4>
          <div className="adm-form-grid">
            <label className="adm-form-field">
              <span>Rate % *</span>
              <input type="number" step="0.01" min="0" max="100" required
                value={form.ratePercent} onChange={upd('ratePercent')} />
            </label>
            <label className="adm-form-field">
              <span>Tax type</span>
              <select value={form.taxType} onChange={upd('taxType')}>
                {TAX_TYPES.map(v => <option key={v} value={v}>{v}</option>)}
              </select>
            </label>
            <label className="adm-form-field" style={{ gridColumn: 'span 2' }}>
              <span>Applies to</span>
              <select value={form.appliesTo} onChange={upd('appliesTo')}>
                {APPLIES_TO_OPTIONS.map(o => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              <small style={{ color: 'var(--text-muted)', fontSize: '0.72rem', marginTop: 4 }}>
                {APPLIES_TO_OPTIONS.find(o => o.value === form.appliesTo)?.hint}
              </small>
            </label>
            <label className="adm-form-field" style={{ gridColumn: '1 / -1', flexDirection: 'row',
                       alignItems: 'center', gap: '0.5rem' }}>
              <input type="checkbox" checked={form.inclusive} onChange={updBool('inclusive')} />
              <span style={{ textTransform: 'none', letterSpacing: 0, fontSize: '0.85rem' }}>
                Inclusive — price already contains this tax (display-only breakdown)
              </span>
            </label>
          </div>

          {/* Jurisdiction */}
          <h4 style={{ margin: '1.25rem 0 0.5rem', fontSize: '0.85rem',
                       textTransform: 'uppercase', letterSpacing: '0.05em',
                       color: 'var(--text-secondary)' }}>
            Jurisdiction <span style={{ textTransform: 'none', fontWeight: 400, color: 'var(--text-muted)' }}>(blank = matches anywhere)</span>
          </h4>
          <div className="adm-form-grid">
            <label className="adm-form-field">
              <span>Country (ISO-2)</span>
              <input maxLength={2} placeholder="IN"
                value={form.countryCode}
                onChange={e => setForm(f => ({ ...f, countryCode: e.target.value.toUpperCase() }))} />
            </label>
            <label className="adm-form-field">
              <span>Region</span>
              <input maxLength={16} value={form.regionCode}
                onChange={e => setForm(f => ({ ...f, regionCode: e.target.value.toUpperCase() }))} />
            </label>
            <label className="adm-form-field">
              <span>State code</span>
              <input maxLength={16} placeholder="KA"
                value={form.stateCode}
                onChange={e => setForm(f => ({ ...f, stateCode: e.target.value.toUpperCase() }))} />
            </label>
            <label className="adm-form-field">
              <span>City</span>
              <input value={form.city} onChange={upd('city')} />
            </label>
            <label className="adm-form-field">
              <span>Postal code</span>
              <input value={form.postalCode} onChange={upd('postalCode')} />
            </label>
          </div>

          {/* Targeting */}
          <h4 style={{ margin: '1.25rem 0 0.5rem', fontSize: '0.85rem',
                       textTransform: 'uppercase', letterSpacing: '0.05em',
                       color: 'var(--text-secondary)' }}>
            Targeting
          </h4>
          <div className="adm-form-grid">
            <label className="adm-form-field">
              <span>Product type</span>
              <select value={form.productType} onChange={upd('productType')}>
                {PRODUCT_TYPES.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </label>
            <label className="adm-form-field">
              <span>Customer type</span>
              <select value={form.customerType} onChange={upd('customerType')}>
                {CUSTOMER_TYPES.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </label>
          </div>

          {/* Validity */}
          <h4 style={{ margin: '1.25rem 0 0.5rem', fontSize: '0.85rem',
                       textTransform: 'uppercase', letterSpacing: '0.05em',
                       color: 'var(--text-secondary)' }}>
            Validity & priority
          </h4>
          <div className="adm-form-grid">
            <label className="adm-form-field">
              <span>Effective from</span>
              <input type="datetime-local" value={form.effectiveFrom} onChange={upd('effectiveFrom')} />
            </label>
            <label className="adm-form-field">
              <span>Effective to</span>
              <input type="datetime-local" value={form.effectiveTo} onChange={upd('effectiveTo')} />
            </label>
            <label className="adm-form-field">
              <span>Priority</span>
              <input type="number" value={form.priority} onChange={upd('priority')} />
              <small style={{ color: 'var(--text-muted)', fontSize: '0.72rem', marginTop: 4 }}>
                Lower runs first (default 100)
              </small>
            </label>
            <label className="adm-form-field" style={{ flexDirection: 'row', alignItems: 'center',
                       gap: '0.5rem', alignSelf: 'flex-end', paddingBottom: '0.5rem' }}>
              <input type="checkbox" checked={form.active} onChange={updBool('active')} />
              <span style={{ textTransform: 'none', letterSpacing: 0, fontSize: '0.85rem' }}>
                Active
              </span>
            </label>
          </div>

          <div className="adm-modal-actions" style={{ justifyContent: 'flex-end' }}>
            <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
              {saving ? 'Saving…' : (mode === 'create' ? 'Create rule' : 'Save changes')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
