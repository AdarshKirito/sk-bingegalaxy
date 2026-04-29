// Default content for the public landing page. The super-admin editor saves
// a JSON document with the same shape; if a key is missing in the saved doc
// we fall back to these defaults so the page never breaks. Mirrors the
// "schema-on-read" pattern Webflow / Strapi use for evolving CMS schemas.
export const HOME_CMS_SLUG = 'home';

export const defaultHomeContent = {
  hero: {
    kicker: 'Private theater experiences for occasions that should not feel generic',
    headline: 'Make the',
    headlineHighlight: 'first impression',
    headlineSuffix: 'feel bigger than the screen.',
    description: 'SK Binge Galaxy is the pre-login home for private screenings, birthday rooms, proposal setups, and premium event nights. When someone clicks the SK Binge title, this is the page they should land on.',
    primaryCtaLabel: 'Plan My Experience',
    primaryCtaHref: '/register',
    secondaryCtaLabel: 'Sign in',
    secondaryCtaHref: '/login',
  },
  proofStrip: [
    { value: '500+', label: 'private celebrations hosted' },
    { value: '3 steps', label: 'from plan to confirmed booking' },
    { value: '100%', label: 'exclusive room access for your slot' },
  ],
  // Carousel + grid on the landing page. URLs may be public CDN links or
  // base64 data URIs (super-admin paste / upload). caption is optional.
  gallery: [
    { url: 'https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?auto=format&fit=crop&w=1600&q=80', caption: 'Birthday signature setup' },
    { url: 'https://images.unsplash.com/photo-1542204625-ca960c179c08?auto=format&fit=crop&w=1600&q=80', caption: 'Proposal reveal lighting' },
    { url: 'https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?auto=format&fit=crop&w=1600&q=80', caption: 'Private screening room' },
    { url: 'https://images.unsplash.com/photo-1505236858219-8359eb29e329?auto=format&fit=crop&w=1600&q=80', caption: 'Anniversary theme' },
    { url: 'https://images.unsplash.com/photo-1485846234645-a62644f84728?auto=format&fit=crop&w=1600&q=80', caption: 'Cinematic interior' },
    { url: 'https://images.unsplash.com/photo-1527979809431-ea3d5c0c01c9?auto=format&fit=crop&w=1600&q=80', caption: 'Corporate event hall' },
  ],
  // Big Amazon-style hero banner shown right after the hero strip. Each
  // slide is a full-bleed photograph with overlay headline + CTA. Falls
  // back to the gallery URLs if the admin hasn't customised this yet.
  bannerCarousel: {
    enabled: true,
    autoplayMs: 6000,
    slides: [
      {
        url: 'https://images.unsplash.com/photo-1542204165-65bf26472b9b?auto=format&fit=crop&w=2400&q=85',
        kicker: 'Private screenings',
        title: 'A theatre that feels reserved for your night.',
        subtitle: 'Cinematic lighting, curated playlists, exclusive room access for your slot.',
        ctaLabel: 'Reserve a room',
        ctaHref: '/binges',
        align: 'left',
      },
      {
        url: 'https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?auto=format&fit=crop&w=2400&q=85',
        kicker: 'Birthday rooms',
        title: 'Make the candle-blow moment cinematic.',
        subtitle: 'Cake table staging, warm spotlights, your playlist on the big screen.',
        ctaLabel: 'Plan a birthday',
        ctaHref: '/register',
        align: 'left',
      },
      {
        url: 'https://images.unsplash.com/photo-1488376739360-f02d3a075e22?auto=format&fit=crop&w=2400&q=85',
        kicker: 'Proposal setups',
        title: 'A cinematic “yes” without the chaos.',
        subtitle: 'A controlled, intimate setting for the moment that has to land right.',
        ctaLabel: 'Design a proposal',
        ctaHref: '/register',
        align: 'left',
      },
      {
        url: 'https://images.unsplash.com/photo-1505236858219-8359eb29e329?auto=format&fit=crop&w=2400&q=85',
        kicker: 'Corporate nights',
        title: 'Premieres, launches, and team screenings.',
        subtitle: 'Polished private venue energy without the banquet-hall feel.',
        ctaLabel: 'Talk to concierge',
        ctaHref: '#contact',
        align: 'right',
      },
    ],
  },
  // Display mode for the secondary "Inside the rooms" section.
  // 'grid' | 'banner' | 'both' | 'off'.
  galleryDisplay: 'grid',
  marquee: ['Birthday Nights', 'Proposal Reveals', 'Anniversary Setups', 'Private Screenings', 'Corporate Shows', 'Family Celebrations'],
  features: {
    kicker: 'Why this home page should convert better',
    title: 'It sells the feeling before it asks for the login.',
    description: 'The public landing experience should make the venue feel cinematic, private, and premium before users ever see an auth form.',
    items: [
      { title: 'Curated Private Screenings', description: 'A room that feels reserved for your people, your playlist, your lights, and your moment.', icon: 'film' },
      { title: 'Fast Celebration Setup', description: 'Dates, add-ons, timing, and event planning get shaped into one clean booking flow.', icon: 'calendar' },
      { title: 'Safer Payments', description: 'Checkout and callback handling are built for a controlled, verifiable payment path.', icon: 'shield' },
      { title: 'Photo-Ready Spaces', description: 'The room is designed to feel cinematic before the movie even starts.', icon: 'camera' },
    ],
  },
  signature: {
    kicker: 'Signature moments',
    title: 'Different moods, same private-screen advantage.',
    items: [
      { eyebrow: 'Birthday rooms', title: 'Celebrate with a full-screen surprise', description: 'Cake table, private seating, your playlist, and a clean reveal moment without crowd noise.', accent: 'Sunrise Gold' },
      { eyebrow: 'Proposal setups', title: 'A cinematic yes without the chaos', description: 'Build a controlled, intimate setting with lighting, timing, and a sharper emotional reveal.', accent: 'Rose Velvet' },
      { eyebrow: 'Corporate screenings', title: 'Present without a banquet-hall feel', description: 'Use the theater like a polished private venue for launches, team events, or premium client sessions.', accent: 'Midnight Slate' },
    ],
  },
  process: {
    kicker: 'Simple booking rhythm',
    title: 'Plan the night without getting lost in the UI.',
    items: [
      { number: '01', title: 'Pick the mood', description: 'Birthday, proposal, anniversary, surprise date, or a private movie night with friends.' },
      { number: '02', title: 'Lock the slot', description: 'Choose your venue, date, show window, guest count, and add-ons in one flow.' },
      { number: '03', title: 'Walk into a finished setup', description: 'Get the confirmation, arrive with your booking reference, and let the room do the work.' },
    ],
  },
  packages: {
    kicker: 'Indicative packages',
    title: 'Built for occasions, not just ticket sales.',
    description: 'Starting prices below are directional. Final pricing should still reflect slot, setup depth, and add-ons.',
    items: [
      { name: 'Birthday Party', price: '₹4,999', icon: 'gift', note: 'Cake-first setup with celebration framing.' },
      { name: 'Anniversary', price: '₹5,999', icon: 'heart', note: 'Soft lighting, private seating, cleaner atmosphere.' },
      { name: 'Proposal Setup', price: '₹7,999', icon: 'star', note: 'A high-focus room built for one main reveal.' },
      { name: 'HD Screening', price: '₹2,999', icon: 'film', note: 'Straight private screening without event extras.' },
      { name: 'Corporate Event', price: '₹9,999', icon: 'briefcase', note: 'Presentations and screenings without banquet noise.' },
      { name: 'Baby Shower', price: '₹5,499', icon: 'smile', note: 'Comfort-first staging for smaller private groups.' },
    ],
  },
  finalCta: {
    kicker: 'Ready when you are',
    title: 'Click the SK Binge title anytime and this should still feel like the right front door.',
    description: 'It now works as a proper public homepage before login, while still handing authenticated users off to booking or admin actions.',
  },
};

// Deep-merge helper — fills missing keys in `partial` from `defaults`. Arrays
// in `partial` replace defaults wholesale (so the admin can re-order).
export function mergeHomeContent(partial) {
  if (!partial || typeof partial !== 'object') return defaultHomeContent;
  const merge = (def, val) => {
    if (Array.isArray(def)) return Array.isArray(val) ? val : def;
    if (def && typeof def === 'object') {
      const out = {};
      for (const k of Object.keys(def)) out[k] = merge(def[k], val ? val[k] : undefined);
      // Preserve any extra keys the admin added.
      if (val && typeof val === 'object') {
        for (const k of Object.keys(val)) if (!(k in out)) out[k] = val[k];
      }
      return out;
    }
    return val !== undefined && val !== null ? val : def;
  };
  return merge(defaultHomeContent, partial);
}
