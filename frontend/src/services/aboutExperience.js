const DEFAULT_ABOUT_EXPERIENCE = Object.freeze({
  sectionEyebrow: 'Before You Book',
  sectionTitle: 'Know your binge before event day',
  sectionSubtitle: '',
  heroTitle: 'Everything customers should know, in one place',
  heroDescription: 'Set expectations clearly with venue highlights, house rules, and policies so guests walk in prepared and confident.',
  highlightsTitle: 'Why guests choose this binge',
  highlights: [
    {
      title: 'Private cinematic setup',
      description: 'Your booking includes a private room flow designed for your event mood and timing.',
    },
  ],
  houseRulesTitle: 'House rules',
  houseRules: [
    'Arrive at least 15 minutes before your slot to complete check-in smoothly.',
  ],
  policyTitle: 'Policies and regulations',
  policies: [
    {
      title: 'Payment policy',
      description: 'Bookings stay reserved based on the payment status shown in your booking and payments portal.',
    },
  ],
  contactHeading: 'Need help before your slot?',
  contactDescription: 'Use the support contacts listed for this binge and include your booking reference for quicker help.',
});

const cleanString = (value) => (typeof value === 'string' ? value : '');

export function createAboutHighlight() {
  return {
    title: '',
    description: '',
  };
}

export function createAboutPolicy() {
  return {
    title: '',
    description: '',
  };
}

export function normalizeAboutExperience(config) {
  const highlights = Array.isArray(config?.highlights)
    ? config.highlights.slice(0, 8).map((item) => ({
      title: cleanString(item?.title).trim(),
      description: cleanString(item?.description).trim(),
    })).filter((item) => item.title || item.description)
    : [];

  const houseRules = Array.isArray(config?.houseRules)
    ? config.houseRules.slice(0, 12)
      .map((item) => cleanString(item).trim())
      .filter(Boolean)
    : [];

  const policies = Array.isArray(config?.policies)
    ? config.policies.slice(0, 8).map((item) => ({
      title: cleanString(item?.title).trim(),
      description: cleanString(item?.description).trim(),
    })).filter((item) => item.title || item.description)
    : [];

  return {
    sectionEyebrow: cleanString(config?.sectionEyebrow).trim() || DEFAULT_ABOUT_EXPERIENCE.sectionEyebrow,
    sectionTitle: cleanString(config?.sectionTitle).trim() || DEFAULT_ABOUT_EXPERIENCE.sectionTitle,
    sectionSubtitle: cleanString(config?.sectionSubtitle).trim(),
    heroTitle: cleanString(config?.heroTitle).trim() || DEFAULT_ABOUT_EXPERIENCE.heroTitle,
    heroDescription: cleanString(config?.heroDescription).trim() || DEFAULT_ABOUT_EXPERIENCE.heroDescription,
    highlightsTitle: cleanString(config?.highlightsTitle).trim() || DEFAULT_ABOUT_EXPERIENCE.highlightsTitle,
    highlights: highlights.length > 0 ? highlights : DEFAULT_ABOUT_EXPERIENCE.highlights,
    houseRulesTitle: cleanString(config?.houseRulesTitle).trim() || DEFAULT_ABOUT_EXPERIENCE.houseRulesTitle,
    houseRules: houseRules.length > 0 ? houseRules : DEFAULT_ABOUT_EXPERIENCE.houseRules,
    policyTitle: cleanString(config?.policyTitle).trim() || DEFAULT_ABOUT_EXPERIENCE.policyTitle,
    policies: policies.length > 0 ? policies : DEFAULT_ABOUT_EXPERIENCE.policies,
    contactHeading: cleanString(config?.contactHeading).trim() || DEFAULT_ABOUT_EXPERIENCE.contactHeading,
    contactDescription: cleanString(config?.contactDescription).trim() || DEFAULT_ABOUT_EXPERIENCE.contactDescription,
  };
}

export function sanitizeAboutExperienceForSave(config) {
  const normalized = normalizeAboutExperience(config);
  return {
    ...normalized,
    highlights: normalized.highlights.filter((item) => item.title || item.description),
    houseRules: normalized.houseRules.filter(Boolean),
    policies: normalized.policies.filter((item) => item.title || item.description),
  };
}