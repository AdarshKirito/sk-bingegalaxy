import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { toast } from 'react-toastify';
import BookingWizard from '../components/BookingWizard';
import SEO from '../components/SEO';
import useBingeStore from '../stores/bingeStore';
import { FiCalendar, FiClock, FiCreditCard, FiMapPin } from 'react-icons/fi';

export default function BookingPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { selectedBinge } = useBingeStore();
  const prefillBooking = location.state?.prefillBooking || null;
  const initialEventTypeId = Number(location.state?.eventTypeId || prefillBooking?.eventTypeId || searchParams.get('eventType') || '') || null;
  const prefilledEventName = location.state?.eventTypeName || null;

  const handleSubmit = async (payload) => {
    const res = await bookingService.createBooking(payload);
    const ref = res.data.data.bookingRef;
    toast.success('Booking created!');
    navigate(`/booking/${ref}`);
  };

  return (
    <>
      <SEO title="Book Your Experience" description="Choose your event type, date, time, and add-ons to create your perfect private theater experience." />
      <section className="booking-shell-hero-wrap">
        <div className="container booking-shell-hero">
          <div className="booking-shell-copy">
            <span className="booking-shell-kicker">Customer Booking Flow</span>
            <h1>Build a private screening that already feels planned before checkout.</h1>
            <p>
              {selectedBinge
                ? `Booking at ${selectedBinge.name} with live availability, payment handoff, and a cleaner review flow.`
                : 'Pick the event, choose the slot, and move to payment without losing context.'}
            </p>
            <div className="booking-shell-chips">
              <span><FiCalendar /> Live slot selection</span>
              <span><FiClock /> Fast repeat booking</span>
              <span><FiCreditCard /> Direct payment handoff</span>
            </div>
          </div>

          <aside className="booking-shell-panel">
            <span className="booking-shell-panel-label">Ready to reserve</span>
            <h2>{prefilledEventName || (prefillBooking ? 'Repeat booking loaded' : 'Fresh booking flow')}</h2>
            <p>
              {prefillBooking
                ? 'We preloaded the event, guest count, duration, and add-ons from your earlier booking so you can jump straight to date and time.'
                : initialEventTypeId
                  ? 'An experience is already selected for you. Confirm the details and choose a date.'
                  : 'Start with the experience that matches your plan, then customize the rest.'}
            </p>
            <div className="booking-shell-panel-meta">
              {selectedBinge && <span><FiMapPin /> {selectedBinge.name}</span>}
              <span><FiCalendar /> 4-step guided flow</span>
            </div>
          </aside>
        </div>
      </section>

      <BookingWizard
        isAdmin={false}
        prefillData={prefillBooking}
        initialEventTypeId={initialEventTypeId}
        onSubmit={handleSubmit}
      />
    </>
  );
}
