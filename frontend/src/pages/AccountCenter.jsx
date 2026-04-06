import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { authService, bookingService, paymentService } from '../services/endpoints';
import SEO from '../components/SEO';
import { SkeletonGrid } from '../components/ui/Skeleton';
import {
  ACCOUNT_PREFERENCES_DEFAULTS,
  buildAccountPreferencesPayload,
  buildSupportEmailHref,
  buildSupportWhatsAppHref,
  CUSTOMER_SUPPORT,
  EXPERIENCE_STEPS,
  getAccountPreferences,
  getCallSupportHref,
  getMemberTier,
  HELP_FAQS,
  mergeSupportContact,
  MEMBER_OFFERS,
} from '../services/customerExperience';
import { FiArrowRight, FiBell, FiCalendar, FiCheckCircle, FiClock, FiCreditCard, FiGift, FiHeart, FiLifeBuoy, FiMail, FiMessageCircle, FiPhoneCall, FiSettings, FiShield, FiStar, FiUser } from 'react-icons/fi';
import './CustomerHub.css';

export default function AccountCenter() {
  const { user, setUser } = useAuth();
  const { selectedBinge } = useBinge();
  const [loading, setLoading] = useState(true);
  const [profile, setProfile] = useState(user);
  const [supportContact, setSupportContact] = useState(CUSTOMER_SUPPORT);
  const [currentBookings, setCurrentBookings] = useState([]);
  const [pastBookings, setPastBookings] = useState([]);
  const [payments, setPayments] = useState([]);
  const [myPricing, setMyPricing] = useState(null);
  const [preferences, setPreferences] = useState({ ...ACCOUNT_PREFERENCES_DEFAULTS });

  useEffect(() => {
    setProfile(user);
    setPreferences(getAccountPreferences(user || {}));
  }, [user]);

  useEffect(() => {
    setLoading(true);
    Promise.allSettled([
      authService.getProfile(),
      authService.getSupportContact(),
      selectedBinge ? bookingService.getCurrentBookings() : Promise.resolve({ data: { data: [] } }),
      selectedBinge ? bookingService.getPastBookings() : Promise.resolve({ data: { data: [] } }),
      selectedBinge ? paymentService.getMyPayments() : Promise.resolve({ data: { data: [] } }),
      selectedBinge ? bookingService.getMyPricing() : Promise.resolve({ data: { data: null } }),
    ]).then(([profileRes, supportRes, currentRes, pastRes, paymentRes, pricingRes]) => {
      const profileData = profileRes.status === 'fulfilled' ? (profileRes.value.data.data || null) : null;
      if (profileData) {
        setProfile(profileData);
        setPreferences(getAccountPreferences(profileData));
      }

      setSupportContact(supportRes.status === 'fulfilled' ? mergeSupportContact(supportRes.value.data.data) : CUSTOMER_SUPPORT);
      setCurrentBookings(currentRes.status === 'fulfilled' ? (currentRes.value.data.data || []) : []);
      setPastBookings(pastRes.status === 'fulfilled' ? (pastRes.value.data.data || []) : []);
      setPayments(paymentRes.status === 'fulfilled' ? (paymentRes.value.data.data || []) : []);
      setMyPricing(pricingRes.status === 'fulfilled' ? (pricingRes.value.data.data || null) : null);
    }).finally(() => setLoading(false));
  }, [selectedBinge, setUser, user]);

  const customer = profile || user || {};
  const support = mergeSupportContact(supportContact);
  const hasVenueSelected = Boolean(selectedBinge);
  const primaryLink = hasVenueSelected ? '/my-bookings' : '/binges';
  const primaryLabel = hasVenueSelected ? 'Open Booking Control Center' : 'Choose Venue';
  const secondaryLink = hasVenueSelected ? '/payments' : '/binges';
  const secondaryLabel = hasVenueSelected ? 'Review Payments' : 'Browse Venues';

  const completedCount = pastBookings.length;
  const successfulPayments = payments.filter((payment) => payment.status === 'SUCCESS');
  const totalSpend = successfulPayments.reduce((sum, payment) => sum + (payment.amount || 0), 0);
  const pendingPayments = currentBookings.filter((booking) => booking.paymentStatus !== 'SUCCESS' && booking.status !== 'CANCELLED');
  const memberTier = useMemo(() => getMemberTier(completedCount, totalSpend), [completedCount, totalSpend]);
  const pricingLabel = myPricing?.rateCodeName || memberTier;
  const reminderSummary = `${preferences.notificationChannel.toLowerCase()} reminders ${preferences.reminderLeadDays} day(s) before each celebration`;

  const updatePreference = (key, value) => {
    setPreferences((current) => ({ ...current, [key]: value }));
  };

  const savePreferences = async () => {
    if (!customer?.id) {
      return;
    }

    try {
      const response = await authService.updateAccountPreferences(buildAccountPreferencesPayload(preferences));
      const updatedUser = response.data.data;
      setProfile(updatedUser);
      setPreferences(getAccountPreferences(updatedUser));
      setUser({ ...(user || {}), ...updatedUser });
      toast.success('Account preferences saved');
    } catch (error) {
      const message = error.userMessage || error.response?.data?.message || 'Failed to save account preferences';
      toast.error(message);
    }
  };

  if (loading) {
    return (
      <div className="container customer-hub">
        <SEO title="Account" description="Profile, reminders, preferences, benefits, and support in one customer account center." />
        <SkeletonGrid count={6} columns={2} />
      </div>
    );
  }

  return (
    <div className="container customer-hub">
      <SEO title="Account" description="Manage profile details, celebration reminders, pricing perks, and support from a single customer account center." />

      <section className="customer-hub-hero">
        <div className="customer-hub-copy">
          <span className="customer-hub-kicker">Account Center</span>
          <h1>Keep your profile, reminders, support, and member benefits in one self-service space.</h1>
          <p>
            This is your home for contact details, celebration planning preferences, retention offers, and support access.
            {selectedBinge ? ` You are currently planning around ${selectedBinge.name}.` : ''}
          </p>
          <div className="customer-hub-actions">
            <Link to={primaryLink} className="btn btn-primary">{primaryLabel}</Link>
            <Link to={secondaryLink} className="btn btn-secondary">{secondaryLabel}</Link>
          </div>
        </div>

        <aside className="customer-hub-highlight card">
          <span className="customer-hub-panel-label">Member snapshot</span>
          <h2>{memberTier}</h2>
          <p>{hasVenueSelected ? `${pricingLabel} pricing is currently the best available plan on your account.` : 'Choose a venue to unlock your booking timeline, payments, and venue-specific pricing view.'}</p>
          <div className="customer-hub-highlight-meta">
            <span className="badge badge-success">{completedCount} completed visits</span>
            <span className="badge badge-info">{pendingPayments.length} active booking items</span>
          </div>
          <strong>Rs {Number(totalSpend || 0).toLocaleString()}</strong>
          <div className="customer-hub-inline-actions">
            {support.whatsappRaw && <a href={buildSupportWhatsAppHref({ supportContact: support, customerName: `${customer?.firstName || ''} ${customer?.lastName || ''}`.trim(), topic: 'account help' })} target="_blank" rel="noreferrer" className="btn btn-primary btn-sm">WhatsApp Support</a>}
            {support.email && <a href={buildSupportEmailHref({ supportContact: support, customerName: `${customer?.firstName || ''} ${customer?.lastName || ''}`.trim(), topic: 'Account support' })} className="btn btn-secondary btn-sm">Email Support</a>}
          </div>
        </aside>
      </section>

      <section className="customer-account-grid">
        <article className="customer-hub-panel card customer-account-card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Profile</span>
              <h2>Your customer profile</h2>
            </div>
            <span className="customer-account-avatar"><FiUser /></span>
          </div>
          <div className="customer-account-list">
            <div>
              <span>Name</span>
              <strong>{[customer?.firstName, customer?.lastName].filter(Boolean).join(' ') || 'Customer'}</strong>
            </div>
            <div>
              <span>Email</span>
              <strong>{customer?.email || 'Not available'}</strong>
            </div>
            <div>
              <span>Phone</span>
              <strong>{customer?.phone || 'Add a phone number to get faster help'}</strong>
            </div>
            <div>
              <span>Current venue</span>
              <strong>{selectedBinge?.name || 'Choose a venue to personalize booking flow'}</strong>
            </div>
          </div>
          <p className="customer-account-note">Your email and phone power booking updates, reminders, and same-day support.</p>
        </article>

        <article className="customer-hub-panel card customer-account-card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Contact</span>
              <h2>Support and trust</h2>
            </div>
            <span className="customer-account-avatar"><FiLifeBuoy /></span>
          </div>
          <div className="customer-account-support-grid">
            {support.email && <a href={buildSupportEmailHref({ supportContact: support, customerName: `${customer?.firstName || ''} ${customer?.lastName || ''}`.trim(), topic: 'Customer support' })} className="customer-account-support-link">
              <FiMail />
              <div>
                <strong>Email support</strong>
                <span>{support.email}</span>
              </div>
            </a>}
            {support.whatsappRaw && <a href={buildSupportWhatsAppHref({ supportContact: support, customerName: `${customer?.firstName || ''} ${customer?.lastName || ''}`.trim(), topic: 'support' })} target="_blank" rel="noreferrer" className="customer-account-support-link">
              <FiMessageCircle />
              <div>
                <strong>WhatsApp</strong>
                <span>Fastest path for booking changes and payment help</span>
              </div>
            </a>}
            {support.phoneRaw && <a href={getCallSupportHref(support)} className="customer-account-support-link">
              <FiPhoneCall />
              <div>
                <strong>Call support</strong>
                <span>{support.phoneDisplay || support.phoneRaw}</span>
              </div>
            </a>}
          </div>
          <div className="customer-account-policy-list">
            <p><FiShield /> Cancellation requests are easiest to resolve before the event date and before payment disputes start.</p>
            <p><FiCreditCard /> Payment help is available for pending, failed, and refund-follow-up scenarios.</p>
            <p><FiClock /> Support window: {support.hours}</p>
          </div>
        </article>
      </section>

      <section className="customer-account-grid customer-account-grid-wide">
        <article className="customer-hub-panel card customer-account-card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Preferences</span>
              <h2>Tune the experience around your celebrations</h2>
            </div>
            <span className="customer-account-avatar"><FiSettings /></span>
          </div>
          <div className="customer-account-form-grid">
            <label className="customer-account-field">
              <span>Preferred experience</span>
              <input value={preferences.preferredExperience} onChange={(event) => updatePreference('preferredExperience', event.target.value)} placeholder="Birthday celebration" />
            </label>
            <label className="customer-account-field">
              <span>Preferred vibe</span>
              <input value={preferences.vibePreference} onChange={(event) => updatePreference('vibePreference', event.target.value)} placeholder="Warm and celebratory" />
            </label>
            <label className="customer-account-field">
              <span>Reminder lead time</span>
              <select value={preferences.reminderLeadDays} onChange={(event) => updatePreference('reminderLeadDays', event.target.value)}>
                <option value="7">7 days</option>
                <option value="14">14 days</option>
                <option value="30">30 days</option>
              </select>
            </label>
            <label className="customer-account-field">
              <span>Reminder channel</span>
              <select value={preferences.notificationChannel} onChange={(event) => updatePreference('notificationChannel', event.target.value)}>
                <option value="WHATSAPP">WhatsApp</option>
                <option value="EMAIL">Email</option>
                <option value="CALLBACK">Callback request</option>
              </select>
            </label>
          </div>

          <div className="customer-account-toggle-grid">
            <label className="customer-account-toggle">
              <input type="checkbox" checked={preferences.receivesOffers} onChange={(event) => updatePreference('receivesOffers', event.target.checked)} />
              <span><FiGift /> Send me referral and member offers</span>
            </label>
            <label className="customer-account-toggle">
              <input type="checkbox" checked={preferences.weekendAlerts} onChange={(event) => updatePreference('weekendAlerts', event.target.checked)} />
              <span><FiCalendar /> Tell me when strong weekend slots open</span>
            </label>
            <label className="customer-account-toggle">
              <input type="checkbox" checked={preferences.conciergeSupport} onChange={(event) => updatePreference('conciergeSupport', event.target.checked)} />
              <span><FiLifeBuoy /> Keep concierge-style support enabled</span>
            </label>
          </div>

          <div className="customer-hub-inline-actions">
            <button type="button" className="btn btn-primary btn-sm" onClick={savePreferences}>Save Preferences</button>
            <span className="customer-account-note">Saved to your account so reminders and preferences follow you across sessions.</span>
          </div>
        </article>

        <article className="customer-hub-panel card customer-account-card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Celebration reminders</span>
              <h2>Keep the important dates visible</h2>
            </div>
            <span className="customer-account-avatar"><FiBell /></span>
          </div>
          <div className="customer-account-form-grid">
            <label className="customer-account-field">
              <span>Birthday month</span>
              <select value={preferences.birthdayMonth} onChange={(event) => updatePreference('birthdayMonth', event.target.value)}>
                <option value="">Select month</option>
                {MONTH_OPTIONS.map((month) => <option key={month} value={month}>{month}</option>)}
              </select>
            </label>
            <label className="customer-account-field">
              <span>Anniversary month</span>
              <select value={preferences.anniversaryMonth} onChange={(event) => updatePreference('anniversaryMonth', event.target.value)}>
                <option value="">Select month</option>
                {MONTH_OPTIONS.map((month) => <option key={month} value={month}>{month}</option>)}
              </select>
            </label>
          </div>

          <div className="customer-account-reminder-box">
            <strong><FiCheckCircle /> Reminder plan</strong>
            <p>{reminderSummary}</p>
            <p>{preferences.birthdayMonth ? `Birthday offer watch: ${preferences.birthdayMonth}` : 'Add your birthday month to unlock occasion-first reminders.'}</p>
            <p>{preferences.anniversaryMonth ? `Anniversary reminder watch: ${preferences.anniversaryMonth}` : 'Use the anniversary field for recurring milestone planning.'}</p>
          </div>
        </article>
      </section>

      <section className="customer-hub-panel card">
        <div className="customer-hub-panel-head">
          <div>
            <span className="customer-hub-panel-label">Benefits and retention</span>
            <h2>Reasons to keep coming back</h2>
          </div>
          <span className="customer-hub-inline-link">Your current tier: {memberTier}</span>
        </div>
        <div className="customer-benefits-grid">
          {MEMBER_OFFERS.map((offer) => (
            <article key={offer.title} className="customer-benefit-card">
              <span className="customer-hub-panel-label">{offer.title}</span>
              <h3>{offer.title}</h3>
              <p>{offer.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="customer-account-trust-grid">
        <article className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">Frequently asked</span>
              <h2>Help before you need it</h2>
            </div>
          </div>
          <div className="customer-faq-list">
            {HELP_FAQS.map((item) => (
              <article key={item.question} className="customer-faq-item">
                <h3>{item.question}</h3>
                <p>{item.answer}</p>
              </article>
            ))}
          </div>
        </article>

        <article className="customer-hub-panel card">
          <div className="customer-hub-panel-head">
            <div>
              <span className="customer-hub-panel-label">How it works</span>
              <h2>Your experience in three steps</h2>
            </div>
          </div>
          <ol className="customer-steps-list">
            {EXPERIENCE_STEPS.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
          <div className="customer-hub-inline-actions">
            <Link to={hasVenueSelected ? '/book' : '/binges'} className="btn btn-primary btn-sm">{hasVenueSelected ? 'Plan the next one' : 'Choose a venue first'} <FiArrowRight /></Link>
          </div>
        </article>
      </section>
    </div>
  );
}

const MONTH_OPTIONS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];