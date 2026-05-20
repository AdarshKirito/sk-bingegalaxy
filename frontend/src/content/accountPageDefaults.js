// Default content for the customer Account Center page.
// Super-admin can override any of these fields via the CMS editor at
// /admin/account-page-editor.  The schema is intentionally flat so
// the editor UI stays simple — no nested objects except the FAQ and
// steps arrays.  Mirrors the "schema-on-read" pattern used by homeDefaults.js.

export const ACCOUNT_PAGE_CMS_SLUG = 'account-page';

export const defaultAccountPageContent = {
  // ── Contact / Support ──────────────────────────────────────────────────
  supportHours: '9:00 AM to 10:00 PM IST',
  cancellationPolicy:
    'Cancellation requests are easiest to resolve before the event date and before payment disputes start.',
  paymentHelpPolicy:
    'Payment help is available for pending, failed, and refund-follow-up scenarios.',

  // ── Help and Trust panel (customer Dashboard) ──────────────────────
  // Heading + 3 supporting bullet points shown beneath the dashboard hero.
  // Editable per-binge so each venue can tailor the reassurance copy.
  // The string "{hours}" inside any bullet is replaced at render time with
  // the active support hours.
  helpAndTrustHeading: 'Support is visible before anything goes wrong',
  helpAndTrustPoints: [
    'Payment, cancellation, and schedule questions are easiest to resolve before the booking date.',
    'WhatsApp support is the fastest route for booking changes during {hours}.',
    'Account preferences and celebration reminders now live in one dedicated account area.',
  ],

  // ── FAQ ────────────────────────────────────────────────────────────────
  faqs: [
    {
      question: 'Can I reschedule a booking?',
      answer:
        'Yes. Contact support with your booking reference as early as possible so the team can check availability and payment status.',
    },
    {
      question: 'What happens if my payment is pending?',
      answer:
        'Pending reservations stay visible in your control center. You can finish the payment from the booking card or the payments hub.',
    },
    {
      question: 'How do add-ons and guest count affect pricing?',
      answer:
        'Base package pricing comes from your event type, then add-ons and guest-based charges are layered on top before payment.',
    },
    {
      question: 'Where do I get help on the day of the event?',
      answer:
        'Use the support links in your account or booking cards. Your booking reference is the fastest way for the team to help you.',
    },
  ],

  // ── How it works steps ─────────────────────────────────────────────────
  howItWorksSteps: [
    'Choose the event style and venue that fits the occasion.',
    'Pick a slot, add guests and extras, then review the quote.',
    'Finish payment, receive confirmation, and arrive with your booking reference ready.',
  ],

  // ── Member offers / Benefits & retention ──────────────────────────────
  memberOffers: [
    {
      title: 'Member Benefits',
      description:
        'Unlock better planning support, faster payment follow-up, and curated offers once you start booking regularly.',
    },
    {
      title: 'Referral Offer',
      description:
        'Refer a friend and keep a private-event credit ready for the next celebration in your circle.',
    },
    {
      title: 'Birthday Surprise',
      description:
        'Save your birthday month so we can surface celebration-first offers before the date sneaks up on you.',
    },
    {
      title: 'Exclusive Pricing',
      description:
        'Your profile can carry standard, custom, or member pricing depending on the offers active on your account.',
    },
  ],
};

/**
 * Deep-merge a partial CMS document on top of the defaults so missing keys
 * always fall back gracefully.  Arrays in partial replace defaults wholesale
 * (same as homeDefaults.js mergeHomeContent — the editor owns the full list).
 */
export function mergeAccountPageContent(partial) {
  if (!partial || typeof partial !== 'object') return structuredClone(defaultAccountPageContent);
  return {
    supportHours:
      typeof partial.supportHours === 'string' && partial.supportHours.trim()
        ? partial.supportHours.trim()
        : defaultAccountPageContent.supportHours,
    cancellationPolicy:
      typeof partial.cancellationPolicy === 'string' && partial.cancellationPolicy.trim()
        ? partial.cancellationPolicy.trim()
        : defaultAccountPageContent.cancellationPolicy,
    paymentHelpPolicy:
      typeof partial.paymentHelpPolicy === 'string' && partial.paymentHelpPolicy.trim()
        ? partial.paymentHelpPolicy.trim()
        : defaultAccountPageContent.paymentHelpPolicy,
    faqs:
      Array.isArray(partial.faqs) && partial.faqs.length > 0
        ? partial.faqs
        : defaultAccountPageContent.faqs,
    howItWorksSteps:
      Array.isArray(partial.howItWorksSteps) && partial.howItWorksSteps.length > 0
        ? partial.howItWorksSteps
        : defaultAccountPageContent.howItWorksSteps,
    memberOffers:
      Array.isArray(partial.memberOffers) && partial.memberOffers.length > 0
        ? partial.memberOffers
        : defaultAccountPageContent.memberOffers,
    helpAndTrustHeading:
      typeof partial.helpAndTrustHeading === 'string' && partial.helpAndTrustHeading.trim()
        ? partial.helpAndTrustHeading.trim()
        : defaultAccountPageContent.helpAndTrustHeading,
    helpAndTrustPoints: (() => {
      if (!Array.isArray(partial.helpAndTrustPoints) || partial.helpAndTrustPoints.length === 0) {
        return defaultAccountPageContent.helpAndTrustPoints;
      }
      const cleaned = partial.helpAndTrustPoints
        .map((s) => (typeof s === 'string' ? s.trim() : ''))
        .filter(Boolean)
        .slice(0, 6); // cap so the panel stays readable
      // If every entry was blank, fall back to defaults so the panel never
      // renders empty.
      return cleaned.length > 0 ? cleaned : defaultAccountPageContent.helpAndTrustPoints;
    })(),
  };
}
