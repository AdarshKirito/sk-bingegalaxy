import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useAuth } from '../context/AuthContext';
import { authService, bookingService, paymentService } from '../services/endpoints';
import { normalizeDashboardExperience } from '../services/dashboardExperience';
import useBingeStore from '../stores/bingeStore';
import {
  EXPERIENCE_STEPS,
  HELP_FAQS,
  mergeSupportContact,
  MEMBER_OFFERS,
} from '../services/customerExperience';
import { FiCalendar, FiClock, FiArrowLeft, FiArrowRight, FiCreditCard, FiMapPin, FiDollarSign, FiTag, FiGift, FiHeart, FiStar, FiFilm, FiBriefcase, FiSmile, FiRepeat, FiShield, FiUser, FiMessageCircle } from 'react-icons/fi';
import { SkeletonGrid } from '../components/ui/Skeleton';
import Pagination from '../components/ui/Pagination';
import SEO from '../components/SEO';
import './Dashboard.css';

export default function Dashboard() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { selectedBinge } = useBingeStore();
  const [currentBookings, setCurrentBookings] = useState([]);
  const [pastBookings, setPastBookings] = useState([]);
  const [payments, setPayments] = useState([]); // Removed unused state
  const [eventTypes, setEventTypes] = useState([]);
  const [dashboardExperience, setDashboardExperience] = useState(() => normalizeDashboardExperience(null));
  const [myPricing, setMyPricing] = useState(null);
  const [supportContact, setSupportContact] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loyaltyAccount, setLoyaltyAccount] = useState(null);
  const [page, setPage] = useState(1);
  const [experienceIndex, setExperienceIndex] = useState(0);
  const perPage = 4;

  // Handles both plain arrays and paginated payloads like { content: [...] }
  const toList = (payload) => {
    if (Array.isArray(payload)) return payload;
    if (Array.isArray(payload?.content)) return payload.content;
    if (Array.isArray(payload?.items)) return payload.items;
    return [];
  };

  useEffect(() => {
    const loads = [
      authService.getSupportContact().then(r => setSupportContact(r.data.data || null)).catch(() => null),
      bookingService.getCurrentBookings().then(r => setCurrentBookings(toList(r.data?.data ?? r.data))).catch(() => null),
      bookingService.getPastBookings().then(r => setPastBookings(toList(r.data?.data ?? r.data))).catch(() => null),
      paymentService.getMyPayments().then(r => setPayments(toList(r.data?.data ?? r.data))).catch(() => null),
      bookingService.getEventTypes().then(r => setEventTypes(toList(r.data?.data ?? r.data).filter(e => e.active !== false))).catch(() => null),
      selectedBinge?.id
        ? bookingService.getBingeDashboardExperience(selectedBinge.id)
          .then(r => setDashboardExperience(normalizeDashboardExperience(r.data.data || r.data || null)))
          .catch(() => null)
        : Promise.resolve(setDashboardExperience(normalizeDashboardExperience(null))),
      bookingService.getMyPricing().then(r => setMyPricing(r.data.data || null)).catch(() => null),
      bookingService.getMyLoyalty().then(r => setLoyaltyAccount(r.data.data || null)).catch(() => null),
    ];
    Promise.allSettled(loads).then(results => {
      const failed = results.filter(r => r.status === 'rejected' || r.value === null).length;
      if (failed > 0) toast.error('Some dashboard data could not be loaded.');
    }).finally(() => setLoading(false));
  }, [selectedBinge?.id]);

  const sortedCurrentBookings = [...currentBookings].sort((left, right) => {
    const leftTime = new Date(`${left.bookingDate}T${left.startTime || '00:00'}`).getTime();
    const rightTime = new Date(`${right.bookingDate}T${right.startTime || '00:00'}`).getTime();
    return leftTime - rightTime;
  });
  const totalPages = Math.ceil(sortedCurrentBookings.length / perPage);
  const paged = sortedCurrentBookings.slice((page - 1) * perPage, page * perPage);

  const upcomingCount = currentBookings.length;
  const pastCount = pastBookings.length;
  const totalSpend = pastBookings
    .filter(b => b.paymentStatus === 'SUCCESS')
    .reduce((sum, b) => sum + (b.totalAmount || 0), 0);
  const support = mergeSupportContact(supportContact, selectedBinge);
  const loyaltyTier = loyaltyAccount?.tierLevel || 'BRONZE';
  const pricingLabel = myPricing?.pricingSource === 'CUSTOMER'
    ? (myPricing.memberLabel || 'Custom')
    : myPricing?.pricingSource === 'RATE_CODE'
      ? (myPricing.memberLabel || myPricing.rateCodeName || 'Rate Code')
      : loyaltyTier;
  const pricingSourceLabel = myPricing?.pricingSource === 'CUSTOMER'
    ? 'Personalized pricing has been configured for your account.'
    : myPricing?.pricingSource === 'RATE_CODE'
      ? `${myPricing.memberLabel || myPricing.rateCodeName || 'Rate code'} pricing is active on your account.`
      : `You are on ${loyaltyTier} tier pricing.`;

  const pendingPayments = payments.filter(p => p.status === 'INITIATED');
  const lastPayment = payments.length > 0 ? payments[0] : null;
  const pendingBookings = currentBookings.filter(b => b.status === 'PENDING' && b.paymentStatus !== 'SUCCESS');
  const nextBooking = sortedCurrentBookings[0] || null;
  const heroBooking = pendingBookings[0] || nextBooking || null;

  const expIcons = {
    'Birthday': <FiGift />, 'Anniversary': <FiHeart />, 'Proposal': <FiStar />,
    'Screening': <FiFilm />, 'HD': <FiFilm />, 'Corporate': <FiBriefcase />,
    'Baby': <FiSmile />, 'default': <FiStar />,
  };
  const getExpIcon = (name) => {
    const key = Object.keys(expIcons).find(k => name?.toLowerCase().includes(k.toLowerCase()));
    return key ? expIcons[key] : expIcons.default;
  };
  const getExperienceTone = (name = '') => {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('birthday')) return { tag: 'Celebrations', blurb: 'Cake, music, memories, and a room that is fully yours.', theme: 'celebration' };
    if (lowerName.includes('anniversary') || lowerName.includes('proposal')) return { tag: 'Romance', blurb: 'Build a quieter, more personal setup for milestone moments.', theme: 'romance' };
    if (lowerName.includes('corporate')) return { tag: 'Teams', blurb: 'Present, celebrate wins, or host a private watch party with your crew.', theme: 'team' };
    if (lowerName.includes('baby')) return { tag: 'Family', blurb: 'A softer, family-first setup for shared moments and photos.', theme: 'family' };
    if (lowerName.includes('hd') || lowerName.includes('screen')) return { tag: 'Movie Night', blurb: 'Keep it simple and cinematic with a focused private-screening setup.', theme: 'cinema' };
    return { tag: 'Private Event', blurb: 'Shape the room around your plan instead of fitting into a public showtime.', theme: 'luxury' };
  };
  const formatAmount = (amount) => `Rs ${Number(amount || 0).toLocaleString()}`;
  const formatDuration = (booking) => {
    const totalMinutes = booking?.durationMinutes || ((booking?.durationHours || 0) * 60);
    if (!totalMinutes) return 'Flexible duration';
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    if (hours > 0 && minutes > 0) return `${hours}h ${minutes}m`;
    if (hours > 0) return `${hours}h`;
    return `${minutes}m`;
  };

  const getThemeIcon = (theme = 'celebration') => {
    switch (theme) {
      case 'romance':
        return <FiHeart />;
      case 'cinema':
        return <FiFilm />;
      case 'team':
        return <FiBriefcase />;
      case 'family':
        return <FiSmile />;
      case 'luxury':
        return <FiStar />;
      case 'celebration':
      default:
        return <FiGift />;
    }
  };

  const getThemeLabel = (theme = 'celebration') => {
    switch (theme) {
      case 'romance':
        return 'Romance';
      case 'cinema':
        return 'Cinema';
      case 'team':
        return 'Team';
      case 'family':
        return 'Family';
      case 'luxury':
        return 'Luxury';
      case 'celebration':
      default:
        return 'Celebration';
    }
  };

  const customExperienceItems = useMemo(() => (
    dashboardExperience.slides.map((slide, index) => {
      const linkedEvent = slide.linkedEventTypeId
        ? eventTypes.find((et) => et.id === slide.linkedEventTypeId)
        : null;
      return {
        key: `custom-${index}`,
        type: 'custom',
        badge: slide.badge || getThemeLabel(slide.theme),
        title: slide.headline || 'Custom setup',
        description: slide.description || 'Guide customers toward the atmosphere you want highlighted first.',
        ctaLabel: slide.ctaLabel || 'Open Booking',
        theme: slide.theme || 'celebration',
        imageUrl: slide.imageUrl || '',
        icon: getThemeIcon(slide.theme),
        metaValue: selectedBinge?.name || 'Featured setup',
        linkState: linkedEvent ? { eventTypeId: linkedEvent.id, eventTypeName: linkedEvent.name } : undefined,
        price: linkedEvent ? formatAmount(linkedEvent.basePrice) : undefined,
      };
    })
  ), [dashboardExperience.slides, selectedBinge?.name, eventTypes]);

  const defaultExperienceItems = useMemo(() => (
    eventTypes.slice(0, 6).map((evt) => {
      const tone = getExperienceTone(evt.name);
      return {
        key: `event-${evt.id}`,
        type: 'event',
        badge: tone.tag,
        title: evt.name,
        description: evt.description || tone.blurb,
        ctaLabel: 'Build This',
        theme: tone.theme,
        icon: getExpIcon(evt.name),
        price: formatAmount(evt.basePrice),
        linkState: { eventTypeId: evt.id, eventTypeName: evt.name },
      };
    })
  ), [eventTypes]);

  const experienceItems = customExperienceItems.length > 0 ? customExperienceItems : defaultExperienceItems;
  const useExperienceCarousel = dashboardExperience.layout === 'CAROUSEL';

  useEffect(() => {
    setExperienceIndex(0);
  }, [experienceItems.length, dashboardExperience.layout, dashboardExperience.sectionTitle]);

  /* Auto-rotate carousel every 5 seconds */
  useEffect(() => {
    if (!useExperienceCarousel || experienceItems.length <= 1 || loading) return;
    const timer = setInterval(() => {
      setExperienceIndex((current) => (current + 1) % experienceItems.length);
    }, 5000);
    return () => clearInterval(timer);
  }, [useExperienceCarousel, experienceItems.length, loading]);

  const handlePreviousExperience = () => {
    if (experienceItems.length <= 1) return;
    setExperienceIndex((current) => (current === 0 ? experienceItems.length - 1 : current - 1));
  };

  const handleNextExperience = () => {
    if (experienceItems.length <= 1) return;
    setExperienceIndex((current) => (current + 1) % experienceItems.length);
  };

  return (
    <div className="container dashboard">
      <SEO title="Dashboard" />
      <section className="dash-hero">
        <div className="dash-hero-copy">
          <span className="dash-eyebrow">Customer Dashboard</span>
          <h1>Hello, {user?.firstName}! Plan the next private screening without the clutter.</h1>
          <p>
            {selectedBinge
              ? `You are currently booking at ${selectedBinge.name}. Track reservations, finish pending payments, and jump straight into the next celebration from one place.`
              : 'Track reservations, finish pending payments, and jump straight into the next celebration from one place.'}
          </p>
          <div className="dash-hero-actions">
            <Link to="/book" className="btn btn-primary">Start a New Booking</Link>
            <Link to="/my-bookings" className="btn btn-secondary">Review My Bookings</Link>
            <Link to="/account" className="btn btn-secondary">Open Account</Link>
          </div>
        </div>

        <aside className="dash-highlight-card">
          <div className="dash-highlight-meta">
            <span className="dash-highlight-label">
              {loading
                ? 'Loading'
                : pendingBookings.length > 0
                  ? 'Next Action'
                  : nextBooking
                    ? 'Coming Up'
                    : 'Start Here'}
            </span>
            {!loading && heroBooking && (
              <span className={`badge ${pendingBookings.length > 0 ? 'badge-warning' : 'badge-success'}`}>
                {pendingBookings.length > 0 ? 'Payment Needed' : 'Scheduled'}
              </span>
            )}
          </div>

          {loading ? (
            <div className="dash-highlight-body">
              <h2>Preparing your dashboard...</h2>
              <p>Fetching bookings, payments, and experience options.</p>
            </div>
          ) : heroBooking ? (
            <div className="dash-highlight-body">
              <h2>{heroBooking.eventType?.name ?? heroBooking.eventType}</h2>
              <p>{heroBooking.bookingDate} at {heroBooking.startTime} for {formatDuration(heroBooking)}</p>
              <div className="dash-highlight-amount">{formatAmount(heroBooking.totalAmount)}</div>
              <div className="dash-inline-actions">
                {pendingBookings.length > 0 ? (
                  <button className="btn btn-primary btn-sm" onClick={() => navigate(`/payment/${heroBooking.bookingRef}`)}>
                    Finish Payment <FiArrowRight />
                  </button>
                ) : (
                  <Link to={`/booking/${heroBooking.bookingRef}`} className="btn btn-primary btn-sm">
                    View Reservation <FiArrowRight />
                  </Link>
                )}
                <Link to="/my-bookings" className="btn btn-secondary btn-sm">Open Timeline</Link>
              </div>
            </div>
          ) : (
            <div className="dash-highlight-body">
              <h2>No active reservation yet</h2>
              <p>Pick an experience, choose a slot, and turn this page into your planning hub.</p>
              <div className="dash-inline-actions">
                <Link to="/book" className="btn btn-primary btn-sm">
                  Book Your First Slot <FiArrowRight />
                </Link>
              </div>
            </div>
          )}

          {!loading && (
            <div className="dash-highlight-footer">
              <span>{upcomingCount} upcoming booking{upcomingCount === 1 ? '' : 's'}</span>
              <span>{pendingPayments.length} in-progress payment{pendingPayments.length === 1 ? '' : 's'}</span>
            </div>
          )}
        </aside>
      </section>

      <section className="dash-summary-grid">
        <article className="dash-summary-card">
          <div className="dash-summary-icon"><FiCalendar /></div>
          <span className="dash-summary-label">Upcoming</span>
          <strong className="dash-summary-value">{loading ? '–' : upcomingCount}</strong>
          <p>{loading ? 'Loading reservations' : upcomingCount > 0 ? 'Reservations waiting on your calendar.' : 'No reservation locked in yet.'}</p>
        </article>

        <article className="dash-summary-card">
          <div className="dash-summary-icon"><FiClock /></div>
          <span className="dash-summary-label">Past Visits</span>
          <strong className="dash-summary-value">{loading ? '–' : pastCount}</strong>
          <p>{loading ? 'Loading visit history' : pastCount > 0 ? 'A quick view of how often you have booked with us.' : 'Your history starts with the next experience.'}</p>
        </article>

        <article className="dash-summary-card">
          <div className="dash-summary-icon"><FiDollarSign /></div>
          <span className="dash-summary-label">Total Spend</span>
          <strong className="dash-summary-value">{loading ? '–' : formatAmount(totalSpend)}</strong>
          <p>{loading ? 'Calculating spend' : totalSpend > 0 ? 'Based on successfully paid completed bookings.' : 'No completed paid bookings recorded yet.'}</p>
        </article>

        <article className="dash-summary-card dash-summary-card-accent">
          <div className="dash-summary-icon"><FiTag /></div>
          <span className="dash-summary-label">Your Pricing</span>
          <strong className="dash-summary-value">{loading ? '–' : pricingLabel}</strong>
          <p>{loading ? 'Loading pricing profile' : pricingSourceLabel}</p>
        </article>
      </section>

      <section className="grid-3 dash-actions">
        <Link to="/book" className="dash-action-card">
          <div className="dash-icon"><FiCalendar /></div>
          <span className="dash-card-kicker">Fastest path</span>
          <h3>Book Now</h3>
          <p>Jump straight into the booking flow and lock your preferred slot.</p>
          <FiArrowRight className="dash-arrow" />
        </Link>

        <Link to="/my-bookings" className="dash-action-card">
          <div className="dash-icon"><FiClock /></div>
          <span className="dash-card-kicker">Timeline</span>
          <h3>My Bookings</h3>
          <p>See upcoming plans, past visits, booking downloads, and support shortcuts in one place.</p>
          <FiArrowRight className="dash-arrow" />
        </Link>

        <div className="dash-action-card dash-payments-card">
          <div className="dash-icon dash-icon-success">
            <FiCreditCard />
          </div>
          <span className="dash-card-kicker">Payments</span>
          <h3>Payments</h3>
          {loading ? (
            <p className="dash-card-muted">Loading payment activity...</p>
          ) : pendingBookings.length > 0 ? (
            <>
              <p className="dash-payments-pending">{pendingBookings.length} booking{pendingBookings.length > 1 ? 's' : ''} still needs payment.</p>
              <p className="dash-card-muted">Finish the current transaction without reopening the booking flow.</p>
              <div className="dash-inline-actions">
                <Link to="/payments" className="btn btn-primary btn-sm dash-pay-btn">Open Payments Hub</Link>
                <button className="btn btn-secondary btn-sm dash-pay-btn" onClick={() => navigate(`/payment/${pendingBookings[0].bookingRef}`)}>
                  Continue Payment <FiArrowRight />
                </button>
              </div>
            </>
          ) : lastPayment ? (
            <>
              <p className="dash-card-muted">Latest payment snapshot</p>
              <p className="dash-payment-status-line">
                <span className={`badge ${lastPayment.status === 'SUCCESS' ? 'badge-success' : lastPayment.status === 'INITIATED' ? 'badge-warning' : 'badge-danger'}`}>
                {lastPayment.status}
                </span>
                <strong>{formatAmount(lastPayment.amount)}</strong>
              </p>
              <Link to="/payments" className="btn btn-secondary btn-sm dash-pay-btn">Review Payment History</Link>
            </>
          ) : (
            <>
              <p className="dash-card-muted">No payments recorded yet.</p>
              <Link to="/payments" className="btn btn-secondary btn-sm dash-pay-btn">Open Payments Hub</Link>
            </>
          )}
        </div>
      </section>

      <section className="dash-insights-grid">
        {selectedBinge && (
          <article className="dash-venue-card card">
            <div className="dash-venue-head">
              <div className="dash-venue-left">
                <div className="dash-venue-icon"><FiMapPin /></div>
                <div>
                  <span className="dash-card-kicker">Selected Venue</span>
                  <h3>{selectedBinge.name}</h3>
                  {selectedBinge.address && <p className="dash-venue-address">{selectedBinge.address}</p>}
                </div>
              </div>
              <button className="btn btn-secondary btn-sm dash-venue-change" onClick={() => navigate('/binges')}>
                <FiRepeat /> Change Venue
              </button>
            </div>

            <p className="dash-venue-copy">
              Keep this venue as your base if you want the fastest path through booking, pricing, and confirmations.
            </p>

            <div className="dash-venue-tags">
              <span className="badge badge-info">Private screening</span>
              <span className="badge badge-success">Celebration ready</span>
              <span className="badge badge-info">Flexible add-ons</span>
            </div>
          </article>
        )}

        <article className="dash-member-card card">
          <span className="dash-card-kicker">Pricing Snapshot</span>
          <h3>{loading ? 'Checking your plan...' : `${loyaltyTier} tier`}</h3>
          <p>{loading ? 'We are resolving your current customer pricing.' : pricingLabel === loyaltyTier ? `Your pricing matches your ${loyaltyTier} tier.` : `${pricingLabel} pricing is paired with your ${loyaltyTier} loyalty status.`}</p>
          <div className="dash-member-metrics">
            <div>
              <span>Completed visits</span>
              <strong>{loading ? '–' : pastCount}</strong>
            </div>
            <div>
              <span>Paid bookings</span>
              <strong>{loading ? '–' : pastBookings.filter(b => b.paymentStatus === 'SUCCESS').length}</strong>
            </div>
          </div>
        </article>
      </section>

      <section className="dash-retention-grid">
        <article className="dash-loyalty-card card">
          <div className="dash-section-header">
            <div>
              <span className="dash-section-kicker">Loyalty</span>
              <h2>{loyaltyAccount ? 'Your Rewards' : 'Reasons to keep booking through your account'}</h2>
            </div>
            <Link to="/my-bookings" className="dash-inline-link">Book now</Link>
          </div>
          {loyaltyAccount ? (
            <div className="dash-loyalty-summary">
              <div className="dash-loyalty-stat">
                <span className="dash-loyalty-stat-value">{loyaltyAccount.currentBalance?.toLocaleString() || 0}</span>
                <span className="dash-loyalty-stat-label">Points Balance</span>
              </div>
              <div className="dash-loyalty-stat">
                <span className="dash-loyalty-stat-value">{loyaltyAccount.tierLevel || 'BRONZE'}</span>
                <span className="dash-loyalty-stat-label">Current Tier</span>
              </div>
              <div className="dash-loyalty-stat">
                <span className="dash-loyalty-stat-value">{loyaltyAccount.totalPointsEarned?.toLocaleString() || 0}</span>
                <span className="dash-loyalty-stat-label">Lifetime Points</span>
              </div>
            </div>
          ) : (
            <div className="dash-loyalty-offers">
              {MEMBER_OFFERS.map((offer) => (
                <article key={offer.title} className="dash-loyalty-offer">
                  <span className="dash-card-kicker">{offer.title}</span>
                  <h3>{offer.title}</h3>
                  <p>{offer.description}</p>
                </article>
              ))}
            </div>
          )}
        </article>

        <article className="dash-help-card card">
          <div className="dash-section-header">
            <div>
              <span className="dash-section-kicker">Help and trust</span>
              <h2>Support is visible before anything goes wrong</h2>
            </div>
          </div>
          <div className="dash-help-points">
            <p><FiShield /> Payment, cancellation, and schedule questions are easiest to resolve before the booking date.</p>
            <p><FiMessageCircle /> WhatsApp support is the fastest route for booking changes during {support.hours}.</p>
            <p><FiUser /> Account preferences and celebration reminders now live in one dedicated account area.</p>
          </div>
          <div className="dash-inline-actions">
            <Link to="/account" className="btn btn-primary btn-sm">Manage Account</Link>
            <Link to="/my-bookings" className="btn btn-secondary btn-sm">Open Booking Control</Link>
          </div>
        </article>
      </section>

      {!loading && experienceItems.length > 0 && (
        <section className="dash-experiences">
          <div className="dash-section-header">
            <div className="dash-section-copy">
              <span className="dash-section-kicker">{dashboardExperience.sectionEyebrow}</span>
              <h2>{dashboardExperience.sectionTitle}</h2>
              {dashboardExperience.sectionSubtitle && (
                <p className="dash-section-subtitle">{dashboardExperience.sectionSubtitle}</p>
              )}
            </div>
            <div className="dash-exp-header-actions">
              {useExperienceCarousel && experienceItems.length > 1 && (
                <div className="dash-exp-nav" aria-label="Experience carousel controls">
                  <button type="button" onClick={handlePreviousExperience} aria-label="Previous experience">
                    <FiArrowLeft />
                  </button>
                  <span>{experienceIndex + 1} / {experienceItems.length}</span>
                  <button type="button" onClick={handleNextExperience} aria-label="Next experience">
                    <FiArrowRight />
                  </button>
                </div>
              )}
              <Link to="/book" className="dash-inline-link">Open booking flow</Link>
            </div>
          </div>

          {useExperienceCarousel ? (
            <>
              <div className="dash-exp-carousel-window">
                <div
                  className="dash-exp-carousel-track"
                  style={{ transform: `translateX(-${experienceIndex * 100}%)` }}
                >
                  {experienceItems.map((item) => (
                    <article key={item.key} className={`dash-exp-slide${item.imageUrl ? ' dash-exp-slide-has-image' : ''}`} data-theme={item.theme}>
                      {item.imageUrl && (
                        <div className="dash-exp-slide-bg" style={{ backgroundImage: `url(${item.imageUrl})` }} />
                      )}
                      <div className="dash-exp-slide-copy">
                        <span className="dash-exp-pill">{item.badge}</span>
                        <h3>{item.title}</h3>
                        <p className="dash-exp-desc">{item.description}</p>
                        {item.price ? (
                          <div className="dash-exp-slide-price">
                            <span className="dash-exp-price-label">Starts from</span>
                            <strong className="dash-exp-price">{item.price}</strong>
                          </div>
                        ) : (
                          <div className="dash-exp-slide-note">Curated for the {item.metaValue} dashboard.</div>
                        )}
                        <Link
                          to="/book"
                          state={item.linkState}
                          className="btn btn-primary btn-sm dash-exp-btn"
                        >
                          {item.ctaLabel} <FiArrowRight />
                        </Link>
                      </div>

                      <div className="dash-exp-slide-art">
                        {item.imageUrl ? (
                          <img src={item.imageUrl} alt={item.title} className="dash-exp-slide-img" />
                        ) : (
                          <>
                            <div className="dash-exp-slide-icon">{item.icon}</div>
                            <span>{getThemeLabel(item.theme)}</span>
                          </>
                        )}
                      </div>
                    </article>
                  ))}
                </div>
              </div>

              {experienceItems.length > 1 && (
                <div className="dash-exp-dots" role="tablist" aria-label="Experience slides">
                  {experienceItems.map((item, index) => (
                    <button
                      key={`${item.key}-dot`}
                      type="button"
                      className={`dash-exp-dot${index === experienceIndex ? ' active' : ''}`}
                      onClick={() => setExperienceIndex(index)}
                      aria-label={`Show experience ${index + 1}`}
                      aria-pressed={index === experienceIndex}
                    />
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="dash-exp-grid">
              {experienceItems.map((item) => (
                <article key={item.key} className={`dash-exp-card card${item.imageUrl ? ' dash-exp-card-has-image' : ''}`} data-theme={item.theme}>
                  {item.imageUrl && (
                    <div className="dash-exp-card-image">
                      <img src={item.imageUrl} alt={item.title} />
                    </div>
                  )}
                  <div className="dash-exp-topline">
                    <span className="dash-exp-pill">{item.badge}</span>
                    {!item.imageUrl && <div className="dash-exp-icon">{item.icon}</div>}
                  </div>
                  <h3>{item.title}</h3>
                  <p className="dash-exp-desc">{item.description}</p>
                  <div className="dash-exp-footer">
                    {item.price ? (
                      <div>
                        <span className="dash-exp-price-label">Starts from</span>
                        <strong className="dash-exp-price">{item.price}</strong>
                      </div>
                    ) : (
                      <span className="dash-exp-note">{getThemeLabel(item.theme)} spotlight</span>
                    )}
                    <Link
                      to="/book"
                      state={item.linkState}
                      className="btn btn-primary btn-sm dash-exp-btn"
                    >
                      {item.ctaLabel} <FiArrowRight />
                    </Link>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      )}

      <section className="dash-upcoming">
        <div className="dash-section-header">
          <div>
            <span className="dash-section-kicker">Upcoming</span>
            <h2>Your reservation timeline</h2>
          </div>
          <Link to="/my-bookings" className="dash-inline-link">See all bookings</Link>
        </div>

        {loading ? (
          <SkeletonGrid count={4} columns={2} />
        ) : currentBookings.length === 0 ? (
          <div className="card dash-empty-state">
            <p>No upcoming bookings</p>
            <span>Your next reservation will appear here with payment and timing details.</span>
            <Link to="/book" className="btn btn-primary btn-sm">Make a Booking</Link>
          </div>
        ) : (
          <>
            <div className="grid-2">
              {paged.map(b => (
                <Link to={`/booking/${b.bookingRef}`} key={b.bookingRef} className="card booking-preview-card">
                  <div className="bpc-header">
                    <span className="badge badge-info">{b.status}</span>
                    <span className="bpc-ref">{b.bookingRef}</span>
                  </div>
                  <h4>{b.eventType?.name ?? b.eventType}</h4>
                  <p>{b.bookingDate} at {b.startTime} • {formatDuration(b)}</p>
                  <div className="bpc-footer">
                    <span className="bpc-amount">{formatAmount(b.totalAmount)}</span>
                    {b.status === 'PENDING' && b.paymentStatus !== 'SUCCESS' && (
                      <span className="badge badge-warning">Unpaid</span>
                    )}
                  </div>
                </Link>
              ))}
            </div>
            <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
          </>
        )}
      </section>

      <section className="dash-trust-grid">
        <article className="dash-help-card card">
          <div className="dash-section-header">
            <div>
              <span className="dash-section-kicker">Frequently asked</span>
              <h2>Common answers without extra back-and-forth</h2>
            </div>
          </div>
          <div className="dash-faq-list">
            {HELP_FAQS.slice(0, 3).map((item) => (
              <article key={item.question} className="dash-faq-item">
                <h3>{item.question}</h3>
                <p>{item.answer}</p>
              </article>
            ))}
          </div>
        </article>

        <article className="dash-help-card card">
          <div className="dash-section-header">
            <div>
              <span className="dash-section-kicker">How it works</span>
              <h2>A simple path from idea to private screening</h2>
            </div>
          </div>
          <ol className="dash-steps-list">
            {EXPERIENCE_STEPS.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
        </article>
      </section>
    </div>
  );
}
