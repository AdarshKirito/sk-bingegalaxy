import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useAuth } from '../context/AuthContext';
import { authService, bookingService } from '../services/endpoints';
import { normalizeDashboardExperience } from '../services/dashboardExperience';
import useBingeStore from '../stores/bingeStore';
import { getMemberTier, mergeSupportContact } from '../services/customerExperience';
import {
  FiArrowLeft,
  FiArrowRight,
  FiBriefcase,
  FiCalendar,
  FiClock,
  FiCreditCard,
  FiFilm,
  FiGift,
  FiHeart,
  FiMapPin,
  FiSmile,
  FiStar,
} from 'react-icons/fi';
import { SkeletonGrid } from '../components/ui/Skeleton';
import Pagination from '../components/ui/Pagination';
import SEO from '../components/SEO';
import './Dashboard.css';

const formatStatusLabel = (value, fallback = 'Pending') => {
  if (!value) return fallback;
  return String(value)
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (character) => character.toUpperCase());
};

const bookingStatusBadge = (status) => ({
  PENDING: 'badge-warning',
  CONFIRMED: 'badge-info',
  CHECKED_IN: 'badge-info',
  COMPLETED: 'badge-success',
  CANCELLED: 'badge-danger',
  NO_SHOW: 'badge-danger',
}[status] || 'badge-info');

const paymentStatusBadge = (status) => ({
  PENDING: 'badge-warning',
  INITIATED: 'badge-warning',
  PARTIALLY_PAID: 'badge-warning',
  SUCCESS: 'badge-success',
  FAILED: 'badge-danger',
  REFUNDED: 'badge-info',
  PARTIALLY_REFUNDED: 'badge-info',
}[status] || 'badge-info');

