import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();

  const experienceHighlightsLocal = [
    { title: t('home.exp_screening_title', 'Curated Private Screenings'), description: t('home.exp_screening_desc', 'A room that feels reserved for your people, your playlist, your lights, and your moment.'), icon: <FiFilm /> },
    { title: t('home.exp_setup_title', 'Fast Celebration Setup'), description: t('home.exp_setup_desc', 'Dates, add-ons, timing, and event planning get shaped into one clean booking flow.'), icon: <FiCalendar /> },
    { title: t('home.exp_payment_title', 'Safer Payments'), description: t('home.exp_payment_desc', 'Checkout and callback handling are built for a controlled, verifiable payment path.'), icon: <FiShield /> },
    { title: t('home.exp_photo_title', 'Photo-Ready Spaces'), description: t('home.exp_photo_desc', 'The room is designed to feel cinematic before the movie even starts.'), icon: <FiCamera /> },
  ];

  const signatureMomentsLocal = [
    { eyebrow: t('home.sig_birthday_eyebrow', 'Birthday rooms'), title: t('home.sig_birthday_title', 'Celebrate with a full-screen surprise'), description: t('home.sig_birthday_desc', 'Cake table, private seating, your playlist, and a clean reveal moment without crowd noise.'), accent: t('home.sig_birthday_accent', 'Sunrise Gold') },
    { eyebrow: t('home.sig_proposal_eyebrow', 'Proposal setups'), title: t('home.sig_proposal_title', 'A cinematic yes without the chaos'), description: t('home.sig_proposal_desc', 'Build a controlled, intimate setting with lighting, timing, and a sharper emotional reveal.'), accent: t('home.sig_proposal_accent', 'Rose Velvet') },
    { eyebrow: t('home.sig_corporate_eyebrow', 'Corporate screenings'), title: t('home.sig_corporate_title', 'Present without a banquet-hall feel'), description: t('home.sig_corporate_desc', 'Use the theater like a polished private venue for launches, team events, or premium client sessions.'), accent: t('home.sig_corporate_accent', 'Midnight Slate') },
  ];

  const planningStepsLocal = [
    { number: '01', title: t('home.step1_title', 'Pick the mood'), description: t('home.step1_desc', 'Birthday, proposal, anniversary, surprise date, or a private movie night with friends.') },
    { number: '02', title: t('home.step2_title', 'Lock the slot'), description: t('home.step2_desc', 'Choose your venue, date, show window, guest count, and add-ons in one flow.') },
    { number: '03', title: t('home.step3_title', 'Walk into a finished setup'), description: t('home.step3_desc', 'Get the confirmation, arrive with your booking reference, and let the room do the work.') },
  ];

  const packageCardsLocal = [
    { name: t('home.pkg_birthday', 'Birthday Party'), price: t('home.pkg_birthday_price', '₹4,999'), icon: <FiGift />, note: t('home.pkg_birthday_note', 'Cake-first setup with celebration framing.') },
    { name: t('home.pkg_anniversary', 'Anniversary'), price: t('home.pkg_anniversary_price', '₹5,999'), icon: <FiHeart />, note: t('home.pkg_anniversary_note', 'Soft lighting, private seating, cleaner atmosphere.') },
    { name: t('home.pkg_proposal', 'Proposal Setup'), price: t('home.pkg_proposal_price', '₹7,999'), icon: <FiStar />, note: t('home.pkg_proposal_note', 'A high-focus room built for one main reveal.') },
    { name: t('home.pkg_screening', 'HD Screening'), price: t('home.pkg_screening_price', '₹2,999'), icon: <FiFilm />, note: t('home.pkg_screening_note', 'Straight private screening without event extras.') },
    { name: t('home.pkg_corporate', 'Corporate Event'), price: t('home.pkg_corporate_price', '₹9,999'), icon: <FiBriefcase />, note: t('home.pkg_corporate_note', 'Presentations and screenings without banquet noise.') },
    { name: t('home.pkg_babyshower', 'Baby Shower'), price: t('home.pkg_babyshower_price', '₹5,499'), icon: <FiSmile />, note: t('home.pkg_babyshower_note', 'Comfort-first staging for smaller private groups.') },
  ];

  const guestSignalsLocal = [
    { label: t('home.guest_couples', 'For couples'), quote: t('home.guest_couples_quote', 'It feels more like entering a planned scene than walking into a normal theater show.') },
    { label: t('home.guest_birthdays', 'For birthdays'), quote: t('home.guest_birthdays_quote', 'The room already feels like the event before the screen even turns on.') },
    { label: t('home.guest_teams', 'For teams'), quote: t('home.guest_teams_quote', 'Private enough to feel premium, simple enough to book without operational mess.') },
  ];

  return (
    <div className="home">
      <SEO title={t('nav.home')} description={t('home.seo_desc', 'Book an exclusive private theater for birthdays, anniversaries, proposals, and more with SK Binge Galaxy.')} />
      <section className="home-hero">
        <div className="container home-hero-shell">
          <div className="home-hero-copy">
            <span className="home-kicker">{t('home.kicker', 'Private theater experiences for occasions that should not feel generic')}</span>
            <h1>
              {t('home.hero_line1', 'Make the')} <span>{t('home.hero_highlight', 'first impression')}</span> {t('home.hero_line2', 'feel bigger than the screen.')}
            </h1>
            <p>{t('home.hero_desc', 'SK Binge Galaxy is the pre-login home for private screenings, birthday rooms, proposal setups, and premium event nights. When someone clicks the SK Binge title, this is the page they should land on.')}</p>
            <div className="home-hero-actions">
              {isAuthenticated ? (
                isAdmin ? (
                  <Link to="/admin/dashboard" className="btn btn-primary home-cta-primary">
                    {t('home.cta_admin', 'Open Admin Dashboard')} <FiArrowRight />
                  </Link>
                ) : (
                  <Link to="/book" className="btn btn-primary home-cta-primary">
                    {t('home.cta_book')} <FiArrowRight />
                  </Link>
                )
              ) : (
                <>
                  <Link to="/register" className="btn btn-primary home-cta-primary">
                    {t('home.cta_plan', 'Plan My Experience')} <FiArrowRight />
                  </Link>
                  <Link to="/login" className="btn btn-secondary home-cta-secondary">
                    {t('auth.sign_in')}
                  </Link>
                </>
              )}
            </div>
            <div className="home-proof-strip" aria-label="Experience highlights">
              <div>
                <strong>500+</strong>
                <span>{t('home.proof_celebrations', 'private celebrations hosted')}</span>
              </div>
              <div>
                <strong>{t('home.proof_steps_num', '3 steps')}</strong>
                <span>{t('home.proof_steps_desc', 'from plan to confirmed booking')}</span>
              </div>
              <div>
                <strong>100%</strong>
                <span>{t('home.proof_exclusive', 'exclusive room access for your slot')}</span>
              </div>
            </div>
          </div>

          <div className="home-hero-stage" aria-hidden="true">
            <div className="home-stage-card home-stage-primary">
              <span className="stage-tag">{t('home.stage_tag', "Tonight's Signature Flow")}</span>
              <h2>{t('home.stage_title', 'Golden aisle. Custom screen moment. Zero outside crowd.')}</h2>
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
          <span>{t('home.marquee_birthday', 'Birthday Nights')}</span>
          <span>{t('home.marquee_proposal', 'Proposal Reveals')}</span>
          <span>{t('home.marquee_anniversary', 'Anniversary Setups')}</span>
          <span>{t('home.marquee_screening', 'Private Screenings')}</span>
          <span>{t('home.marquee_corporate', 'Corporate Shows')}</span>
          <span>{t('home.marquee_family', 'Family Celebrations')}</span>
        </div>
      </section>

      <section className="home-section container">
        <div className="home-section-heading">
          <span className="home-section-kicker">{t('home.sec_features_kicker', 'Why this home page should convert better')}</span>
          <h2>{t('home.sec_features_title', 'It sells the feeling before it asks for the login.')}</h2>
          <p>{t('home.sec_features_desc', 'The public landing experience should make the venue feel cinematic, private, and premium before users ever see an auth form.')}</p>
        </div>
        <div className="home-feature-grid">
          {experienceHighlightsLocal.map((item) => (
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
          <span className="home-section-kicker">{t('home.sec_signature_kicker', 'Signature moments')}</span>
          <h2>{t('home.sec_signature_title', 'Different moods, same private-screen advantage.')}</h2>
        </div>
        <div className="home-signature-grid">
          {signatureMomentsLocal.map((moment) => (
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
          <span className="home-section-kicker">{t('home.sec_process_kicker', 'Simple booking rhythm')}</span>
          <h2>{t('home.sec_process_title', 'Plan the night without getting lost in the UI.')}</h2>
        </div>
        <div className="home-process-grid">
          {planningStepsLocal.map((step) => (
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
          <span className="home-section-kicker">{t('home.sec_guest_kicker', 'How the room should feel')}</span>
          <h2>{t('home.sec_guest_title', 'Premium enough for a milestone, simple enough for a fast decision.')}</h2>
        </div>
        <div className="home-guest-grid">
          {guestSignalsLocal.map((signal) => (
            <article key={signal.label} className="home-guest-card">
              <span className="home-guest-label">{signal.label}</span>
              <p>{signal.quote}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-packages-section">
        <div className="home-section-heading">
          <span className="home-section-kicker">{t('home.sec_packages_kicker', 'Indicative packages')}</span>
          <h2>{t('home.sec_packages_title', 'Built for occasions, not just ticket sales.')}</h2>
          <p>{t('home.sec_packages_desc', 'Starting prices below are directional. Final pricing should still reflect slot, setup depth, and add-ons.')}</p>
        </div>
        <div className="home-package-grid">
          {packageCardsLocal.map((evt) => (
            <article key={evt.name} className="home-package-card">
              <span className="home-package-icon">{evt.icon}</span>
              <h3>{evt.name}</h3>
              <p className="home-package-price">{t('home.starting_at', 'Starting at')} {evt.price}</p>
              <p className="home-package-note">{evt.note}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-final-cta">
        <div>
          <span className="home-section-kicker">{t('home.sec_final_kicker', 'Ready when you are')}</span>
          <h2>{t('home.sec_final_title', 'Click the SK Binge title anytime and this should still feel like the right front door.')}</h2>
          <p>{t('home.sec_final_desc', 'It now works as a proper public homepage before login, while still handing authenticated users off to booking or admin actions.')}</p>
        </div>
        <div className="home-final-actions">
          {isAuthenticated ? (
            isAdmin ? (
              <Link to="/admin/dashboard" className="btn btn-primary">
                {t('home.cta_admin', 'Open Admin Dashboard')} <FiArrowRight />
              </Link>
            ) : (
              <Link to="/book" className="btn btn-primary">
                {t('home.cta_continue', 'Continue to Booking')} <FiArrowRight />
              </Link>
            )
          ) : (
            <>
              <Link to="/register" className="btn btn-primary">
                {t('auth.register_title')} <FiArrowRight />
              </Link>
              <Link to="/login" className="btn btn-secondary">
                {t('auth.sign_in')}
              </Link>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
