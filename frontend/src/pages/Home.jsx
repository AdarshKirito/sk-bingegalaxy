import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import {
  FiArrowRight,
  FiAward,
  FiBriefcase,
  FiCalendar,
  FiCamera,
  FiCheckCircle,
  FiClock,
  FiFilm,
  FiGift,
  FiHeart,
  FiMapPin,
  FiShield,
  FiSmile,
  FiStar,
  FiUsers,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import './Home.css';

const experienceHighlights = [
  {
    title: 'Curated Private Screenings',
    description: 'A room that feels reserved for your people, your playlist, your lights, and your moment.',
    icon: <FiFilm />,
  },
  {
    title: 'Fast Celebration Setup',
    description: 'Dates, add-ons, timing, and event planning get shaped into one clean booking flow.',
    icon: <FiCalendar />,
  },
  {
    title: 'Safer Payments',
    description: 'Checkout and callback handling are built for a controlled, verifiable payment path.',
    icon: <FiShield />,
  },
  {
    title: 'Photo-Ready Spaces',
    description: 'The room is designed to feel cinematic before the movie even starts.',
    icon: <FiCamera />,
  },
];

const signatureMoments = [
  {
    eyebrow: 'Birthday rooms',
    title: 'Celebrate with a full-screen surprise',
    description: 'Cake table, private seating, your playlist, and a clean reveal moment without crowd noise.',
    accent: 'Sunrise Gold',
  },
  {
    eyebrow: 'Proposal setups',
    title: 'A cinematic yes without the chaos',
    description: 'Build a controlled, intimate setting with lighting, timing, and a sharper emotional reveal.',
    accent: 'Rose Velvet',
  },
  {
    eyebrow: 'Corporate screenings',
    title: 'Present without a banquet-hall feel',
    description: 'Use the theater like a polished private venue for launches, team events, or premium client sessions.',
    accent: 'Midnight Slate',
  },
];

const planningSteps = [
  {
    number: '01',
    title: 'Pick the mood',
    description: 'Birthday, proposal, anniversary, surprise date, or a private movie night with friends.',
  },
  {
    number: '02',
    title: 'Lock the slot',
    description: 'Choose your venue, date, show window, guest count, and add-ons in one flow.',
  },
  {
    number: '03',
    title: 'Walk into a finished setup',
    description: 'Get the confirmation, arrive with your booking reference, and let the room do the work.',
  },
];

const packageCards = [
  { name: 'Birthday Party', price: '₹4,999', icon: <FiGift />, note: 'Cake-first setup with celebration framing.' },
  { name: 'Anniversary', price: '₹5,999', icon: <FiHeart />, note: 'Soft lighting, private seating, cleaner atmosphere.' },
  { name: 'Proposal Setup', price: '₹7,999', icon: <FiStar />, note: 'A high-focus room built for one main reveal.' },
  { name: 'HD Screening', price: '₹2,999', icon: <FiFilm />, note: 'Straight private screening without event extras.' },
  { name: 'Corporate Event', price: '₹9,999', icon: <FiBriefcase />, note: 'Presentations and screenings without banquet noise.' },
  { name: 'Baby Shower', price: '₹5,499', icon: <FiSmile />, note: 'Comfort-first staging for smaller private groups.' },
];

const guestSignals = [
  {
    label: 'For couples',
    quote: 'It feels more like entering a planned scene than walking into a normal theater show.',
  },
  {
    label: 'For birthdays',
    quote: 'The room already feels like the event before the screen even turns on.',
  },
  {
    label: 'For teams',
    quote: 'Private enough to feel premium, simple enough to book without operational mess.',
  },
];

export default function Home() {
  const { isAuthenticated, isAdmin } = useAuth();

  return (
    <div className="home">
      <SEO title="Home" description="Book an exclusive private theater for birthdays, anniversaries, proposals, and more with SK Binge Galaxy." />
      <section className="home-hero">
        <div className="container home-hero-shell">
          <div className="home-hero-copy">
            <span className="home-kicker">Private theater experiences for occasions that should not feel generic</span>
            <h1>
              Make the <span>first impression</span> feel bigger than the screen.
            </h1>
            <p>
              SK Binge Galaxy is the pre-login home for private screenings, birthday rooms, proposal setups, and premium event nights.
              When someone clicks the SK Binge title, this is the page they should land on.
            </p>
            <div className="home-hero-actions">
              {isAuthenticated ? (
                isAdmin ? (
                  <Link to="/admin/dashboard" className="btn btn-primary home-cta-primary">
                    Open Admin Dashboard <FiArrowRight />
                  </Link>
                ) : (
                  <Link to="/book" className="btn btn-primary home-cta-primary">
                    Start Booking <FiArrowRight />
                  </Link>
                )
              ) : (
                <>
                  <Link to="/register" className="btn btn-primary home-cta-primary">
                    Plan My Experience <FiArrowRight />
                  </Link>
                  <Link to="/login" className="btn btn-secondary home-cta-secondary">
                    Sign In
                  </Link>
                </>
              )}
            </div>
            <div className="home-proof-strip" aria-label="Experience highlights">
              <div>
                <strong>500+</strong>
                <span>private celebrations hosted</span>
              </div>
              <div>
                <strong>3 steps</strong>
                <span>from plan to confirmed booking</span>
              </div>
              <div>
                <strong>100%</strong>
                <span>exclusive room access for your slot</span>
              </div>
            </div>
          </div>

          <div className="home-hero-stage" aria-hidden="true">
            <div className="home-stage-card home-stage-primary">
              <span className="stage-tag">Tonight&apos;s Signature Flow</span>
              <h2>Golden aisle. Custom screen moment. Zero outside crowd.</h2>
              <ul>
                <li><FiCheckCircle /> Private venue feel from arrival to exit</li>
                <li><FiClock /> Built for planned reveals and timed entries</li>
                <li><FiUsers /> Works for couples, families, and premium group events</li>
              </ul>
            </div>
            <div className="home-stage-card home-stage-secondary">
              <span className="stage-mini-title">What gets remembered</span>
              <div className="stage-metric-grid">
                <article>
                  <FiMapPin />
                  <strong>Private venue vibe</strong>
                  <span>No shared audience energy.</span>
                </article>
                <article>
                  <FiAward />
                  <strong>Premium reveal moments</strong>
                  <span>Better for milestones than a normal show.</span>
                </article>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="home-marquee">
        <div className="container home-marquee-track">
          <span>Birthday Nights</span>
          <span>Proposal Reveals</span>
          <span>Anniversary Setups</span>
          <span>Private Screenings</span>
          <span>Corporate Shows</span>
          <span>Family Celebrations</span>
        </div>
      </section>

      <section className="home-section container">
        <div className="home-section-heading">
          <span className="home-section-kicker">Why this home page should convert better</span>
          <h2>It sells the feeling before it asks for the login.</h2>
          <p>
            The public landing experience should make the venue feel cinematic, private, and premium before users ever see an auth form.
          </p>
        </div>
        <div className="home-feature-grid">
          {experienceHighlights.map((item) => (
            <article key={item.title} className="home-feature-card">
              <div className="home-feature-icon">{item.icon}</div>
              <h3>{item.title}</h3>
              <p>{item.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-signature-section">
        <div className="home-section-heading home-section-heading-tight">
          <span className="home-section-kicker">Signature moments</span>
          <h2>Different moods, same private-screen advantage.</h2>
        </div>
        <div className="home-signature-grid">
          {signatureMoments.map((moment) => (
            <article key={moment.title} className="home-signature-card">
              <span className="home-signature-accent">{moment.accent}</span>
              <span className="home-signature-eyebrow">{moment.eyebrow}</span>
              <h3>{moment.title}</h3>
              <p>{moment.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-process-section">
        <div className="home-section-heading home-section-heading-tight">
          <span className="home-section-kicker">Simple booking rhythm</span>
          <h2>Plan the night without getting lost in the UI.</h2>
        </div>
        <div className="home-process-grid">
          {planningSteps.map((step) => (
            <article key={step.number} className="home-process-card">
              <span className="home-process-number">{step.number}</span>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-guest-section">
        <div className="home-section-heading home-section-heading-tight">
          <span className="home-section-kicker">How the room should feel</span>
          <h2>Premium enough for a milestone, simple enough for a fast decision.</h2>
        </div>
        <div className="home-guest-grid">
          {guestSignals.map((signal) => (
            <article key={signal.label} className="home-guest-card">
              <span className="home-guest-label">{signal.label}</span>
              <p>{signal.quote}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-packages-section">
        <div className="home-section-heading">
          <span className="home-section-kicker">Indicative packages</span>
          <h2>Built for occasions, not just ticket sales.</h2>
          <p>Starting prices below are directional. Final pricing should still reflect slot, setup depth, and add-ons.</p>
        </div>
        <div className="home-package-grid">
          {packageCards.map((evt) => (
            <article key={evt.name} className="home-package-card">
              <span className="home-package-icon">{evt.icon}</span>
              <h3>{evt.name}</h3>
              <p className="home-package-price">Starting at {evt.price}</p>
              <p className="home-package-note">{evt.note}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-final-cta">
        <div>
          <span className="home-section-kicker">Ready when you are</span>
          <h2>Click the SK Binge title anytime and this should still feel like the right front door.</h2>
          <p>
            It now works as a proper public homepage before login, while still handing authenticated users off to booking or admin actions.
          </p>
        </div>
        <div className="home-final-actions">
          {isAuthenticated ? (
            isAdmin ? (
              <Link to="/admin/dashboard" className="btn btn-primary">
                Go to Admin Dashboard <FiArrowRight />
              </Link>
            ) : (
              <Link to="/book" className="btn btn-primary">
                Continue to Booking <FiArrowRight />
              </Link>
            )
          ) : (
            <>
              <Link to="/register" className="btn btn-primary">
                Create Account <FiArrowRight />
              </Link>
              <Link to="/login" className="btn btn-secondary">
                Sign In
              </Link>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
