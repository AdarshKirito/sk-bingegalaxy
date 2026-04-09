import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useAuth } from '../context/AuthContext';
import { authService, bookingService, paymentService } from '../services/endpoints';
import useBingeStore from '../stores/bingeStore';
import {
  EXPERIENCE_STEPS,
  getMemberTier,
  HELP_FAQS,
  mergeSupportContact,
  MEMBER_OFFERS,
} from '../services/customerExperience';
import { FiCalendar, FiClock, FiArrowRight, FiCreditCard, FiMapPin, FiDollarSign, FiTag, FiGift, FiHeart, FiStar, FiFilm, FiBriefcase, FiSmile, FiRepeat, FiShield, FiUser, FiMessageCircle } from 'react-icons/fi';
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
  const [payments, setPayments] = useState([]);
  const [eventTypes, setEventTypes] = useState([]);
  const [myPricing, setMyPricing] = useState(null);
  const [supportContact, setSupportContact] = useState(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const perPage = 4;

  useEffect(() => {
    const loads = [
      authService.getSupportContact().then(r => setSupportContact(r.data.data || null)).catch(() => null),
      bookingService.getCurrentBookings().then(r => setCurrentBookings(r.data.data || [])).catch(() => null),
      bookingService.getPastBookings().then(r => setPastBookings(r.data.data || [])).catch(() => null),
      paymentService.getMyPayments().then(r => setPayments(r.data.data || [])).catch(() => null),
      bookingService.getEventTypes().then(r => setEventTypes((r.data.data || r.data || []).filter(e => e.active !== false))).catch(() => null),
      bookingService.getMyPricing().then(r => setMyPricing(r.data.data || null)).catch(() => null),
    ];
    Promise.allSettled(loads).then(results => {
      const failed = results.filter(r => r.status === 'rejected' || r.value === null).length;
      if (failed > 0) toast.error('Some dashboard data could not be loaded.');
    }).finally(() => setLoading(false));
  }, []);

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
  const support = mergeSupportContact(supportContact);
  const memberTier = useMemo(() => getMemberTier(pastCount, totalSpend), [pastCount, totalSpend]);
  const pricingLabel = myPricing?.rateCodeName || 'Standard';
  const pricingSourceLabel = myPricing?.pricingSource === 'RATE_CODE'
    ? 'Rate code pricing is active on your account.'
    : myPricing?.pricingSource === 'CUSTOM'
      ? 'Custom pricing is active on your account.'
      : 'You are currently on standard customer pricing.';

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
    if (lowerName.includes('birthday')) return { tag: 'Celebrations', blurb: 'Cake, music, memories, and a room that is fully yours.' };
    if (lowerName.includes('anniversary') || lowerName.includes('proposal')) return { tag: 'Romance', blurb: 'Build a quieter, more personal setup for milestone moments.' };
    if (lowerName.includes('corporate')) return { tag: 'Teams', blurb: 'Present, celebrate wins, or host a private watch party with your crew.' };
    if (lowerName.includes('baby')) return { tag: 'Family', blurb: 'A softer, family-first setup for shared moments and photos.' };
    if (lowerName.includes('hd') || lowerName.includes('screen')) return { tag: 'Movie Night', blurb: 'Keep it simple and cinematic with a focused private-screening setup.' };
    return { tag: 'Private Event', blurb: 'Shape the room around your plan instead of fitting into a public showtime.' };
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
          <h3>{loading ? 'Checking your plan...' : `${memberTier} tier`}</h3>
          <p>{loading ? 'We are resolving your current customer pricing.' : `${pricingLabel} pricing is paired with your ${memberTier.toLowerCase()} member status.`}</p>
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
              <h2>Reasons to keep booking through your account</h2>
            </div>
            <Link to="/account" className="dash-inline-link">Account center</Link>
          </div>
          <div className="dash-loyalty-offers">
            {MEMBER_OFFERS.map((offer) => (
              <article key={offer.title} className="dash-loyalty-offer">
                <span className="dash-card-kicker">{offer.title}</span>
                <h3>{offer.title}</h3>
                <p>{offer.description}</p>
              </article>
            ))}
          </div>
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

      {!loading && eventTypes.length > 0 && (
        <section className="dash-experiences">
          <div className="dash-section-header">
            <div>
              <span className="dash-section-kicker">Explore Experiences</span>
              <h2>Pick a setup that matches the mood</h2>
            </div>
            <Link to="/book" className="dash-inline-link">Open booking flow</Link>
          </div>

          <div className="dash-exp-grid">
            {eventTypes.slice(0, 6).map(evt => {
              const tone = getExperienceTone(evt.name);
              return (
                <article key={evt.id} className="dash-exp-card card">
                  <div className="dash-exp-topline">
                    <span className="dash-exp-pill">{tone.tag}</span>
                    <div className="dash-exp-icon">{getExpIcon(evt.name)}</div>
                  </div>
                  <h3>{evt.name}</h3>
                  <p className="dash-exp-desc">{evt.description || tone.blurb}</p>
                  <div className="dash-exp-footer">
                    <div>
                      <span className="dash-exp-price-label">Starts from</span>
                      <strong className="dash-exp-price">{formatAmount(evt.basePrice)}</strong>
                    </div>
                    <Link
                      to="/book"
                      state={{ eventTypeId: evt.id, eventTypeName: evt.name }}
                      className="btn btn-primary btn-sm dash-exp-btn"
                    >
                      Build This <FiArrowRight />
                    </Link>
                  </div>
                </article>
              );
            })}
          </div>
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
