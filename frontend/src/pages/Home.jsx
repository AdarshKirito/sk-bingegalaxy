import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import {
  FiArrowRight, FiAward, FiBriefcase, FiCalendar, FiCamera, FiCheckCircle,
  FiChevronLeft, FiChevronRight, FiClock, FiFilm, FiGift, FiHeart, FiMapPin,
  FiShield, FiSmile, FiStar, FiUsers,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import { siteContentService } from '../services/endpoints';
import { HOME_CMS_SLUG, defaultHomeContent, mergeHomeContent } from '../content/homeDefaults';
import './Home.css';

// Map serialized icon keys → React icon nodes. Lets the CMS store small
// stable identifiers ("film") instead of forcing the editor to think about
// component imports. Falls back to a sensible default if unknown.
const ICONS = {
  film: <FiFilm />, calendar: <FiCalendar />, shield: <FiShield />, camera: <FiCamera />,
  gift: <FiGift />, heart: <FiHeart />, star: <FiStar />, briefcase: <FiBriefcase />,
  smile: <FiSmile />, users: <FiUsers />, award: <FiAward />, clock: <FiClock />,
  mappin: <FiMapPin />, check: <FiCheckCircle />,
};
const iconFor = (key) => ICONS[String(key || '').toLowerCase()] ?? <FiStar />;

function HeroCarousel({ images, intervalMs = 4500 }) {
  const safe = useMemo(() => (Array.isArray(images) && images.length > 0 ? images : null), [images]);
  const length = safe?.length ?? 0;
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);

  // Keep autoplay alive by re-arming a timer whenever the index changes,
  // the user pauses (hover/focus), or the tab visibility changes. Manual
  // nav (arrows/dots) updates `index`, which restarts the timer cleanly —
  // so autoplay never permanently stops after a click.
  useEffect(() => {
    if (length <= 1 || paused || intervalMs <= 0) return undefined;
    const id = setTimeout(() => setIndex(i => (i + 1) % length), intervalMs);
    return () => clearTimeout(id);
  }, [index, length, paused, intervalMs]);

  useEffect(() => {
    const onVis = () => setPaused(document.hidden);
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  if (!safe) return null;
  const go = (delta) => setIndex(i => (i + delta + length) % length);

  return (
    <div
      className="home-carousel"
      role="region"
      aria-label="Featured experiences"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <div className="home-carousel-frame">
        {safe.map((img, i) => (
          <figure
            key={`${img.url}-${i}`}
            className={`home-carousel-slide ${i === index ? 'is-active' : ''}`}
            aria-hidden={i !== index}
          >
            <img src={img.url} alt={img.caption || `Featured ${i + 1}`} loading={i === 0 ? 'eager' : 'lazy'} />
            {img.caption && <figcaption>{img.caption}</figcaption>}
          </figure>
        ))}
      </div>
      {length > 1 && (
        <>
          <button type="button" className="home-carousel-nav home-carousel-prev" onClick={() => go(-1)} aria-label="Previous slide"><FiChevronLeft /></button>
          <button type="button" className="home-carousel-nav home-carousel-next" onClick={() => go(1)} aria-label="Next slide"><FiChevronRight /></button>
          <div className="home-carousel-dots" role="tablist">
            {safe.map((_, i) => (
              <button
                key={i}
                role="tab"
                aria-selected={i === index}
                aria-label={`Go to slide ${i + 1}`}
                className={`home-carousel-dot ${i === index ? 'is-active' : ''}`}
                onClick={() => setIndex(i)}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/**
 * Full-bleed Amazon / Netflix style banner carousel. Each slide is a large
 * photograph with overlay headline + CTA, autoplay with a thin progress bar
 * (so the user can see when the next slide is coming), large hover-to-reveal
 * arrow controls, dot indicators, pause-on-hover, and keyboard arrow navigation.
 */
function BigBannerCarousel({ slides, autoplayMs = 6000 }) {
  const safe = useMemo(() => (Array.isArray(slides) && slides.length > 0 ? slides : []), [slides]);
  const length = safe.length;
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [progress, setProgress] = useState(0);
  const rafRef = useRef(null);
  const startRef = useRef(0);
  const accumRef = useRef(0);
  const rootRef = useRef(null);

  // Drive autoplay with requestAnimationFrame so we can render an exact-fit
  // progress bar without battery-draining setInterval(33ms). Pauses while
  // the tab is hidden or the cursor is over the banner.
  useEffect(() => {
    if (length <= 1 || paused || autoplayMs <= 0) return undefined;
    startRef.current = performance.now();
    const tick = (now) => {
      const elapsed = (now - startRef.current) + accumRef.current;
      const pct = Math.min(1, elapsed / autoplayMs);
      setProgress(pct);
      if (pct >= 1) {
        accumRef.current = 0;
        setIndex(i => (i + 1) % length);
        startRef.current = performance.now();
        setProgress(0);
      }
      rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      // Carry over fractional progress so pause→resume picks up where it left off.
      accumRef.current += performance.now() - startRef.current;
    };
  }, [length, paused, autoplayMs, index]);

  // Pause when the document is hidden (tab in background, OS lock screen).
  useEffect(() => {
    const onVis = () => setPaused(document.hidden);
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  // Keyboard navigation when the banner has focus.
  useEffect(() => {
    const node = rootRef.current;
    if (!node) return undefined;
    const onKey = (e) => {
      if (e.key === 'ArrowLeft') { e.preventDefault(); reset(); setIndex(i => (i - 1 + length) % length); }
      if (e.key === 'ArrowRight') { e.preventDefault(); reset(); setIndex(i => (i + 1) % length); }
    };
    node.addEventListener('keydown', onKey);
    return () => node.removeEventListener('keydown', onKey);
  }, [length]);

  if (length === 0) return null;

  const reset = () => { accumRef.current = 0; setProgress(0); startRef.current = performance.now(); };
  const go = (delta) => { reset(); setIndex(i => (i + delta + length) % length); };
  const goto = (i) => { reset(); setIndex(i); };

  return (
    <div
      ref={rootRef}
      className="home-banner"
      role="region"
      aria-roledescription="carousel"
      aria-label="Featured private experiences"
      tabIndex={0}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <div className="home-banner-track">
        {safe.map((slide, i) => (
          <article
            key={`${slide.url}-${i}`}
            className={`home-banner-slide ${i === index ? 'is-active' : ''} home-banner-align-${slide.align || 'left'}`}
            aria-hidden={i !== index}
            aria-roledescription="slide"
            aria-label={`${i + 1} of ${length}: ${slide.title || slide.kicker || ''}`}
          >
            <img
              src={slide.url}
              alt={slide.title || slide.kicker || `Slide ${i + 1}`}
              loading={i === 0 ? 'eager' : 'lazy'}
              decoding="async"
            />
            <div className="home-banner-scrim" aria-hidden="true" />
            <div className="home-banner-content">
              <div className="container home-banner-shell">
                {slide.kicker && <span className="home-banner-kicker">{slide.kicker}</span>}
                {slide.title && <h2 className="home-banner-title">{slide.title}</h2>}
                {slide.subtitle && <p className="home-banner-subtitle">{slide.subtitle}</p>}
                {slide.ctaLabel && (
                  <Link to={slide.ctaHref || '/register'} className="btn btn-primary home-banner-cta">
                    {slide.ctaLabel} <FiArrowRight />
                  </Link>
                )}
              </div>
            </div>
          </article>
        ))}
      </div>

      {length > 1 && (
        <>
          <button type="button" className="home-banner-nav home-banner-prev" onClick={() => go(-1)} aria-label="Previous slide">
            <FiChevronLeft />
          </button>
          <button type="button" className="home-banner-nav home-banner-next" onClick={() => go(1)} aria-label="Next slide">
            <FiChevronRight />
          </button>
          <div className="home-banner-controls">
            <div className="home-banner-dots" role="tablist" aria-label="Choose slide">
              {safe.map((s, i) => (
                <button
                  key={i}
                  role="tab"
                  aria-selected={i === index}
                  aria-label={`Go to slide ${i + 1}${s.title ? `: ${s.title}` : ''}`}
                  className={`home-banner-dot ${i === index ? 'is-active' : ''}`}
                  onClick={() => goto(i)}
                >
                  {i === index && <span className="home-banner-dot-fill" style={{ width: `${Math.round(progress * 100)}%` }} />}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default function Home() {
  const { isAuthenticated, isAdmin } = useAuth();
  const { t } = useTranslation();
  const [content, setContent] = useState(defaultHomeContent);

  useEffect(() => {
    let cancelled = false;
    siteContentService.getPublic(HOME_CMS_SLUG).then(res => {
      if (cancelled) return;
      const raw = res?.data?.data?.contentJson;
      if (!raw) return; // never been edited — use defaults
      try {
        const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
        setContent(mergeHomeContent(parsed));
      } catch (err) {
        // Bad JSON in DB — keep defaults rather than crash the homepage.
        console.warn('[Home CMS] failed to parse content, using defaults', err);
      }
    }).catch(() => { /* network/no-content: defaults are fine */ });
    return () => { cancelled = true; };
  }, []);

  const { hero, proofStrip, gallery, marquee, features, signature, process, packages, finalCta, bannerCarousel } = content;
  const bannerEnabled = bannerCarousel?.enabled !== false && Array.isArray(bannerCarousel?.slides) && bannerCarousel.slides.length > 0;

  return (
    <div className="home">
      <SEO title={t('nav.home')} description={hero.description} />

      <section className="home-hero">
        <div className="container home-hero-shell">
          <div className="home-hero-copy">
            <span className="home-kicker">{hero.kicker}</span>
            <h1>
              {hero.headline} <span>{hero.headlineHighlight}</span> {hero.headlineSuffix}
            </h1>
            <p>{hero.description}</p>
            <div className="home-hero-actions">
              {isAuthenticated ? (
                isAdmin ? (
                  <Link to="/admin/dashboard" className="btn btn-primary home-cta-primary">
                    {t('home.cta_admin', 'Open Admin Dashboard')} <FiArrowRight />
                  </Link>
                ) : (
                  <Link to="/book" className="btn btn-primary home-cta-primary">
                    {t('home.cta_book', 'Book your event')} <FiArrowRight />
                  </Link>
                )
              ) : (
                <>
                  <Link to={hero.primaryCtaHref || '/register'} className="btn btn-primary home-cta-primary">
                    {hero.primaryCtaLabel} <FiArrowRight />
                  </Link>
                  <Link to={hero.secondaryCtaHref || '/login'} className="btn btn-secondary home-cta-secondary">
                    {hero.secondaryCtaLabel}
                  </Link>
                </>
              )}
            </div>
            <div className="home-proof-strip" aria-label="Experience highlights">
              {(proofStrip || []).map((p, i) => (
                <div key={i}>
                  <strong>{p.value}</strong>
                  <span>{p.label}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="home-hero-stage">
            <HeroCarousel images={gallery} />
          </div>
        </div>
      </section>

      <section className="home-marquee">
        <div className="container home-marquee-track">
          {(marquee || []).map((m, i) => <span key={i}>{m}</span>)}
        </div>
      </section>

      {bannerEnabled && (
        <section className="home-banner-section" aria-label="Featured experiences">
          <BigBannerCarousel slides={bannerCarousel.slides} autoplayMs={bannerCarousel.autoplayMs ?? 6000} />
        </section>
      )}

      <section className="home-section container">
        <div className="home-section-heading">
          <span className="home-section-kicker">{features.kicker}</span>
          <h2>{features.title}</h2>
          <p>{features.description}</p>
        </div>
        <div className="home-feature-grid">
          {(features.items || []).map((item, i) => (
            <article key={i} className="home-feature-card">
              <div className="home-feature-icon">{iconFor(item.icon)}</div>
              <h3>{item.title}</h3>
              <p>{item.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-signature-section">
        <div className="home-section-heading home-section-heading-tight">
          <span className="home-section-kicker">{signature.kicker}</span>
          <h2>{signature.title}</h2>
        </div>
        <div className="home-signature-grid">
          {(signature.items || []).map((moment, i) => (
            <article key={i} className="home-signature-card">
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
          <span className="home-section-kicker">{process.kicker}</span>
          <h2>{process.title}</h2>
        </div>
        <div className="home-process-grid">
          {(process.items || []).map((step, i) => (
            <article key={i} className="home-process-card">
              <span className="home-process-number">{step.number}</span>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-packages-section">
        <div className="home-section-heading">
          <span className="home-section-kicker">{packages.kicker}</span>
          <h2>{packages.title}</h2>
          <p>{packages.description}</p>
        </div>
        <div className="home-package-grid">
          {(packages.items || []).map((evt, i) => (
            <article key={i} className="home-package-card">
              <span className="home-package-icon">{iconFor(evt.icon)}</span>
              <h3>{evt.name}</h3>
              <p className="home-package-price">{t('home.starting_at', 'Starting at')} {evt.price}</p>
              <p className="home-package-note">{evt.note}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="home-section container home-final-cta">
        <div>
          <span className="home-section-kicker">{finalCta.kicker}</span>
          <h2>{finalCta.title}</h2>
          <p>{finalCta.description}</p>
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
              <Link to={hero.primaryCtaHref || '/register'} className="btn btn-primary">
                {hero.primaryCtaLabel} <FiArrowRight />
              </Link>
              <Link to={hero.secondaryCtaHref || '/login'} className="btn btn-secondary">
                {hero.secondaryCtaLabel}
              </Link>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
