import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminService, bookingService, authorityService } from '../services/endpoints';
import {
  createDashboardSlide,
  DASHBOARD_LAYOUT_OPTIONS,
  DASHBOARD_THEME_OPTIONS,
  normalizeDashboardExperience,
  sanitizeDashboardExperienceForSave,
} from '../services/dashboardExperience';
import {
  createAboutHighlight,
  createAboutPolicy,
  normalizeAboutExperience,
  sanitizeAboutExperienceForSave,
} from '../services/aboutExperience';
import { useBinge } from '../context/BingeContext';
import { useAuth } from '../context/AuthContext';
import TimezonePicker from '../components/TimezonePicker';
import { toast } from 'react-toastify';
import { FiActivity, FiArrowRight, FiCalendar, FiClock, FiCompass, FiEdit2, FiMapPin, FiPhone, FiPlus, FiRefreshCw, FiShield, FiStar, FiToggleLeft, FiToggleRight, FiTrash2, FiUpload, FiX } from 'react-icons/fi';
import './AdminPages.css';
import './BingeForm.css';
import BingeLoyaltySection from '../components/admin/BingeLoyaltySection';
import AddressFields, { EMPTY_ADDRESS, validateAddress } from '../components/form/AddressFields';
import { currencyForCountry } from '../utils/currency';
import { guessTimezone } from '../utils/timezoneGuess';
import PhoneField, { joinPhone, splitPhone, validatePhone } from '../components/form/PhoneField';
import VenueCoordinatesField, { validateCoordinates } from '../components/form/VenueCoordinatesField';
import OperatingHoursField, { defaultOpeningHours, normalizeOpeningHours, validateOpeningHours } from '../components/form/OperatingHoursField';

function StarRating({ avg, count }) {
  const rounded = Math.round((avg || 0) * 2) / 2;
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    if (i <= rounded) stars.push(<FiStar key={i} style={{ fill: '#f59e0b', color: '#f59e0b', verticalAlign: '-2px' }} />);
    else if (i - 0.5 === rounded) stars.push(<FiStar key={i} style={{ fill: '#f59e0b', color: '#f59e0b', opacity: 0.5, verticalAlign: '-2px' }} />);
    else stars.push(<FiStar key={i} style={{ color: '#d1d5db', verticalAlign: '-2px' }} />);
  }
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.85rem', margin: '4px 0' }}>
      {stars} <strong style={{ marginLeft: 2 }}>{(avg || 0).toFixed(1)}</strong>
      <span style={{ color: '#888' }}>({count || 0})</span>
    </span>
  );
}

