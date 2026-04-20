export const DASHBOARD_LAYOUT_OPTIONS = [
  { value: 'GRID', label: 'Grid cards' },
  { value: 'CAROUSEL', label: 'Carousel spotlight' },
];

export const DASHBOARD_THEME_OPTIONS = [
  { value: 'celebration', label: 'Celebration' },
  { value: 'romance', label: 'Romance' },
  { value: 'cinema', label: 'Cinema' },
  { value: 'team', label: 'Team' },
  { value: 'family', label: 'Family' },
  { value: 'luxury', label: 'Luxury' },
];

const DEFAULT_DASHBOARD_EXPERIENCE = Object.freeze({
  sectionEyebrow: 'Explore Experiences',
  sectionTitle: 'Pick a setup that matches the mood',
  sectionSubtitle: '',
  layout: 'GRID',
  slides: [],
});

const cleanString = (value) => (typeof value === 'string' ? value : '');

export function createDashboardSlide() {
  return {
    badge: '',
    headline: '',
    description: '',
    ctaLabel: '',
    imageUrl: '',
    theme: 'celebration',
    linkedEventTypeId: null,
  };
}

export function dashboardSlideHasContent(slide) {
  return ['badge', 'headline', 'description', 'ctaLabel'].some((field) => cleanString(slide?.[field]).trim()) || cleanString(slide?.imageUrl).trim();
}

export function normalizeDashboardExperience(config) {
  const slides = Array.isArray(config?.slides)
    ? config.slides.slice(0, 6).map((slide) => ({
        badge: cleanString(slide?.badge),
        headline: cleanString(slide?.headline),
        description: cleanString(slide?.description),
        ctaLabel: cleanString(slide?.ctaLabel),
        imageUrl: cleanString(slide?.imageUrl),
        theme: DASHBOARD_THEME_OPTIONS.some((option) => option.value === slide?.theme) ? slide.theme : 'celebration',
        linkedEventTypeId: slide?.linkedEventTypeId != null ? Number(slide.linkedEventTypeId) : null,
      }))
    : [];

  return {
    sectionEyebrow: cleanString(config?.sectionEyebrow).trim() || DEFAULT_DASHBOARD_EXPERIENCE.sectionEyebrow,
    sectionTitle: cleanString(config?.sectionTitle).trim() || DEFAULT_DASHBOARD_EXPERIENCE.sectionTitle,
    sectionSubtitle: cleanString(config?.sectionSubtitle).trim(),
    layout: config?.layout === 'CAROUSEL' ? 'CAROUSEL' : 'GRID',
    slides,
  };
}

export function sanitizeDashboardExperienceForSave(config) {
  const normalized = normalizeDashboardExperience(config);
  return {
    ...normalized,
    slides: normalized.slides.filter(dashboardSlideHasContent),
  };
}