export default function Dashboard() {
  const { user } = useAuth();
  const { selectedBinge } = useBingeStore();
  const [currentBookings, setCurrentBookings] = useState([]);
  const [pastBookings, setPastBookings] = useState([]);
  const [eventTypes, setEventTypes] = useState([]);
  const [dashboardExperience, setDashboardExperience] = useState(() => normalizeDashboardExperience(null));
  const [myPricing, setMyPricing] = useState(null);
  const [supportContact, setSupportContact] = useState(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [experienceIndex, setExperienceIndex] = useState(0);
  const perPage = 4;

  useEffect(() => {
    let isActive = true;

    const setIfActive = (setter, value) => {
      if (isActive) setter(value);
    };

    setLoading(true);

    const loads = [
      authService.getSupportContact()
        .then((response) => {
          setIfActive(setSupportContact, response.data.data || null);
          return true;
        })
        .catch(() => null),
      bookingService.getCurrentBookings()
        .then((response) => {
          setIfActive(setCurrentBookings, response.data.data || []);
          return true;
        })
        .catch(() => null),
      bookingService.getPastBookings()
        .then((response) => {
          setIfActive(setPastBookings, response.data.data || []);
          return true;
        })
        .catch(() => null),
      bookingService.getEventTypes()
        .then((response) => {
          const items = (response.data.data || response.data || []).filter((item) => item.active !== false);
          setIfActive(setEventTypes, items);
          return true;
        })
        .catch(() => null),
      selectedBinge?.id
        ? bookingService.getBingeDashboardExperience(selectedBinge.id)
          .then((response) => {
            setIfActive(setDashboardExperience, normalizeDashboardExperience(response.data.data || response.data || null));
            return true;
          })
          .catch(() => null)
        : Promise.resolve().then(() => {
          setIfActive(setDashboardExperience, normalizeDashboardExperience(null));
          return true;
        }),
      bookingService.getMyPricing()
        .then((response) => {
          setIfActive(setMyPricing, response.data.data || null);
          return true;
        })
        .catch(() => null),
    ];

    Promise.allSettled(loads)
      .then((results) => {
        if (!isActive) return;
        const failed = results.filter((result) => result.status === 'rejected' || result.value === null).length;
        if (failed > 0) toast.error('Some dashboard data could not be loaded.');
      })
      .finally(() => {
        if (isActive) setLoading(false);
      });

    return () => {
      isActive = false;
    };
  }, [selectedBinge?.id]);

  const sortedCurrentBookings = useMemo(() => (
    [...currentBookings].sort((left, right) => {
      const leftTime = new Date(`${left.bookingDate}T${left.startTime || '00:00'}`).getTime();
      const rightTime = new Date(`${right.bookingDate}T${right.startTime || '00:00'}`).getTime();
      return leftTime - rightTime;
    })
  ), [currentBookings]);

  const totalPages = Math.ceil(sortedCurrentBookings.length / perPage);
  const pagedBookings = sortedCurrentBookings.slice((page - 1) * perPage, page * perPage);
  const upcomingCount = currentBookings.length;
  const pastCount = pastBookings.length;
  const totalSpend = pastBookings
    .filter((booking) => booking.paymentStatus === 'SUCCESS')
    .reduce((sum, booking) => sum + (booking.totalAmount || 0), 0);
  const support = mergeSupportContact(supportContact);
  const memberTier = useMemo(() => getMemberTier(pastCount, totalSpend), [pastCount, totalSpend]);

  const pricingLabel = myPricing?.pricingSource === 'CUSTOMER'
    ? (myPricing.memberLabel || 'Custom')
    : myPricing?.pricingSource === 'RATE_CODE'
      ? (myPricing.memberLabel || myPricing.rateCodeName || 'Rate Code')
      : memberTier;

  const pricingSourceLabel = myPricing?.pricingSource === 'CUSTOMER'
    ? 'Personalized pricing has been configured for your account.'
    : myPricing?.pricingSource === 'RATE_CODE'
      ? `${myPricing.memberLabel || myPricing.rateCodeName || 'Rate code'} pricing is active on your account.`
      : `You are on ${memberTier.toLowerCase()} member pricing.`;

  const pendingBooking = sortedCurrentBookings.find((booking) => booking.status === 'PENDING' && booking.paymentStatus !== 'SUCCESS') || null;
  const nextBooking = sortedCurrentBookings[0] || null;
  const focusBooking = pendingBooking || nextBooking;

  useEffect(() => {
    if (totalPages === 0 && page !== 1) {
      setPage(1);
      return;
    }

    if (totalPages > 0 && page > totalPages) {
      setPage(totalPages);
    }
  }, [page, totalPages]);

  const getExperienceTone = (name = '') => {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('birthday')) return { tag: 'Celebration', blurb: 'A flexible setup for parties and milestone moments.', theme: 'celebration' };
    if (lowerName.includes('anniversary') || lowerName.includes('proposal')) return { tag: 'Romance', blurb: 'A quieter setup for intimate private screenings.', theme: 'romance' };
    if (lowerName.includes('corporate')) return { tag: 'Team', blurb: 'Built for presentations, launches, and group sessions.', theme: 'team' };
    if (lowerName.includes('baby')) return { tag: 'Family', blurb: 'A softer setup for family-first celebrations.', theme: 'family' };
    if (lowerName.includes('hd') || lowerName.includes('screen')) return { tag: 'Cinema', blurb: 'A simple private-screening option with a cinematic focus.', theme: 'cinema' };
    return { tag: 'Private Event', blurb: 'A straightforward setup for your next private event.', theme: 'luxury' };
  };

  const getExperienceIcon = (name = '') => {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('birthday')) return <FiGift />;
    if (lowerName.includes('anniversary') || lowerName.includes('proposal')) return <FiHeart />;
    if (lowerName.includes('corporate')) return <FiBriefcase />;
    if (lowerName.includes('baby')) return <FiSmile />;
    if (lowerName.includes('hd') || lowerName.includes('screen')) return <FiFilm />;
    return <FiStar />;
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

  const customExperienceItems = useMemo(() => (
    dashboardExperience.slides.map((slide, index) => ({
      key: `custom-${index}`,
      badge: slide.badge || getThemeLabel(slide.theme),
      title: slide.headline || 'Featured setup',
      description: slide.description || 'A featured private-screening setup for your next booking.',
      ctaLabel: slide.ctaLabel || 'Open booking',
      theme: slide.theme || 'celebration',
      imageUrl: slide.imageUrl || '',
      icon: getThemeIcon(slide.theme),
      linkState: undefined,
    }))
  ), [dashboardExperience.slides]);

  const defaultExperienceItems = useMemo(() => (
    eventTypes.slice(0, 6).map((eventType) => {
      const tone = getExperienceTone(eventType.name);
      return {
        key: `event-${eventType.id}`,
        badge: tone.tag,
        title: eventType.name,
        description: eventType.description || tone.blurb,
        ctaLabel: 'Book this',
        theme: tone.theme,
        imageUrl: '',
        icon: getExperienceIcon(eventType.name),
        price: formatAmount(eventType.basePrice),
        linkState: { eventTypeId: eventType.id, eventTypeName: eventType.name },
      };
    })
  ), [eventTypes]);

  const experienceItems = customExperienceItems.length > 0 ? customExperienceItems : defaultExperienceItems;
  const useExperienceCarousel = dashboardExperience.layout === 'CAROUSEL';
  const activeExperience = experienceItems[experienceIndex] || null;

  useEffect(() => {
    setExperienceIndex(0);
  }, [experienceItems.length, dashboardExperience.layout, dashboardExperience.sectionTitle]);

  const handlePreviousExperience = () => {
    if (experienceItems.length <= 1) return;
    setExperienceIndex((current) => (current === 0 ? experienceItems.length - 1 : current - 1));
  };

  const handleNextExperience = () => {
    if (experienceItems.length <= 1) return;
    setExperienceIndex((current) => (current + 1) % experienceItems.length);
  };

  const supportLinks = [
    support.email ? { label: 'Email support', href: `mailto:${support.email}` } : null,
    support.whatsappRaw
      ? { label: 'WhatsApp', href: `https://wa.me/${String(support.whatsappRaw).replace(/\D/g, '')}`, external: true }
      : null,
    support.phoneRaw ? { label: 'Call support', href: `tel:${support.phoneRaw}` } : null,
  ].filter(Boolean);

  const renderExperienceCard = (item, featured = false) => (
    <article
      key={item.key}
      className={`card dash-experience-card${featured ? ' dash-experience-card-featured' : ''}${item.imageUrl ? ' dash-experience-card-has-image' : ''}`}
    >
      {item.imageUrl && (
        <div className="dash-experience-image">
          <img src={item.imageUrl} alt={item.title} />
        </div>
      )}

      <div className="dash-experience-body">
        <div className="dash-experience-top">
          <span className="dash-pill">{item.badge}</span>
          {!item.imageUrl && <span className="dash-experience-icon" aria-hidden="true">{item.icon}</span>}
        </div>

        <h3>{item.title}</h3>
        <p>{item.description}</p>

        <div className="dash-experience-footer">
          <strong>{item.price || getThemeLabel(item.theme)}</strong>
          <Link to="/book" state={item.linkState} className="btn btn-secondary btn-sm">
            {item.ctaLabel} <FiArrowRight />
          </Link>
        </div>
      </div>
    </article>
  );

  return (
    <div className="container dashboard">
      <SEO title="Dashboard" />

      <section className="dash-header">
        <div>
          <p className="dash-overline">Customer dashboard</p>
          <h1>Hello, {user?.firstName || 'there'}</h1>
          <p className="dash-intro">
            {selectedBinge
              ? `Manage your bookings, payments, and pricing for ${selectedBinge.name} from one place.`
              : 'Manage your bookings, payments, and pricing from one place.'}
          </p>
        </div>

        <div className="dash-header-actions">
          <Link to="/book" className="btn btn-primary">Start booking</Link>
          <Link to="/my-bookings" className="btn btn-secondary">My bookings</Link>
          <Link to="/payments" className="btn btn-secondary">Payments</Link>
        </div>
      </section>

      <section className="dash-main-grid">
        <article className="card dash-panel dash-focus-card">
          <div className="dash-panel-head">
            <div>
              <p className="dash-overline">
                {loading
                  ? 'Loading'
                  : pendingBooking
                    ? 'Payment pending'
                    : nextBooking
                      ? 'Next booking'
                      : 'Start here'}
              </p>
              <h2>{focusBooking ? (focusBooking.eventType?.name ?? focusBooking.eventType) : 'No active booking'}</h2>
            </div>

            {!loading && focusBooking && (
              <span className={`badge ${pendingBooking ? 'badge-warning' : bookingStatusBadge(focusBooking.status)}`}>
                {pendingBooking ? 'Action needed' : formatStatusLabel(focusBooking.status)}
              </span>
            )}
          </div>

          {loading ? (
            <p className="dash-muted">Loading the current booking details.</p>
          ) : focusBooking ? (
            <>
              <div className="dash-focus-meta">
                <span><FiCalendar /> {focusBooking.bookingDate || 'Date not set'}</span>
                <span><FiClock /> {focusBooking.startTime || 'Time not set'}</span>
                <span>{formatDuration(focusBooking)}</span>
              </div>

              <div className="dash-focus-tags">
                <span className={`badge ${bookingStatusBadge(focusBooking.status)}`}>
                  {formatStatusLabel(focusBooking.status)}
                </span>
                <span className={`badge ${paymentStatusBadge(focusBooking.paymentStatus)}`}>
                  {formatStatusLabel(focusBooking.paymentStatus, 'Pending')}
                </span>
              </div>

              <div className="dash-focus-total">{formatAmount(focusBooking.totalAmount)}</div>

              <p className="dash-muted">
                {pendingBooking
                  ? 'The booking is created, but the payment still needs to be completed.'
                  : 'Open the booking to review the reservation details or payment history.'}
              </p>

              <div className="dash-actions-row">
                {pendingBooking ? (
                  <Link to={`/payment/${focusBooking.bookingRef}`} className="btn btn-primary btn-sm">
                    <FiCreditCard /> Pay now
                  </Link>
                ) : (
                  <Link to={`/booking/${focusBooking.bookingRef}`} className="btn btn-primary btn-sm">
                    View booking <FiArrowRight />
                  </Link>
                )}

                <Link to="/my-bookings" className="btn btn-secondary btn-sm">Open timeline</Link>
              </div>
            </>
          ) : (
            <>
              <p className="dash-muted">Book when you are ready. This page will keep the key details visible once you do.</p>
              <div className="dash-actions-row">
                <Link to="/book" className="btn btn-primary btn-sm">Start your first booking</Link>
              </div>
            </>
          )}
        </article>

        <article className="card dash-panel dash-overview-card">
          <div className="dash-panel-head">
            <div>
              <p className="dash-overline">Overview</p>
              <h2>Only the essentials</h2>
            </div>

            {selectedBinge && (
              <span className="dash-venue-chip">
                <FiMapPin /> {selectedBinge.name}
              </span>
            )}
          </div>

          <div className="dash-overview-list">
            <div className="dash-overview-row">
              <span>Venue</span>
              <strong>{selectedBinge?.name || 'Not selected'}</strong>
            </div>
            <div className="dash-overview-row">
              <span>Upcoming bookings</span>
              <strong>{loading ? '–' : upcomingCount}</strong>
            </div>
            <div className="dash-overview-row">
              <span>Past visits</span>
              <strong>{loading ? '–' : pastCount}</strong>
            </div>
            <div className="dash-overview-row">
              <span>Total spend</span>
              <strong>{loading ? '–' : formatAmount(totalSpend)}</strong>
            </div>
          </div>

          <div className="dash-overview-note">
            <span>Pricing plan</span>
            <strong>{loading ? 'Loading pricing...' : pricingLabel}</strong>
            <p>{loading ? 'Checking your pricing details.' : pricingSourceLabel}</p>
          </div>

          {selectedBinge && <Link to="/binges" className="dash-inline-link">Change venue</Link>}
        </article>
      </section>

      {!loading && experienceItems.length > 0 && (
        <section className="dash-section">
          <div className="dash-section-header">
            <div className="dash-section-copy">
              <p className="dash-overline">{dashboardExperience.sectionEyebrow}</p>
              <h2>{dashboardExperience.sectionTitle}</h2>
              {dashboardExperience.sectionSubtitle && (
                <p className="dash-section-subtitle">{dashboardExperience.sectionSubtitle}</p>
              )}
            </div>

            <div className="dash-section-actions">
              {useExperienceCarousel && experienceItems.length > 1 && (
                <div className="dash-experience-nav" aria-label="Experience carousel controls">
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

          {useExperienceCarousel && activeExperience ? (
            renderExperienceCard(activeExperience, true)
          ) : (
            <div className="dash-experience-grid">
              {experienceItems.map((item) => renderExperienceCard(item))}
            </div>
          )}
        </section>
      )}

      <section className="dash-bottom-grid">
        <article className="card dash-panel dash-bookings-card">
          <div className="dash-panel-head">
            <div>
              <p className="dash-overline">Upcoming bookings</p>
              <h2>Your reservation list</h2>
            </div>

            <Link to="/my-bookings" className="dash-inline-link">See all</Link>
          </div>

          {loading ? (
            <SkeletonGrid count={4} columns={2} />
          ) : currentBookings.length === 0 ? (
            <div className="dash-empty-state">
              <p>No upcoming bookings yet.</p>
              <span>The next booking you create will show here with its payment status and amount.</span>
              <Link to="/book" className="btn btn-primary btn-sm">Start booking</Link>
            </div>
          ) : (
            <>
              <div className="dash-bookings-list">
                {pagedBookings.map((booking) => (
                  <article key={booking.bookingRef} className="dash-booking-item">
                    <div className="dash-booking-top">
                      <div className="dash-booking-title">
                        <strong>{booking.eventType?.name ?? booking.eventType}</strong>
                        <span className="dash-focus-ref">{booking.bookingRef}</span>
                      </div>

                      <div className="dash-booking-badges">
                        <span className={`badge ${bookingStatusBadge(booking.status)}`}>
                          {formatStatusLabel(booking.status)}
                        </span>
                        <span className={`badge ${paymentStatusBadge(booking.paymentStatus)}`}>
                          {formatStatusLabel(booking.paymentStatus, 'Pending')}
                        </span>
                      </div>
                    </div>

                    <div className="dash-booking-meta">
                      <span><FiCalendar /> {booking.bookingDate || 'Date not set'}</span>
                      <span><FiClock /> {booking.startTime || 'Time not set'}</span>
                      <span>{formatDuration(booking)}</span>
                    </div>

                    <div className="dash-booking-footer">
                      <strong>{formatAmount(booking.totalAmount)}</strong>

                      <div className="dash-actions-row">
                        <Link to={`/booking/${booking.bookingRef}`} className="btn btn-secondary btn-sm">View</Link>
                        {booking.status === 'PENDING' && booking.paymentStatus !== 'SUCCESS' && (
                          <Link to={`/payment/${booking.bookingRef}`} className="btn btn-primary btn-sm">
                            <FiCreditCard /> Pay
                          </Link>
                        )}
                      </div>
                    </div>
                  </article>
                ))}
              </div>

              {totalPages > 1 && (
                <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
              )}
            </>
          )}
        </article>

        <aside className="card dash-panel dash-support-card">
          <div className="dash-panel-head">
            <div>
              <p className="dash-overline">Support</p>
              <h2>Need help?</h2>
            </div>
          </div>

          <p className="dash-muted">
            Use support for payment questions, booking changes, or anything that needs a quick follow-up. Available during {support.hours}.
          </p>

          {supportLinks.length > 0 ? (
            <div className="dash-support-list">
              {supportLinks.map((item) => (
                <a
                  key={item.label}
                  className="dash-support-link"
                  href={item.href}
                  target={item.external ? '_blank' : undefined}
                  rel={item.external ? 'noreferrer' : undefined}
                >
                  {item.label}
                </a>
              ))}
            </div>
          ) : (
            <p className="dash-muted">Support details are not available right now.</p>
          )}

          <div className="dash-support-note">
            <span>Current pricing</span>
            <strong>{loading ? 'Loading pricing...' : pricingLabel}</strong>
            <p>{loading ? 'Checking your pricing details.' : pricingSourceLabel}</p>
          </div>
        </aside>
      </section>
    </div>
  );
}