export default function BingeManagement() {
  const emptyForm = {
    name: '',
    address: { ...EMPTY_ADDRESS },
    latitude: '',
    longitude: '',
    supportEmail: '',
    supportPhone: '',
    supportWhatsapp: '',
    supportPhoneIsWhatsapp: false,
    ownerEmail: '',
    ownerPhone: '',
    ownerPhoneIsWhatsapp: false,
    customerCancellationEnabled: true,
    customerCancellationCutoffMinutes: 180,
    maxConcurrentBookings: '',
    roomSelectionRequired: false,
    // Left blank on purpose: a brand-new venue has no country yet, so we must not
    // pre-stamp an India timezone. It is auto-derived once the address country/city
    // is entered (see the guessTimezone effect) and is validated as required on save.
    timezone: '',
    openTime: '10:00',
    closeTime: '23:00',
    openingHours: defaultOpeningHours('10:00', '23:00'),
  };
  const [binges, setBinges] = useState([]);
  const [reviewSummaries, setReviewSummaries] = useState({});
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [resubmittingId, setResubmittingId] = useState(null);
  const [dashboardEditor, setDashboardEditor] = useState({ open: false, binge: null });
  const [dashboardForm, setDashboardForm] = useState(() => normalizeDashboardExperience(null));
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardSaving, setDashboardSaving] = useState(false);
  const [dashboardEventTypes, setDashboardEventTypes] = useState([]);
  const [aboutEditor, setAboutEditor] = useState({ open: false, binge: null });
  const [aboutForm, setAboutForm] = useState(() => normalizeAboutExperience(null));
  const [aboutLoading, setAboutLoading] = useState(false);
  const [aboutSaving, setAboutSaving] = useState(false);
  const [tierEditor, setTierEditor] = useState({ open: false, binge: null });
  const [tierRows, setTierRows] = useState([]);
  const [tierLoading, setTierLoading] = useState(false);
  const [tierSaving, setTierSaving] = useState(false);
  const DEFAULT_POLICY = {
    freezePolicyEnabled: true,
    freezeDurationMinutes: 60,
    maxPendingCancelsBeforeFreeze: 3,
    maxPendingPaymentTimeoutsBeforeFreeze: 3,
    maxUnpaidBookingsPerCustomer: 2,
    refundOnSuccessfulPaymentCancel: true,
    refundOnPendingPaymentCancel: false,
  };
  const [policyForm, setPolicyForm] = useState(DEFAULT_POLICY);
  const [policySaving, setPolicySaving] = useState(false);  const [loyaltyEditor, setLoyaltyEditor] = useState({ open: false, binge: null });
  // ── Binge change requests (country-change approve/reject workflow) ──
  const [changeRequests, setChangeRequests] = useState([]);
  const [crModal, setCrModal] = useState(null);       // { bingeId, bingeName, country, reason }
  const [tzModal, setTzModal] = useState(null);       // { bingeId, bingeName, currentTz, timezone, reason }
  const [crDecision, setCrDecision] = useState(null); // { request, action, note, timezone }
  const [crBusy, setCrBusy] = useState(false);
  const { clearBinge, selectBinge, selectedBinge } = useBinge();
  const { isSuperAdmin } = useAuth();
  const navigate = useNavigate();

  // ── Stripe Connect status for the venue being edited ──
  // null = not yet checked; { connected, chargesEnabled } once known.
  const [connectStatus, setConnectStatus] = useState(null);
  const [connectBusy, setConnectBusy] = useState(false);

  useEffect(() => {
    if (!editId) { setConnectStatus(null); return; }
    let cancelled = false;
    (async () => {
      try {
        const res = await adminService.getStripeConnectStatus();
        const acct = res.data?.data;
        if (!cancelled) {
          setConnectStatus({ connected: !!acct, chargesEnabled: acct?.chargesEnabled === true });
        }
      } catch (err) {
        // 404 = onboarding never started; anything else (Stripe not configured,
        // network) is also "not connected" as far as this panel is concerned.
        if (!cancelled) setConnectStatus({ connected: false, chargesEnabled: false });
      }
    })();
    return () => { cancelled = true; };
  }, [editId]);

  const startStripeOnboarding = async () => {
    setConnectBusy(true);
    try {
      const res = await adminService.startStripeOnboarding();
      const url = res.data?.data?.url;
      if (url) {
        // Stripe's onboarding links are single-use and short-lived, so navigate
        // immediately rather than storing the URL.
        window.location.href = url;
      } else {
        toast.error('Stripe did not return an onboarding link. Please try again.');
      }
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message
        || 'Could not start payment onboarding.');
    } finally {
      setConnectBusy(false);
    }
  };

  // Whether the current admin may change the timezone of the binge being edited.
  // Creating a new venue OR editing one still pending approval: the owner sets the
  // zone freely (the super-admin re-confirms it at approval). Editing an APPROVED
  // one: native super-admin always, otherwise only if a TIMEZONE_CHANGE grant exists
  // for this binge or for ALL venues (mirrors the backend AuthorityLockGuard).
  // Who may set a venue's timezone by hand. Policy: a regular admin NEVER can —
  // the zone is auto-derived from the address, and if it looks wrong the admin
  // raises a review for a super-admin to resolve. Super-admins can always edit;
  // a delegated admin holding a TIMEZONE_CHANGE grant is treated as one.
  const [tzPermitted, setTzPermitted] = useState(false);
  // Approval status of the binge being edited ('APPROVED' | 'PENDING_APPROVAL' | 'REJECTED').
  const [editStatus, setEditStatus] = useState(null);
  // True once a permitted user manually picks a zone in this session — stops the
  // address-driven auto-derivation from overriding an explicit human choice.
  const [tzTouched, setTzTouched] = useState(false);
  useEffect(() => {
    if (!showForm) { setTzPermitted(false); return; }
    if (isSuperAdmin) { setTzPermitted(true); return; }
    // Regular admin: locked, unless an Authority-Handover TIMEZONE_CHANGE grant
    // exists (for this binge or ALL venues) — that delegation acts as super-admin.
    let cancelled = false;
    setTzPermitted(false); // fail-closed until a grant is confirmed
    (async () => {
      try {
        const [bingeGrant, allGrant] = await Promise.all([
          editId ? authorityService.lookupLock('TIMEZONE_CHANGE', String(editId)) : Promise.resolve(null),
          authorityService.lookupLock('TIMEZONE_CHANGE', 'ALL'),
        ]);
        const granted = !!(bingeGrant?.data?.data || allGrant?.data?.data);
        if (!cancelled) setTzPermitted(granted);
      } catch {
        if (!cancelled) setTzPermitted(false);
      }
    })();
    return () => { cancelled = true; };
  }, [showForm, editId, isSuperAdmin]);

  // Auto-derive the venue timezone from the address (country → state → city), so a
  // New York venue defaults to America/New_York. This runs for EVERYONE — it is
  // how a locked-out admin's zone gets set at all — and only steps aside for a
  // permitted user's explicit manual pick (tzTouched). Reacts to ADDRESS CHANGES
  // only (the ref resync in openCreateForm/handleEdit prevents the initial
  // population from clobbering a stored zone).
  const addrTzKey = `${form.address?.country || ''}|${form.address?.state || ''}|${form.address?.city || ''}`;
  const prevAddrTzKey = useRef(addrTzKey);
  useEffect(() => {
    if (prevAddrTzKey.current === addrTzKey) return;
    prevAddrTzKey.current = addrTzKey;
    if (!showForm || tzTouched) return;
    const guess = guessTimezone(form.address || {});
    if (guess) setForm((f) => (f.timezone === guess ? f : { ...f, timezone: guess }));
  }, [addrTzKey, showForm, tzTouched]); // eslint-disable-line react-hooks/exhaustive-deps

  const fetchBinges = async () => {
    try {
      const res = await adminService.getAdminBinges();
      const list = res.data.data || res.data || [];
      setBinges(list);
      // Fetch review summaries
      const summaries = {};
      await Promise.allSettled(
        list.map(async (b) => {
          try {
            const r = await bookingService.getBingeReviewSummary(b.id);
            summaries[b.id] = r.data.data || r.data || {};
          } catch { summaries[b.id] = {}; }
        })
      );
      setReviewSummaries(summaries);
    } catch (err) {
      toast.error('Failed to load binges');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchBinges(); }, []);

  const resetForm = () => {
    setForm(emptyForm);
    setShowForm(false);
    setEditId(null);
    setEditStatus(null);
    setTzTouched(false);
    prevAddrTzKey.current = '||';
  };

  const resetDashboardEditor = () => {
    setDashboardEditor({ open: false, binge: null });
    setDashboardForm(normalizeDashboardExperience(null));
    setDashboardLoading(false);
    setDashboardSaving(false);
  };

  const resetAboutEditor = () => {
    setAboutEditor({ open: false, binge: null });
    setAboutForm(normalizeAboutExperience(null));
    setAboutLoading(false);
    setAboutSaving(false);
  };

  const handleOpenTierEditor = async (binge) => {
    setTierEditor({ open: true, binge });
    setTierLoading(true);
    try {
      const [tierRes, policyRes] = await Promise.allSettled([
        adminService.getCancellationTiers(binge.id),
        adminService.getCancellationPolicy(binge.id),
      ]);
      if (tierRes.status === 'fulfilled') {
        const tiers = tierRes.value.data.data || tierRes.value.data || [];
        setTierRows(tiers.length
          ? tiers.map(t => ({ hoursBeforeStart: t.hoursBeforeStart, refundPercentage: t.refundPercentage, label: t.label || '' }))
          : [{ hoursBeforeStart: 48, refundPercentage: 100, label: 'Full refund' }, { hoursBeforeStart: 24, refundPercentage: 50, label: 'Half refund' }, { hoursBeforeStart: 0, refundPercentage: 0, label: 'No refund' }]);
      } else {
        setTierRows([{ hoursBeforeStart: 48, refundPercentage: 100, label: 'Full refund' }, { hoursBeforeStart: 24, refundPercentage: 50, label: 'Half refund' }, { hoursBeforeStart: 0, refundPercentage: 0, label: 'No refund' }]);
      }
      if (policyRes.status === 'fulfilled') {
        const p = policyRes.value.data.data || policyRes.value.data || {};
        setPolicyForm({ ...DEFAULT_POLICY, ...p });
      } else {
        setPolicyForm(DEFAULT_POLICY);
      }
    } finally {
      setTierLoading(false);
    }
  };

  const handleSaveTiers = async () => {
    setTierSaving(true);
    try {
      await adminService.saveCancellationTiers(tierEditor.binge.id, { tiers: tierRows });
      toast.success('Cancellation tiers saved');
      setTierEditor({ open: false, binge: null });
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save tiers');
    } finally {
      setTierSaving(false);
    }
  };

  const handleSavePolicy = async () => {
    setPolicySaving(true);
    try {
      const payload = {
        freezePolicyEnabled: !!policyForm.freezePolicyEnabled,
        freezeDurationMinutes: Number(policyForm.freezeDurationMinutes) || 60,
        maxPendingCancelsBeforeFreeze: Number(policyForm.maxPendingCancelsBeforeFreeze) || 3,
        maxPendingPaymentTimeoutsBeforeFreeze: Number(policyForm.maxPendingPaymentTimeoutsBeforeFreeze) || 3,
        maxUnpaidBookingsPerCustomer: Number(policyForm.maxUnpaidBookingsPerCustomer) || 2,
        refundOnSuccessfulPaymentCancel: !!policyForm.refundOnSuccessfulPaymentCancel,
        refundOnPendingPaymentCancel: !!policyForm.refundOnPendingPaymentCancel,
      };
      await adminService.saveCancellationPolicy(tierEditor.binge.id, payload);
      toast.success('Cancellation policy saved');
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save policy');
    } finally {
      setPolicySaving(false);
    }
  };

  // ── Change-request workflow handlers ──
  const loadChangeRequests = async () => {
    try {
      const res = await adminService.listBingeChangeRequests();
      setChangeRequests(Array.isArray(res.data?.data) ? res.data.data : []);
    } catch {
      // non-fatal — panel simply stays empty
    }
  };
  useEffect(() => { loadChangeRequests(); }, []);

  const submitCountryRequest = async () => {
    const c = (crModal?.country || '').trim().toUpperCase();
    if (!/^[A-Z]{2}$/.test(c)) { toast.error('Enter a valid 2-letter ISO country code (e.g. US, CN, AE)'); return; }
    setCrBusy(true);
    try {
      await adminService.requestBingeCountryChange(crModal.bingeId, c, crModal.reason || '');
      toast.success('Country-change request sent to super-admins for review.');
      setCrModal(null);
      loadChangeRequests();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Could not send the request.');
    } finally {
      setCrBusy(false);
    }
  };

  // Admin reports the auto-detected timezone as wrong. Reason is mandatory; the
  // suggested zone is optional (the super-admin can pick the final one).
  const submitTimezoneRequest = async () => {
    const reason = (tzModal?.reason || '').trim();
    if (reason.length < 5) { toast.error('Briefly explain why the timezone looks wrong.'); return; }
    setCrBusy(true);
    try {
      await adminService.requestBingeTimezoneChange(
        tzModal.bingeId, (tzModal.timezone || '').trim(), reason);
      toast.success('Timezone review sent to super-admins.');
      setTzModal(null);
      loadChangeRequests();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Could not send the review.');
    } finally {
      setCrBusy(false);
    }
  };

  const decideChangeRequest = async () => {
    if (!crDecision) return;
    const isTz = crDecision.request?.requestType === 'TIMEZONE_CHANGE';
    setCrBusy(true);
    try {
      if (crDecision.action === 'approve') {
        // For a timezone review the super-admin must supply the zone to apply
        // (their pick, or the admin's suggestion pre-filled into crDecision.timezone).
        if (isTz) {
          const zone = (crDecision.timezone || crDecision.request.requestedValue || '').trim();
          if (!zone) { toast.error('Choose the correct timezone to apply.'); setCrBusy(false); return; }
          await adminService.approveBingeChangeRequest(crDecision.request.id, crDecision.note || '', zone);
          toast.success('Resolved — the venue timezone has been updated.');
        } else {
          await adminService.approveBingeChangeRequest(crDecision.request.id, crDecision.note || '');
          toast.success('Approved — the venue country and currency have been updated.');
        }
      } else {
        await adminService.rejectBingeChangeRequest(crDecision.request.id, crDecision.note || '');
        toast.success('Request rejected. The requesting admin has been notified.');
      }
      setCrDecision(null);
      await Promise.all([loadChangeRequests(), fetchBinges()]);
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Decision failed.');
    } finally {
      setCrBusy(false);
    }
  };

  const withdrawChangeRequest = async (requestId) => {
    if (!window.confirm('Withdraw this pending country-change request?')) return;
    try {
      await adminService.cancelBingeChangeRequest(requestId);
      toast.success('Request withdrawn.');
      loadChangeRequests();
    } catch (err) {
      toast.error(err.userMessage || err.response?.data?.message || 'Could not withdraw the request.');
    }
  };

  const openCreateForm = () => {
    setEditId(null);
    setEditStatus(null);
    setTzTouched(false);
    setForm(emptyForm);
    prevAddrTzKey.current = '||';
    setShowForm(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.name.trim()) { toast.error('Venue name is required'); return; }
    // Country is load-bearing, not cosmetic: it derives the venue's currency and
    // timezone, selects its tax rules, and decides which payment methods customers
    // are offered. A venue without one silently falls back to platform defaults.
    if (!form.address?.country?.trim()) {
      toast.error('Venue country is required — it sets this venue\'s currency, timezone, taxes and payment methods');
      return;
    }
    if (!form.timezone) {
      toast.error('Venue timezone is required — set the venue country/city in the address, or pick a timezone manually');
      return;
    }
    if (form.openTime && form.closeTime && form.closeTime <= form.openTime) {
      toast.error('Closing time must be later than opening time');
      return;
    }
    const addressErrors = validateAddress(form.address);
    if (Object.keys(addressErrors).length) {
      toast.error(Object.values(addressErrors)[0]);
      return;
    }
    const coordError = validateCoordinates({ latitude: form.latitude, longitude: form.longitude });
    if (coordError) { toast.error(coordError); return; }
    const hoursError = validateOpeningHours(form.openingHours);
    if (hoursError) { toast.error(hoursError); return; }
    const phoneError = validatePhone(form.supportPhone);
    if (phoneError) { toast.error(`Support phone: ${phoneError}`); return; }
    const waError = validatePhone(form.supportWhatsapp);
    if (waError) { toast.error(`Support WhatsApp: ${waError}`); return; }
    const ownerPhoneError = validatePhone(form.ownerPhone);
    if (ownerPhoneError) { toast.error(`Owner phone: ${ownerPhoneError}`); return; }
    try {
      const phoneSplit = splitPhone(form.supportPhone);
      const waSplit = splitPhone(form.supportWhatsapp);
      const ownerSplit = splitPhone(form.ownerPhone);
      const payload = {
        name: form.name,
        // Legacy free-form `address` is composed server-side from the structured fields.
        address: '',
        addressLine1: form.address?.addressLine1 || '',
        addressLine2: form.address?.addressLine2 || '',
        city: form.address?.city || '',
        state: form.address?.state || '',
        country: form.address?.country || '',
        postalCode: form.address?.postalCode || '',
        latitude: String(form.latitude).trim() === '' ? null : Number(form.latitude),
        longitude: String(form.longitude).trim() === '' ? null : Number(form.longitude),
        supportEmail: form.supportEmail,
        supportPhone: phoneSplit.phone,
        supportPhoneCountryCode: phoneSplit.phoneCountryCode,
        supportWhatsapp: waSplit.phone,
        supportWhatsappCountryCode: waSplit.phoneCountryCode,
        supportPhoneIsWhatsapp: !!form.supportPhoneIsWhatsapp,
        ownerEmail: form.ownerEmail,
        ownerPhone: ownerSplit.phone,
        ownerPhoneCountryCode: ownerSplit.phoneCountryCode,
        ownerPhoneIsWhatsapp: !!form.ownerPhoneIsWhatsapp,
        customerCancellationEnabled: form.customerCancellationEnabled,
        customerCancellationCutoffMinutes: form.customerCancellationCutoffMinutes,
        maxConcurrentBookings: form.maxConcurrentBookings === '' ? null : form.maxConcurrentBookings,
        roomSelectionRequired: !!form.roomSelectionRequired,
        timezone: form.timezone,
        openTime: form.openTime,
        closeTime: form.closeTime,
        openingHours: form.openingHours,
      };
      if (editId) {
        await adminService.updateBinge(editId, payload);
        toast.success('Binge updated');
      } else {
        const res = await adminService.createBinge(payload);
        const created = res?.data?.data;
        const message = res?.data?.message;
        if (created?.status === 'PENDING_APPROVAL') {
          toast.info(message || 'Binge submitted for super-admin approval. It will be hidden from customers and cannot accept bookings until a super-admin approves it.', { autoClose: 8000 });
        } else {
          toast.success(message || 'Binge created');
        }
      }
      resetForm();
      fetchBinges();
    } catch (err) {
      // Surface the server message (e.g. the 423 timezone-permission error from
      // AuthorityLockGuard) so a blocked admin gets the real reason, not a generic one.
      toast.error(err.userMessage || err.response?.data?.message || 'Failed to save binge');
    }
  };

  const handleEdit = (b) => {
    setEditId(b.id);
    setEditStatus(b.status || 'APPROVED'); // missing status fails closed
    setTzTouched(false);
    // Resync the derivation ref so opening the form doesn't count as an
    // "address change" and clobber the venue's stored timezone.
    prevAddrTzKey.current = `${b.country || ''}|${b.state || ''}|${b.city || ''}`;
    setForm({
      name: b.name,
      address: {
        addressLine1: b.addressLine1 || '',
        addressLine2: b.addressLine2 || '',
        city: b.city || '',
        state: b.state || '',
        country: b.country || '',
        postalCode: b.postalCode || '',
      },
      latitude: b.latitude == null ? '' : String(b.latitude),
      longitude: b.longitude == null ? '' : String(b.longitude),
      supportEmail: b.supportEmail || '',
      supportPhone: joinPhone(b.supportPhoneCountryCode, b.supportPhone),
      supportWhatsapp: joinPhone(b.supportWhatsappCountryCode, b.supportWhatsapp),
      supportPhoneIsWhatsapp: b.supportPhoneIsWhatsapp === true,
      ownerEmail: b.ownerEmail || '',
      ownerPhone: joinPhone(b.ownerPhoneCountryCode, b.ownerPhone),
      ownerPhoneIsWhatsapp: b.ownerPhoneIsWhatsapp === true,
      customerCancellationEnabled: b.customerCancellationEnabled !== false,
      customerCancellationCutoffMinutes: b.customerCancellationCutoffMinutes ?? 180,
      maxConcurrentBookings: b.maxConcurrentBookings ?? '',
      roomSelectionRequired: b.roomSelectionRequired === true,
      // No Asia/Kolkata fallback: a legacy venue with a null timezone opens with
      // the field empty so the admin must pick one (it is validated as required on
      // save), rather than being silently stamped with an India zone.
      timezone: b.timezone || '',
      openTime: (b.openTime || '10:00').slice(0, 5),
      closeTime: (b.closeTime || '23:00').slice(0, 5),
      openingHours: normalizeOpeningHours(
        b.openingHours,
        (b.openTime || '10:00').slice(0, 5),
        (b.closeTime || '23:00').slice(0, 5),
      ),
    });
    setShowForm(true);
  };

  const handleOpenDashboardEditor = async (binge) => {
    setDashboardEditor({ open: true, binge });
    setDashboardLoading(true);
    try {
      const [dashRes, etRes] = await Promise.all([
        adminService.getBingeDashboardExperience(binge.id),
        adminService.getAllEventTypes({ bingeId: binge.id }).catch(() => ({ data: { data: [] } })),
      ]);
      setDashboardForm(normalizeDashboardExperience(dashRes.data.data || dashRes.data || null));
      setDashboardEventTypes((etRes.data.data || etRes.data || []).filter((e) => e.active !== false));
    } catch (err) {
      toast.error(err.userMessage || 'Failed to load customer dashboard setup');
      setDashboardEditor({ open: false, binge: null });
    } finally {
      setDashboardLoading(false);
    }
  };

  const handleOpenAboutEditor = async (binge) => {
    setAboutEditor({ open: true, binge });
    setAboutLoading(true);
    try {
      const res = await adminService.getBingeAboutExperience(binge.id);
      setAboutForm(normalizeAboutExperience(res.data.data || res.data || null));
    } catch (err) {
      toast.error(err.userMessage || 'Failed to load customer about page content');
      setAboutEditor({ open: false, binge: null });
    } finally {
      setAboutLoading(false);
    }
  };

  const updateDashboardField = (field, value) => {
    setDashboardForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateDashboardSlide = (index, field, value) => {
    setDashboardForm((prev) => ({
      ...prev,
      slides: prev.slides.map((slide, slideIndex) => (
        slideIndex === index ? { ...slide, [field]: value } : slide
      )),
    }));
  };

  const handleSlideImageUpload = async (index, file) => {
    if (!file) return;
    // Mirror backend MediaController validation (5 MB + JPEG/PNG/WebP/GIF)
    // to give immediate feedback and avoid unnecessary upload traffic / UI hang on large files.
    const MAX_BYTES = 5 * 1024 * 1024;
    const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
    if (file.size > MAX_BYTES) {
      toast.error('Image must be 5 MB or smaller');
      return;
    }
    if (file.type && !ALLOWED_MIME.includes(file.type.toLowerCase())) {
      toast.error('Only JPEG, PNG, WebP, or GIF images are allowed');
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    try {
      const res = await adminService.uploadMedia(formData);
      const url = res.data?.data?.url;
      if (url) {
        updateDashboardSlide(index, 'imageUrl', url);
        toast.success('Image uploaded');
      }
    } catch (err) {
      toast.error(err.userMessage || 'Image upload failed');
    }
  };

  const addDashboardSlide = () => {
    setDashboardForm((prev) => {
      if (prev.slides.length >= 6) return prev;
      return { ...prev, slides: [...prev.slides, createDashboardSlide()] };
    });
  };

  const removeDashboardSlide = (index) => {
    setDashboardForm((prev) => ({
      ...prev,
      slides: prev.slides.filter((_, slideIndex) => slideIndex !== index),
    }));
  };

  const handleSaveDashboardExperience = async (event) => {
    event.preventDefault();
    if (!dashboardEditor.binge) return;

    setDashboardSaving(true);
    try {
      await adminService.updateBingeDashboardExperience(
        dashboardEditor.binge.id,
        sanitizeDashboardExperienceForSave(dashboardForm),
      );
      toast.success('Customer dashboard setup updated');
      resetDashboardEditor();
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save customer dashboard setup');
    } finally {
      setDashboardSaving(false);
    }
  };

  const updateAboutField = (field, value) => {
    setAboutForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateAboutHighlight = (index, field, value) => {
    setAboutForm((prev) => ({
      ...prev,
      highlights: prev.highlights.map((item, itemIndex) => (itemIndex === index ? { ...item, [field]: value } : item)),
    }));
  };

  const updateAboutPolicy = (index, field, value) => {
    setAboutForm((prev) => ({
      ...prev,
      policies: prev.policies.map((item, itemIndex) => (itemIndex === index ? { ...item, [field]: value } : item)),
    }));
  };

  const updateHouseRule = (index, value) => {
    setAboutForm((prev) => ({
      ...prev,
      houseRules: prev.houseRules.map((item, itemIndex) => (itemIndex === index ? value : item)),
    }));
  };

  const handleSaveAboutExperience = async (event) => {
    event.preventDefault();
    if (!aboutEditor.binge) return;

    setAboutSaving(true);
    try {
      await adminService.updateBingeAboutExperience(aboutEditor.binge.id, sanitizeAboutExperienceForSave(aboutForm));
      toast.success('Customer about page updated');
      resetAboutEditor();
    } catch (err) {
      toast.error(err.userMessage || 'Failed to save customer about page');
    } finally {
      setAboutSaving(false);
    }
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

  const handleResubmit = async (binge) => {
    setResubmittingId(binge.id);
    try {
      await adminService.resubmitBinge(binge.id);
      toast.success(`"${binge.name}" re-submitted for approval`);
      fetchBinges();
    } catch (err) {
      toast.error(err.userMessage || err?.response?.data?.message || 'Failed to re-request approval');
    } finally {
      setResubmittingId(null);
    }
  };

  const handleSelect = (binge) => {
    if (binge.status && binge.status !== 'APPROVED') {
      const why = binge.status === 'REJECTED'
        ? `This venue was rejected by a super-admin${binge.approvalRejectionReason ? `: ${binge.approvalRejectionReason}` : '.'}`
        : 'This venue is awaiting super-admin approval. You cannot enter or take bookings for it yet.';
      toast.error(why, { autoClose: 7000 });
      return;
    }
    // The store normalises to the canonical selected-binge shape.
    selectBinge(binge);
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

  // Phone inputs follow the VENUE's country, not a hardcoded India. With a country
  // set, an empty phone field pre-selects that country's calling code (US → +1);
  // with none chosen yet, `undefined` means the picker shows no default code at all
  // instead of forcing +91. Mirrors how currency and timezone derive from country.
  // `null` (not `undefined`) when no country is chosen: undefined would trigger
  // PhoneField's "prop omitted → India" fallback and bring +91 back. null means
  // an explicit "no default country" — empty picker until a country is set.
  const phoneDefaultCountry = form.address?.country?.trim()
    ? form.address.country.trim().toUpperCase()
    : null;

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
        <section className="adm-form adm-flow-card bform">
          <div className="bform-hero">
            <div className="bform-hero-icon" aria-hidden><FiMapPin /></div>
            <div>
              <h3>{editId ? 'Edit venue details' : 'Create a new venue'}</h3>
              <p>
                {editId
                  ? 'Update this venue. Currency, timezone, taxes and payment methods all follow the venue country.'
                  : 'Set up a new venue. Pick the country first — it derives the currency, timezone, taxes and the payment methods customers are offered.'}
              </p>
            </div>
          </div>
          <form onSubmit={handleSubmit}>
            <div className="bform-body">
            <div className="adm-grid-2 bform-grid">
              <div className="input-group full bform-section-head">
                <h4><FiActivity /> Venue basics</h4>
              </div>
              <div className="input-group">
                <label>Binge Name *</label>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="e.g., Downtown Arena" />
              </div>
              <div className="input-group">
                <label>Public Support Email</label>
                <input type="email" value={form.supportEmail} onChange={(e) => setForm({ ...form, supportEmail: e.target.value })} placeholder="support@venue.com" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                  Shown to customers across the portal. Leave empty to hide the email channel for this venue.
                </span>
              </div>
              <div className="input-group full bform-section-head" style={{ marginTop: '0.5rem' }}>
                <h4><FiMapPin /> Location &amp; payments</h4>
                <p>The country sets this venue's currency, timezone, taxes and payment methods.</p>
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <AddressFields
                  legend="Venue address"
                  description="This address is shown to customers and powers postal-code search. All fields optional but recommended."
                  value={form.address}
                  onChange={(addr) => setForm({ ...form, address: addr })}
                />
              </div>
              {/* Currency is derived from the country and is the ONLY currency this venue
                  prices/charges in. Only a super-admin can change the country; a regular
                  admin submits a request. */}
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <label>Payment currency</label>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                  {form.address?.country ? (
                    <>
                      <span className="badge badge-info" style={{ fontSize: '0.85rem' }}>
                        {currencyForCountry(form.address.country)}
                      </span>
                      <small style={{ color: 'var(--text-muted)' }}>
                        Auto-derived from the country ({form.address.country}). Every price, add-on, tax and payment for this venue uses this currency — customers never pick one.
                      </small>
                    </>
                  ) : (
                    <>
                      <span className="badge" style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                        Not set yet
                      </span>
                      <small style={{ color: 'var(--text-muted)' }}>
                        Set the venue country in the address above and the payment currency is derived automatically. Customers never pick one.
                      </small>
                    </>
                  )}
                </div>
                {editId && !isSuperAdmin && (
                  <div style={{ marginTop: '0.5rem', display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                    <small style={{ color: 'var(--text-muted)' }}>Only a super-admin can change a venue's country/currency.</small>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => setCrModal({
                        bingeId: editId,
                        bingeName: form.name,
                        country: form.address?.country || '',
                        reason: '',
                      })}
                    >
                      Request country change
                    </button>
                    {changeRequests.some((r) => r.bingeId === editId && r.status === 'PENDING') && (
                      <span className="badge badge-warning" style={{ fontSize: '0.75rem' }}>Request pending review</span>
                    )}
                  </div>
                )}
                {isSuperAdmin && (
                  <small style={{ color: 'var(--text-muted)', display: 'block', marginTop: '0.35rem' }}>
                    As a super-admin, changing the country above re-derives the currency automatically on save.
                  </small>
                )}
                {editId && (() => {
                  const current = binges.find((b) => b.id === editId);
                  if (!current) return null;
                  const taxesOn = current.taxesEnabled !== false;
                  return (
                    <div style={{ marginTop: '0.6rem', display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                      <span className={`badge ${taxesOn ? 'badge-success' : 'badge-warning'}`}>
                        Taxes {taxesOn ? 'ON' : 'OFF'}
                      </span>
                      {isSuperAdmin ? (
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          onClick={async () => {
                            try {
                              await adminService.setBingeTaxesEnabled(editId, !taxesOn);
                              toast.success(`Taxes ${!taxesOn ? 'enabled' : 'disabled'} for this venue.`);
                              fetchBinges();
                            } catch (err) {
                              toast.error(err.userMessage || err.response?.data?.message || 'Could not update the tax switch.');
                            }
                          }}
                        >
                          {taxesOn ? 'Disable taxes' : 'Enable taxes'}
                        </button>
                      ) : (
                        <small style={{ color: 'var(--text-muted)' }}>
                          The tax system is super-admin controlled. Rules themselves are editable in the tax console.
                        </small>
                      )}
                    </div>
                  );
                })()}
              </div>
              {/* Stripe Connect — only meaningful for an existing venue, since the
                  connected account is created against a saved binge id. */}
              {editId && (
                <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                  <label>Payment payouts (Stripe Connect)</label>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                    {connectStatus === null ? (
                      <small style={{ color: 'var(--text-muted)' }}>Checking connection…</small>
                    ) : connectStatus.connected ? (
                      <>
                        <span className={`badge ${connectStatus.chargesEnabled ? 'badge-success' : 'badge-warning'}`}>
                          {connectStatus.chargesEnabled ? 'Ready to accept payments' : 'Onboarding incomplete'}
                        </span>
                        <small style={{ color: 'var(--text-muted)' }}>
                          {connectStatus.chargesEnabled
                            ? 'Customers can pay this venue in its local payment methods, and payouts go to your bank.'
                            : 'Stripe still needs more details before this venue can take money. Continue onboarding to finish.'}
                        </small>
                      </>
                    ) : (
                      <small style={{ color: 'var(--text-muted)' }}>
                        Not connected. Connect this venue to accept payments in its own currency and
                        local payment methods, with payouts to your bank.
                      </small>
                    )}
                  </div>
                  <div style={{ marginTop: '0.5rem' }}>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      disabled={connectBusy}
                      onClick={startStripeOnboarding}
                    >
                      {connectBusy
                        ? 'Opening Stripe…'
                        : connectStatus?.connected ? 'Continue onboarding' : 'Connect payouts'}
                    </button>
                  </div>
                </div>
              )}
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <VenueCoordinatesField
                  value={{ latitude: form.latitude, longitude: form.longitude }}
                  address={form.address}
                  onChange={({ latitude, longitude }) => setForm({ ...form, latitude, longitude })}
                />
              </div>
              <div className="input-group full bform-section-head" style={{ marginTop: '0.5rem' }}>
                <h4><FiPhone /> Public contact — visible to customers</h4>
                <p>
                  Channels a customer can use for this venue. Any channel left empty is simply
                  not offered to customers (no call / WhatsApp buttons appear for it).
                </p>
              </div>
              <div className="input-group">
                <PhoneField
                  label="Public Support Phone"
                  value={form.supportPhone}
                  onChange={(v) => setForm({ ...form, supportPhone: v })}
                  defaultCountry={phoneDefaultCountry}
                  helpText="Customers see this on their booking confirmations."
                />
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.82rem', marginTop: '0.35rem', lineHeight: 1.2 }}>
                  <input
                    type="checkbox"
                    style={{ width: 16, height: 16, flex: '0 0 auto', margin: 0 }}
                    checked={form.supportPhoneIsWhatsapp}
                    onChange={(e) => setForm({ ...form, supportPhoneIsWhatsapp: e.target.checked })}
                  />
                  This number is also on WhatsApp
                </label>
              </div>
              {/* Only ask for a distinct WhatsApp number when the support phone isn't
                  already WhatsApp — otherwise the extra full-width field is dead space. */}
              {!form.supportPhoneIsWhatsapp && (
                <PhoneField
                  label="Public Support WhatsApp (if different)"
                  value={form.supportWhatsapp}
                  onChange={(v) => setForm({ ...form, supportWhatsapp: v })}
                  defaultCountry={phoneDefaultCountry}
                  helpText="Optional — only if your WhatsApp number differs from the support phone above."
                />
              )}
              <div className="input-group full bform-section-head" style={{ marginTop: '0.5rem' }}>
                <h4><FiShield /> Owner contact — private</h4>
                <p>How the platform (super-admins) reaches you about this venue. Never shown to customers.</p>
              </div>
              <div className="input-group">
                <label>Owner Email (private)</label>
                <input type="email" value={form.ownerEmail} onChange={(e) => setForm({ ...form, ownerEmail: e.target.value })} placeholder="you@example.com" />
              </div>
              <div className="input-group">
                <PhoneField
                  label="Owner Phone (private)"
                  value={form.ownerPhone}
                  onChange={(v) => setForm({ ...form, ownerPhone: v })}
                  defaultCountry={phoneDefaultCountry}
                />
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.82rem', marginTop: '0.35rem', lineHeight: 1.2 }}>
                  <input
                    type="checkbox"
                    style={{ width: 16, height: 16, flex: '0 0 auto', margin: 0 }}
                    checked={form.ownerPhoneIsWhatsapp}
                    onChange={(e) => setForm({ ...form, ownerPhoneIsWhatsapp: e.target.checked })}
                  />
                  This number is also on WhatsApp
                </label>
              </div>
              <div className="input-group full bform-section-head" style={{ marginTop: '0.5rem' }}>
                <h4><FiCalendar /> Booking rules</h4>
                <p>How customers can cancel, how many can book a slot, and whether a room must be chosen.</p>
              </div>
              <div className="input-group">
                <label>Customer Cancellation</label>
                <select value={form.customerCancellationEnabled ? 'enabled' : 'disabled'} onChange={(e) => setForm({ ...form, customerCancellationEnabled: e.target.value === 'enabled' })}>
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                </select>
              </div>
              <div className="input-group">
                <label>Cancellation Cutoff (minutes before start)</label>
                <input type="number" min="0" value={form.customerCancellationCutoffMinutes} onChange={(e) => setForm({ ...form, customerCancellationCutoffMinutes: Number(e.target.value || 0) })} placeholder="180" />
              </div>
              <div className="input-group">
                <label>Max Concurrent Bookings per Slot</label>
                <input type="number" min="1" value={form.maxConcurrentBookings} onChange={(e) => setForm({ ...form, maxConcurrentBookings: e.target.value === '' ? '' : Number(e.target.value) })} placeholder="Unlimited" />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Leave empty for unlimited capacity</span>
              </div>
              <div className="input-group">
                <label>Room Selection</label>
                <select value={form.roomSelectionRequired ? 'required' : 'optional'} onChange={(e) => setForm({ ...form, roomSelectionRequired: e.target.value === 'required' })}>
                  <option value="optional">Optional</option>
                  <option value="required">Required</option>
                </select>
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>When Required, customers and admins must pick a venue room before booking.</span>
              </div>
              <div className="input-group full bform-section-head" style={{ marginTop: '0.5rem' }}>
                <h4><FiClock /> Timezone &amp; operating hours</h4>
                <p>All booking times are interpreted in the venue's timezone. Auto-set from the country — override if needed.</p>
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <label htmlFor="venue-timezone">Venue Timezone *</label>
                <TimezonePicker
                  id="venue-timezone"
                  value={form.timezone}
                  onChange={(tz) => { setTzTouched(true); setForm({ ...form, timezone: tz }); }}
                  required
                  disabled={!tzPermitted}
                  disabledReason={
                    'Auto-detected from the venue address — admins can\'t set it by hand. '
                    + 'If it looks wrong, report it below and a super-admin will resolve it.'}
                />
                {tzPermitted && !tzTouched && (
                  <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                    Auto-set from the venue's city/state/country — pick manually to override.
                  </span>
                )}
                {/* Regular admins can't edit the zone, but if the auto-detected one
                    is wrong they raise a review for a super-admin to resolve. */}
                {!tzPermitted && editId && (
                  <div style={{ marginTop: '0.5rem', display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
                    {changeRequests.some((r) => r.bingeId === editId && r.requestType === 'TIMEZONE_CHANGE' && r.status === 'PENDING') ? (
                      <span className="badge badge-warning" style={{ fontSize: '0.75rem' }}>
                        Timezone review pending super-admin resolution
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => setTzModal({
                          bingeId: editId,
                          bingeName: form.name,
                          currentTz: form.timezone || '',
                          timezone: '',
                          reason: '',
                        })}
                      >
                        Report incorrect timezone
                      </button>
                    )}
                  </div>
                )}
                {!tzPermitted && !editId && (
                  <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                    Auto-detected from the address. After the venue is created you can report it if it looks wrong.
                  </span>
                )}
              </div>
              <div className="input-group">
                <label>Default Opening Time</label>
                <input type="time" value={form.openTime} onChange={(e) => setForm({ ...form, openTime: e.target.value })} required />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Fallback used when a day below has no custom hours.</span>
              </div>
              <div className="input-group">
                <label>Default Closing Time</label>
                <input type="time" value={form.closeTime} onChange={(e) => setForm({ ...form, closeTime: e.target.value })} required />
                <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Fallback used when a day below has no custom hours.</span>
              </div>
              <div className="input-group" style={{ gridColumn: '1 / -1' }}>
                <OperatingHoursField
                  value={form.openingHours}
                  onChange={(oh) => setForm({ ...form, openingHours: oh })}
                />
              </div>
            </div>
            </div>
            <div className="adm-form-actions bform-actions">
              <button type="button" className="btn btn-secondary" onClick={resetForm}>Cancel</button>
              <button type="submit" className="btn btn-primary">{editId ? 'Update venue' : 'Create venue'}</button>
            </div>
          </form>
        </section>
      )}

      {dashboardEditor.open && (
        <section className="adm-form adm-dashboard-editor">
          <div className="adm-dashboard-editor-top">
            <div>
              <h3>Customer dashboard design</h3>
              <p className="adm-form-intro">
                Adjust the experiences section shown on the customer dashboard for <strong>{dashboardEditor.binge?.name}</strong>.
                Switch between a carousel and a grid, then write the slides you want customers to notice first.
              </p>
            </div>
            <div className="adm-flow-actions">
              <button type="button" className="btn btn-secondary" onClick={resetDashboardEditor}>Close</button>
            </div>
          </div>

          {dashboardLoading ? (
            <div className="adm-dashboard-empty">
              <h3>Loading current setup...</h3>
              <p>Fetching the latest customer dashboard content for this venue.</p>
            </div>
          ) : (
            <form onSubmit={handleSaveDashboardExperience}>
              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Section eyebrow</label>
                  <input
                    value={dashboardForm.sectionEyebrow}
                    onChange={(e) => updateDashboardField('sectionEyebrow', e.target.value)}
                    placeholder="Explore Experiences"
                  />
                </div>
                <div className="input-group">
                  <label>Layout</label>
                  <select
                    value={dashboardForm.layout}
                    onChange={(e) => updateDashboardField('layout', e.target.value)}
                  >
                    {DASHBOARD_LAYOUT_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>{option.label}</option>
                    ))}
                  </select>
                  <div className="adm-hint">Carousel turns this section into a focused, swipeable spotlight.</div>
                </div>
              </div>

              <div className="input-group">
                <label>Section title</label>
                <input
                  value={dashboardForm.sectionTitle}
                  onChange={(e) => updateDashboardField('sectionTitle', e.target.value)}
                  placeholder="Pick a setup that matches the mood"
                />
              </div>

              <div className="input-group">
                <label>Section subtitle</label>
                <textarea
                  className="adm-textarea"
                  rows={3}
                  value={dashboardForm.sectionSubtitle}
                  onChange={(e) => updateDashboardField('sectionSubtitle', e.target.value)}
                  placeholder="Optional supporting line below the section title"
                />
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Slides</h4>
                  <p className="adm-hint">Add up to 6 curated slides. Leave this empty to keep the live event cards as the fallback.</p>
                </div>
                <div className="adm-flow-actions">
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={() => setDashboardForm((prev) => ({ ...prev, slides: [] }))}
                  >
                    Use event card fallback
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    onClick={addDashboardSlide}
                    disabled={dashboardForm.slides.length >= 6}
                  >
                    Add Slide
                  </button>
                </div>
              </div>

              {dashboardForm.slides.length === 0 ? (
                <div className="adm-dashboard-empty">
                  <h3>No custom slides yet</h3>
                  <p>The customer portal will keep using the existing experience cards until you save at least one slide.</p>
                </div>
              ) : (
                <div className="adm-dashboard-slides">
                  {dashboardForm.slides.map((slide, index) => (
                    <article key={`${dashboardEditor.binge?.id || 'binge'}-${index}`} className="adm-dashboard-slide">
                      <div className="adm-dashboard-slide-head">
                        <div>
                          <strong>Slide {index + 1}</strong>
                          <div className="adm-dashboard-slide-meta">
                            <span className="adm-badge adm-badge-info">{slide.theme}</span>
                            <span className="adm-hint">{slide.badge || 'Featured badge'}</span>
                          </div>
                        </div>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => removeDashboardSlide(index)}>
                          Remove
                        </button>
                      </div>

                      <div className="adm-grid-2">
                        <div className="input-group">
                          <label>Badge</label>
                          <input
                            value={slide.badge}
                            onChange={(e) => updateDashboardSlide(index, 'badge', e.target.value)}
                            placeholder="Featured"
                          />
                        </div>
                        <div className="input-group">
                          <label>Theme</label>
                          <select
                            value={slide.theme}
                            onChange={(e) => updateDashboardSlide(index, 'theme', e.target.value)}
                          >
                            {DASHBOARD_THEME_OPTIONS.map((option) => (
                              <option key={option.value} value={option.value}>{option.label}</option>
                            ))}
                          </select>
                        </div>
                      </div>

                      <div className="input-group">
                        <label>Headline</label>
                        <input
                          value={slide.headline}
                          onChange={(e) => updateDashboardSlide(index, 'headline', e.target.value)}
                          placeholder="Date-night takeover"
                        />
                      </div>

                      <div className="input-group">
                        <label>Description</label>
                        <textarea
                          className="adm-textarea"
                          rows={3}
                          value={slide.description}
                          onChange={(e) => updateDashboardSlide(index, 'description', e.target.value)}
                          placeholder="Describe the feeling or setup you want to surface in the customer portal"
                        />
                      </div>

                      <div className="input-group">
                        <label>Button label</label>
                        <input
                          value={slide.ctaLabel}
                          onChange={(e) => updateDashboardSlide(index, 'ctaLabel', e.target.value)}
                          placeholder="Open Booking"
                        />
                      </div>

                      <div className="input-group">
                        <label>Link to Event</label>
                        <select
                          value={slide.linkedEventTypeId || ''}
                          onChange={(e) => updateDashboardSlide(index, 'linkedEventTypeId', e.target.value ? Number(e.target.value) : null)}
                        >
                          <option value="">None — opens generic booking</option>
                          {dashboardEventTypes.map((et) => (
                            <option key={et.id} value={et.id}>{et.name} (Rs {et.basePrice})</option>
                          ))}
                        </select>
                        <p className="adm-hint">When linked, clicking this slide pre-selects the event in the booking flow.</p>
                      </div>

                      <div className="input-group">
                        <label>Slide Image</label>
                        <div className="adm-slide-image-upload">
                          {slide.imageUrl ? (
                            <div className="adm-slide-image-preview">
                              <img src={slide.imageUrl} alt={`Slide ${index + 1}`} />
                              <button type="button" className="btn btn-secondary btn-sm" onClick={() => updateDashboardSlide(index, 'imageUrl', '')}>
                                <FiX /> Remove
                              </button>
                            </div>
                          ) : (
                            <label className="adm-slide-image-dropzone">
                              <FiUpload />
                              <span>Upload image (max 5 MB)</span>
                              <input
                                type="file"
                                accept="image/jpeg,image/png,image/webp,image/gif"
                                style={{ display: 'none' }}
                                onChange={(e) => handleSlideImageUpload(index, e.target.files[0])}
                              />
                            </label>
                          )}
                          <input
                            value={slide.imageUrl || ''}
                            onChange={(e) => updateDashboardSlide(index, 'imageUrl', e.target.value)}
                            placeholder="Or paste an image URL"
                            className="adm-slide-image-url-input"
                          />
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
              )}

              <div className="adm-form-actions">
                <button type="button" className="btn btn-secondary" onClick={resetDashboardEditor}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={dashboardSaving}>
                  {dashboardSaving ? 'Saving...' : 'Save Customer Dashboard'}
                </button>
              </div>
            </form>
          )}
        </section>
      )}

      {aboutEditor.open && (
        <section className="adm-form adm-dashboard-editor">
          <div className="adm-dashboard-editor-top">
            <div>
              <h3>Customer about page</h3>
              <p className="adm-form-intro">
                Configure the content customers see in the About page for <strong>{aboutEditor.binge?.name}</strong>.
                Add highlights, rules, and policies to set clear expectations before checkout.
              </p>
            </div>
            <div className="adm-flow-actions">
              <button type="button" className="btn btn-secondary" onClick={resetAboutEditor}>Close</button>
            </div>
          </div>

          {aboutLoading ? (
            <div className="adm-dashboard-empty">
              <h3>Loading current content...</h3>
              <p>Fetching the latest About page setup for this venue.</p>
            </div>
          ) : (
            <form onSubmit={handleSaveAboutExperience}>
              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Section eyebrow</label>
                  <input value={aboutForm.sectionEyebrow} onChange={(e) => updateAboutField('sectionEyebrow', e.target.value)} />
                </div>
                <div className="input-group">
                  <label>Section title</label>
                  <input value={aboutForm.sectionTitle} onChange={(e) => updateAboutField('sectionTitle', e.target.value)} />
                </div>
              </div>

              <div className="input-group">
                <label>Section subtitle</label>
                <textarea className="adm-textarea" rows={2} value={aboutForm.sectionSubtitle} onChange={(e) => updateAboutField('sectionSubtitle', e.target.value)} />
              </div>

              <div className="adm-grid-2">
                <div className="input-group">
                  <label>Hero title</label>
                  <input value={aboutForm.heroTitle} onChange={(e) => updateAboutField('heroTitle', e.target.value)} />
                </div>
                <div className="input-group">
                  <label>Contact heading</label>
                  <input value={aboutForm.contactHeading} onChange={(e) => updateAboutField('contactHeading', e.target.value)} />
                </div>
              </div>

              <div className="input-group">
                <label>Hero description</label>
                <textarea className="adm-textarea" rows={4} value={aboutForm.heroDescription} onChange={(e) => updateAboutField('heroDescription', e.target.value)} />
              </div>

              <div className="input-group">
                <label>Contact description</label>
                <textarea className="adm-textarea" rows={3} value={aboutForm.contactDescription} onChange={(e) => updateAboutField('contactDescription', e.target.value)} />
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Highlights</h4>
                  <p className="adm-hint">Show what makes this binge stand out for customers.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, highlights: [...prev.highlights, createAboutHighlight()].slice(0, 8) }))}>
                    Add Highlight
                  </button>
                </div>
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.highlights.map((item, index) => (
                  <article key={`about-highlight-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Highlight {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, highlights: prev.highlights.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Title</label>
                      <input value={item.title} onChange={(e) => updateAboutHighlight(index, 'title', e.target.value)} />
                    </div>
                    <div className="input-group">
                      <label>Description</label>
                      <textarea className="adm-textarea" rows={3} value={item.description} onChange={(e) => updateAboutHighlight(index, 'description', e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>House rules</h4>
                  <p className="adm-hint">Customers see these as numbered rules before booking.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, houseRules: [...prev.houseRules, ''].slice(0, 12) }))}>
                    Add Rule
                  </button>
                </div>
              </div>
              <div className="input-group">
                <label>Rules section title</label>
                <input value={aboutForm.houseRulesTitle} onChange={(e) => updateAboutField('houseRulesTitle', e.target.value)} />
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.houseRules.map((rule, index) => (
                  <article key={`about-rule-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Rule {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, houseRules: prev.houseRules.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Rule text</label>
                      <textarea className="adm-textarea" rows={2} value={rule} onChange={(e) => updateHouseRule(index, e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-dashboard-editor-actions">
                <div>
                  <h4>Policies</h4>
                  <p className="adm-hint">Capture booking, cancellation, and compliance information clearly.</p>
                </div>
                <div className="adm-flow-actions">
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, policies: [...prev.policies, createAboutPolicy()].slice(0, 8) }))}>
                    Add Policy
                  </button>
                </div>
              </div>
              <div className="input-group">
                <label>Policies section title</label>
                <input value={aboutForm.policyTitle} onChange={(e) => updateAboutField('policyTitle', e.target.value)} />
              </div>
              <div className="adm-dashboard-slides">
                {aboutForm.policies.map((item, index) => (
                  <article key={`about-policy-${index}`} className="adm-dashboard-slide">
                    <div className="adm-dashboard-slide-head">
                      <strong>Policy {index + 1}</strong>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAboutForm((prev) => ({ ...prev, policies: prev.policies.filter((_, i) => i !== index) }))}>Remove</button>
                    </div>
                    <div className="input-group">
                      <label>Policy title</label>
                      <input value={item.title} onChange={(e) => updateAboutPolicy(index, 'title', e.target.value)} />
                    </div>
                    <div className="input-group">
                      <label>Policy description</label>
                      <textarea className="adm-textarea" rows={3} value={item.description} onChange={(e) => updateAboutPolicy(index, 'description', e.target.value)} />
                    </div>
                  </article>
                ))}
              </div>

              <div className="adm-form-actions">
                <button type="button" className="btn btn-secondary" onClick={resetAboutEditor}>Cancel</button>
                <button type="submit" className="btn btn-primary" disabled={aboutSaving}>
                  {aboutSaving ? 'Saving...' : 'Save About Page'}
                </button>
              </div>
            </form>
          )}
        </section>
      )}

      {tierEditor.open && (
        <section className="adm-form adm-flow-card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
            <div>
              <h3>Cancellation refund tiers & policy</h3>
              <p className="adm-form-intro">
                Configure refund tiers, refund applicability (yes/no per payment state), and the temporary freeze settings for <strong>{tierEditor.binge?.name}</strong>. Scroll down to find the freeze duration, threshold counters, and refund-yes/no toggles.
              </p>
            </div>
            <button type="button" className="btn btn-secondary" onClick={() => setTierEditor({ open: false, binge: null })}>Close</button>
          </div>
          {tierLoading ? <p>Loading...</p> : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Hours before start</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Refund %</th>
                    <th style={{ textAlign: 'left', padding: '0.4rem 0.6rem', borderBottom: '1px solid var(--border)' }}>Label</th>
                    <th style={{ width: '60px', borderBottom: '1px solid var(--border)' }} />
                  </tr>
                </thead>
                <tbody>
                  {tierRows.map((row, i) => (
                    <tr key={`tier-${i}-${row.hoursBeforeStart}`}>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input type="number" min="0" style={{ width: '80px' }} value={row.hoursBeforeStart} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, hoursBeforeStart: Number(e.target.value) } : r))} />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input type="number" min="0" max="100" style={{ width: '80px' }} value={row.refundPercentage} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, refundPercentage: Number(e.target.value) } : r))} />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <input value={row.label} onChange={(e) => setTierRows(prev => prev.map((r, j) => j === i ? { ...r, label: e.target.value } : r))} placeholder="e.g. Full refund" />
                      </td>
                      <td style={{ padding: '0.3rem 0.6rem' }}>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTierRows(prev => prev.filter((_, j) => j !== i))}>
                          <FiTrash2 />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTierRows(prev => [...prev, { hoursBeforeStart: 0, refundPercentage: 0, label: '' }])}>
                  <FiPlus /> Add Tier
                </button>
                <button type="button" className="btn btn-primary" disabled={tierSaving} onClick={handleSaveTiers}>
                  {tierSaving ? 'Saving...' : 'Save Tiers'}
                </button>
              </div>

              {/* ── Cancellation policy (binge-level freeze + refund flags) ── */}
              <div style={{ marginTop: '2rem', paddingTop: '1.25rem', borderTop: '1px solid var(--border)' }}>
                <h4 style={{ margin: '0 0 0.5rem' }}>Cancellation policy</h4>
                <p className="adm-form-intro" style={{ marginTop: 0 }}>
                  Controls whether refunds apply when customers cancel under each payment state, and the
                  abuse-protection freeze that temporarily blocks the booking flow after repeated abandons.
                </p>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem 1.5rem', marginBottom: '0.75rem' }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <input
                      type="checkbox"
                      checked={!!policyForm.refundOnSuccessfulPaymentCancel}
                      onChange={(e) => setPolicyForm(p => ({ ...p, refundOnSuccessfulPaymentCancel: e.target.checked }))}
                    />
                    Refund on successful-payment cancellation
                  </label>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <input
                      type="checkbox"
                      checked={!!policyForm.refundOnPendingPaymentCancel}
                      onChange={(e) => setPolicyForm(p => ({ ...p, refundOnPendingPaymentCancel: e.target.checked }))}
                    />
                    Refund on pending-payment cancellation
                  </label>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <input
                      type="checkbox"
                      checked={!!policyForm.freezePolicyEnabled}
                      onChange={(e) => setPolicyForm(p => ({ ...p, freezePolicyEnabled: e.target.checked }))}
                    />
                    Enable temporary freeze on repeated abandons
                  </label>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(180px, 1fr))', gap: '0.75rem 1.25rem', marginBottom: '0.75rem' }}>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <span>Freeze duration (minutes)</span>
                    <input
                      type="number" min="1" max="10080"
                      value={policyForm.freezeDurationMinutes}
                      onChange={(e) => setPolicyForm(p => ({ ...p, freezeDurationMinutes: Number(e.target.value) }))}
                      disabled={!policyForm.freezePolicyEnabled}
                    />
                  </label>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <span>Max pending cancellations before freeze</span>
                    <input
                      type="number" min="1" max="50"
                      value={policyForm.maxPendingCancelsBeforeFreeze}
                      onChange={(e) => setPolicyForm(p => ({ ...p, maxPendingCancelsBeforeFreeze: Number(e.target.value) }))}
                      disabled={!policyForm.freezePolicyEnabled}
                    />
                  </label>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <span>Max payment-timeouts before freeze</span>
                    <input
                      type="number" min="1" max="50"
                      value={policyForm.maxPendingPaymentTimeoutsBeforeFreeze}
                      onChange={(e) => setPolicyForm(p => ({ ...p, maxPendingPaymentTimeoutsBeforeFreeze: Number(e.target.value) }))}
                      disabled={!policyForm.freezePolicyEnabled}
                    />
                  </label>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <span>Max unpaid bookings per customer</span>
                    <input
                      type="number" min="1" max="50"
                      value={policyForm.maxUnpaidBookingsPerCustomer}
                      onChange={(e) => setPolicyForm(p => ({ ...p, maxUnpaidBookingsPerCustomer: Number(e.target.value) }))}
                    />
                    <small style={{ color: 'var(--text-muted)' }}>
                      How many unpaid reservations one customer can hold here at once before new
                      bookings are stopped. Customers can always pay or cancel unpaid bookings
                      themselves (free), so this is a soft cap — not a punishment.
                    </small>
                  </label>
                </div>

                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button type="button" className="btn btn-primary" disabled={policySaving} onClick={handleSavePolicy}>
                    {policySaving ? 'Saving...' : 'Save Policy'}
                  </button>
                </div>
              </div>
            </>
          )}
        </section>
      )}

      {changeRequests.length > 0 && (
        <section className="adm-form adm-flow-card" style={{ marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
            <div>
              <h3>Venue change requests</h3>
              <p className="adm-form-intro">
                {isSuperAdmin
                  ? 'Approve, resolve or reject venue country changes and timezone reviews. Approving a country change re-derives the currency; resolving a timezone review applies the zone you pick.'
                  : 'Your country-change requests and timezone reviews. A super-admin reviews each one; you are notified of the decision.'}
              </p>
            </div>
            <button type="button" className="btn btn-secondary btn-sm" onClick={loadChangeRequests}><FiRefreshCw /> Refresh</button>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem', marginTop: '0.75rem' }}>
            {changeRequests.map((r) => (
              <div key={r.id} style={{
                display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap',
                border: '1px solid var(--border-color, rgba(128,128,128,0.25))', borderRadius: '8px', padding: '0.6rem 0.85rem',
              }}>
                <strong>{r.bingeName || `Venue #${r.bingeId}`}</strong>
                {r.requestType === 'TIMEZONE_CHANGE' ? (
                  <span style={{ fontFamily: 'monospace' }}>
                    <span className="badge badge-info" style={{ fontSize: '0.7rem', marginRight: 6 }}>TIMEZONE</span>
                    {r.currentValue || '—'} → {r.requestedValue || '(super-admin to set)'}
                  </span>
                ) : (
                  <span style={{ fontFamily: 'monospace' }}>
                    {r.currentValue || '—'} ({r.currentCurrency || '—'}) → {r.requestedValue} ({r.requestedCurrency})
                  </span>
                )}
                <span className={`badge ${r.status === 'PENDING' ? 'badge-warning' : r.status === 'APPROVED' ? 'badge-success' : 'badge-danger'}`}>
                  {r.status}
                </span>
                {r.reason && <small style={{ color: 'var(--text-muted)' }}>Reason: {r.reason}</small>}
                {r.decisionNote && <small style={{ color: 'var(--text-muted)' }}>Decision note: {r.decisionNote}</small>}
                <span style={{ flex: 1 }} />
                {isSuperAdmin && r.status === 'PENDING' && (
                  <>
                    <button type="button" className="btn btn-primary btn-sm"
                            onClick={() => setCrDecision({ request: r, action: 'approve', note: '' })}>
                      Approve
                    </button>
                    <button type="button" className="btn btn-danger btn-sm"
                            onClick={() => setCrDecision({ request: r, action: 'reject', note: '' })}>
                      Reject
                    </button>
                  </>
                )}
                {!isSuperAdmin && r.status === 'PENDING' && (
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => withdrawChangeRequest(r.id)}>
                    Withdraw
                  </button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {crModal && (
        <div className="modal-overlay" onClick={() => !crBusy && setCrModal(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '460px' }}>
            <div className="modal-header">
              <h2>Request country change</h2>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setCrModal(null)} disabled={crBusy}><FiX /></button>
            </div>
            <p style={{ marginTop: 0, color: 'var(--text-muted)' }}>
              {crModal.bingeName ? `“${crModal.bingeName}”` : 'This venue'} will move to the requested country only after a
              super-admin approves. The venue's payment currency is re-derived from the new country.
            </p>
            <div className="form-row">
              <label>New country (2-letter ISO code)</label>
              <input
                type="text" maxLength={2} placeholder="e.g. US, CN, AE"
                value={crModal.country}
                onChange={(e) => setCrModal((m) => ({ ...m, country: e.target.value.toUpperCase() }))}
              />
              {/^[A-Za-z]{2}$/.test(crModal.country || '') && (
                <small style={{ color: 'var(--text-muted)' }}>
                  Currency would become <strong>{currencyForCountry(crModal.country)}</strong>
                </small>
              )}
            </div>
            <div className="form-row">
              <label>Reason (recommended)</label>
              <textarea
                rows={3} placeholder="Why should this venue move? Helps the super-admin decide."
                value={crModal.reason}
                onChange={(e) => setCrModal((m) => ({ ...m, reason: e.target.value }))}
              />
            </div>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button type="button" className="btn btn-secondary" onClick={() => setCrModal(null)} disabled={crBusy}>Cancel</button>
              <button type="button" className="btn btn-primary" onClick={submitCountryRequest} disabled={crBusy}>
                {crBusy ? 'Sending…' : 'Send request'}
              </button>
            </div>
          </div>
        </div>
      )}

      {crDecision && (() => {
        const isTz = crDecision.request?.requestType === 'TIMEZONE_CHANGE';
        return (
        <div className="modal-overlay" onClick={() => !crBusy && setCrDecision(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '460px' }}>
            <div className="modal-header">
              <h2>
                {crDecision.action === 'approve' ? (isTz ? 'Resolve' : 'Approve') : 'Reject'}{' '}
                {isTz ? 'timezone review' : 'country change'}
              </h2>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setCrDecision(null)} disabled={crBusy}><FiX /></button>
            </div>
            {isTz ? (
              <>
                <p style={{ marginTop: 0 }}>
                  <strong>{crDecision.request.bingeName || `Venue #${crDecision.request.bingeId}`}</strong> — current zone:{' '}
                  {crDecision.request.currentValue || '—'}
                  {crDecision.request.requestedValue ? <> · admin suggests <strong>{crDecision.request.requestedValue}</strong></> : null}
                </p>
                {crDecision.request.reason && (
                  <p style={{ color: 'var(--text-muted)' }}>Reason: “{crDecision.request.reason}”</p>
                )}
                {crDecision.action === 'approve' && (
                  <div className="form-row">
                    <label>Timezone to apply</label>
                    <TimezonePicker
                      id="tz-resolve"
                      value={crDecision.timezone || crDecision.request.requestedValue || crDecision.request.currentValue || ''}
                      onChange={(tz) => setCrDecision((d) => ({ ...d, timezone: tz }))}
                      required
                    />
                  </div>
                )}
              </>
            ) : (
              <>
                <p style={{ marginTop: 0 }}>
                  <strong>{crDecision.request.bingeName || `Venue #${crDecision.request.bingeId}`}</strong>:{' '}
                  {crDecision.request.currentValue || '—'} ({crDecision.request.currentCurrency || '—'}) →{' '}
                  {crDecision.request.requestedValue} ({crDecision.request.requestedCurrency})
                </p>
                {crDecision.action === 'approve' && (
                  <p style={{ color: 'var(--text-muted)' }}>
                    Approving applies the change immediately: the venue's country and payment currency update, and every new
                    booking at this venue charges in {crDecision.request.requestedCurrency}. Existing prices keep their numeric
                    values — review event/add-on pricing after the move.
                  </p>
                )}
              </>
            )}
            <div className="form-row">
              <label>Note to the requesting admin (optional)</label>
              <textarea
                rows={3}
                value={crDecision.note}
                onChange={(e) => setCrDecision((d) => ({ ...d, note: e.target.value }))}
              />
            </div>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button type="button" className="btn btn-secondary" onClick={() => setCrDecision(null)} disabled={crBusy}>Cancel</button>
              <button type="button" className={`btn ${crDecision.action === 'approve' ? 'btn-primary' : 'btn-danger'}`}
                      onClick={decideChangeRequest} disabled={crBusy}>
                {crBusy ? 'Working…' : crDecision.action === 'approve' ? (isTz ? 'Resolve & apply' : 'Approve & apply') : 'Reject request'}
              </button>
            </div>
          </div>
        </div>
        );
      })()}

      {tzModal && (
        <div className="modal-overlay" onClick={() => !crBusy && setTzModal(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '480px' }}>
            <div className="modal-header">
              <h2>Report incorrect timezone</h2>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setTzModal(null)} disabled={crBusy}><FiX /></button>
            </div>
            <p style={{ marginTop: 0, color: 'var(--text-muted)' }}>
              {tzModal.bingeName ? `“${tzModal.bingeName}”` : 'This venue'} is auto-detected as{' '}
              <strong>{tzModal.currentTz || '—'}</strong>. You can't change it directly — a super-admin resolves it.
            </p>
            <div className="form-row">
              <label>Correct timezone (optional suggestion)</label>
              <TimezonePicker
                id="tz-report"
                value={tzModal.timezone}
                onChange={(tz) => setTzModal((m) => ({ ...m, timezone: tz }))}
              />
              <small style={{ color: 'var(--text-muted)' }}>Leave blank to let the super-admin choose.</small>
            </div>
            <div className="form-row">
              <label>Why is it wrong? (required)</label>
              <textarea
                rows={3} placeholder="e.g. The venue is in Arizona (no DST), but it was set to America/Denver."
                value={tzModal.reason}
                onChange={(e) => setTzModal((m) => ({ ...m, reason: e.target.value }))}
              />
            </div>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button type="button" className="btn btn-secondary" onClick={() => setTzModal(null)} disabled={crBusy}>Cancel</button>
              <button type="button" className="btn btn-primary" onClick={submitTimezoneRequest} disabled={crBusy}>
                {crBusy ? 'Sending…' : 'Send review'}
              </button>
            </div>
          </div>
        </div>
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
          {binges.map((b) => {
            const isPending = b.status === 'PENDING_APPROVAL';
            const isRejected = b.status === 'REJECTED';
            const isApproved = !b.status || b.status === 'APPROVED';
            const cardClass = `adm-venue-card${b.active && isApproved ? '' : ' inactive'}`;
            return (
            <article key={b.id} className={cardClass}>
              <div className="adm-venue-card-top">
                <span className="adm-kicker">Venue option</span>
                {isPending ? (
                  <span className="adm-badge adm-badge-warning">Pending approval</span>
                ) : isRejected ? (
                  <span className="adm-badge adm-badge-danger">Rejected</span>
                ) : (
                  <span className={`adm-badge ${b.active ? 'adm-badge-active' : 'adm-badge-inactive'}`}>
                    {b.active ? 'Active' : 'Inactive'}
                  </span>
                )}
              </div>

              <div className="adm-venue-card-copy">
                <h3>{b.name}</h3>
                <p>{b.address || 'Add an address so the venue reads clearly across staff and customer workflows.'}</p>
                {reviewSummaries[b.id]?.averageRating > 0 && (
                  <StarRating avg={reviewSummaries[b.id].averageRating} count={reviewSummaries[b.id].totalReviews} />
                )}
              </div>

              <p className="adm-venue-card-note">
                {isPending
                  ? 'A super-admin must approve this venue before it appears to customers or accepts any bookings. You will be notified once a decision is made.'
                  : isRejected
                  ? (b.approvalRejectionReason
                      ? `Super-admin rejected this venue: “${b.approvalRejectionReason}”. It will not be visible to customers.`
                      : 'Super-admin rejected this venue. It will not be visible to customers.')
                  : b.active
                  ? 'Dashboard, bookings, availability, and reporting tools are ready for this venue.'
                  : 'Reactivate this venue whenever you want it back in the live admin and booking rotation.'}
              </p>

              <div className="adm-venue-actions">
                {b.active && isApproved && (
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => handleSelect(b)}>
                    Enter <FiArrowRight />
                  </button>
                )}
                {isPending && (
                  <button type="button" className="btn btn-secondary btn-sm" disabled
                    title="Locked until a super-admin approves this venue">
                    <FiClock style={{ marginRight: 3 }} /> Awaiting approval
                  </button>
                )}
                {isRejected && (
                  <button type="button" className="btn btn-primary btn-sm"
                    onClick={() => handleResubmit(b)}
                    disabled={resubmittingId === b.id}
                    title="Send this venue back to a super-admin for another review">
                    <FiRefreshCw style={{ marginRight: 3 }} /> {resubmittingId === b.id ? 'Re-requesting…' : 'Re-request approval'}
                  </button>
                )}
                {/* Operational configuration is hidden for a REJECTED venue — it is
                    frozen to lifecycle actions (edit / delete / re-request) until a
                    super-admin approves it. The backend enforces the same
                    (BingeApprovalInterceptor); this hiding is presentation only. */}
                {!isRejected && (
                  <>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenDashboardEditor(b)}>
                      Dashboard design
                    </button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenAboutEditor(b)}>
                      About page
                    </button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleOpenTierEditor(b)}>
                      Cancellation tiers & policy
                    </button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => setLoyaltyEditor({ open: true, binge: b })}>
                      Loyalty
                    </button>
                    <button type="button" className="btn btn-secondary btn-sm"
                      title="Identity, lifecycle audit and option access for this venue"
                      onClick={() => { selectBinge(b); navigate('/admin/about-binge'); }}>
                      About / Access
                    </button>
                  </>
                )}
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => handleEdit(b)}>
                  <FiEdit2 style={{ marginRight: 3 }} /> {isRejected ? 'Edit & fix' : 'Edit'}
                </button>
                {isApproved && (
                  <button type="button" className={`btn btn-sm ${b.active ? 'btn-danger' : ''}`}
                    style={!b.active ? { background: 'var(--success)', color: '#fff' } : undefined}
                    onClick={() => handleToggle(b.id)}>
                    {b.active ? <><FiToggleLeft style={{ marginRight: 3 }} /> Deactivate</> : <><FiToggleRight style={{ marginRight: 3 }} /> Activate</>}
                  </button>
                )}
                {!b.active && !isPending && (
                  <button type="button" className="btn adm-danger-btn btn-sm" onClick={() => handleDelete(b)}>
                    <FiTrash2 style={{ marginRight: 3 }} /> Delete
                  </button>
                )}
              </div>
            </article>
            );
          })}
        </div>
      )}

      {loyaltyEditor.open && (
        <BingeLoyaltySection
          binge={loyaltyEditor.binge}
          onClose={() => setLoyaltyEditor({ open: false, binge: null })}
        />
      )}
    </div>
  );
}
