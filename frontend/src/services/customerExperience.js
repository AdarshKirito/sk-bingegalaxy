export const CUSTOMER_SUPPORT = {
  email: 'admin@skbingegalaxy.com',
  phoneDisplay: '+91 98765 43210',
  phoneRaw: '+919876543210',
  whatsappRaw: '919876543210',
  hours: '9:00 AM to 10:00 PM IST',
};

export const MEMBER_OFFERS = [
  {
    title: 'Member Benefits',
    description: 'Unlock better planning support, faster payment follow-up, and curated offers once you start booking regularly.',
  },
  {
    title: 'Referral Offer',
    description: 'Refer a friend and keep a private-event credit ready for the next celebration in your circle.',
  },
  {
    title: 'Birthday Surprise',
    description: 'Save your birthday month so we can surface celebration-first offers before the date sneaks up on you.',
  },
  {
    title: 'Exclusive Pricing',
    description: 'Your profile can carry standard, custom, or member pricing depending on the offers active on your account.',
  },
];

export const HELP_FAQS = [
  {
    question: 'Can I reschedule a booking?',
    answer: 'Yes. Contact support with your booking reference as early as possible so the team can check availability and payment status.',
  },
  {
    question: 'What happens if my payment is pending?',
    answer: 'Pending reservations stay visible in your control center. You can finish the payment from the booking card or the payments hub.',
  },
  {
    question: 'How do add-ons and guest count affect pricing?',
    answer: 'Base package pricing comes from your event type, then add-ons and guest-based charges are layered on top before payment.',
  },
  {
    question: 'Where do I get help on the day of the event?',
    answer: 'Use the support links in your account or booking cards. Your booking reference is the fastest way for the team to help you.',
  },
];

export const EXPERIENCE_STEPS = [
  'Choose the event style and venue that fits the occasion.',
  'Pick a slot, add guests and extras, then review the quote.',
  'Finish payment, receive confirmation, and arrive with your booking reference ready.',
];

export const ACCOUNT_PREFERENCES_DEFAULTS = {
  preferredExperience: 'Birthday celebration',
  vibePreference: 'Warm and celebratory',
  reminderLeadDays: '14',
  birthdayMonth: '',
  anniversaryMonth: '',
  notificationChannel: 'WHATSAPP',
  receivesOffers: true,
  weekendAlerts: true,
  conciergeSupport: true,
};

const ACCOUNT_PREFERENCES_STORAGE_KEY = 'skbg.customer.account.preferences';

export function getMemberTier(completedBookings = 0, totalSpend = 0) {
  if (completedBookings >= 8 || totalSpend >= 40000) {
    return 'Galaxy Circle';
  }
  if (completedBookings >= 4 || totalSpend >= 18000) {
    return 'Spotlight Member';
  }
  return 'Private Guest';
}

export function loadAccountPreferences(userId) {
  if (typeof window === 'undefined') {
    return { ...ACCOUNT_PREFERENCES_DEFAULTS };
  }

  try {
    const raw = window.localStorage.getItem(ACCOUNT_PREFERENCES_STORAGE_KEY);
    const allPreferences = raw ? JSON.parse(raw) : {};
    return {
      ...ACCOUNT_PREFERENCES_DEFAULTS,
      ...(allPreferences[userId] || {}),
    };
  } catch (error) {
    return { ...ACCOUNT_PREFERENCES_DEFAULTS };
  }
}

export function saveAccountPreferences(userId, preferences) {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    const raw = window.localStorage.getItem(ACCOUNT_PREFERENCES_STORAGE_KEY);
    const allPreferences = raw ? JSON.parse(raw) : {};
    allPreferences[userId] = preferences;
    window.localStorage.setItem(ACCOUNT_PREFERENCES_STORAGE_KEY, JSON.stringify(allPreferences));
  } catch (error) {
    // Ignore localStorage errors so the page still works.
  }
}

export function buildSupportEmailHref({ bookingRef, customerName, topic = 'Booking support' } = {}) {
  const subject = encodeURIComponent(bookingRef ? `${topic} - ${bookingRef}` : topic);
  const body = encodeURIComponent([
    `Hello SK Binge Galaxy team,`,
    '',
    bookingRef ? `Booking reference: ${bookingRef}` : 'Booking reference: not yet available',
    customerName ? `Customer name: ${customerName}` : '',
    '',
    'I need help with:',
    '- ',
  ].filter(Boolean).join('\n'));

  return `mailto:${CUSTOMER_SUPPORT.email}?subject=${subject}&body=${body}`;
}

export function buildSupportWhatsAppHref({ bookingRef, customerName, topic = 'booking support' } = {}) {
  const message = encodeURIComponent([
    'Hello SK Binge Galaxy team,',
    bookingRef ? `Booking reference: ${bookingRef}` : 'Booking reference: not yet available',
    customerName ? `Customer name: ${customerName}` : '',
    `I need help with ${topic}.`,
  ].filter(Boolean).join('\n'));

  return `https://wa.me/${CUSTOMER_SUPPORT.whatsappRaw}?text=${message}`;
}

export function getCallSupportHref() {
  return `tel:${CUSTOMER_SUPPORT.phoneRaw}`;
}

export function downloadBookingSummary(booking, { customerName, venueName } = {}) {
  if (typeof window === 'undefined' || !booking) {
    return;
  }

  const addOnLines = (booking.addOns || []).map((addOn) => {
    const name = addOn.name || addOn.addOnName || 'Add-on';
    return `- ${name} x${addOn.quantity || 1}`;
  });
  const content = [
    'SK Binge Galaxy Booking Summary',
    '===============================',
    `Booking reference: ${booking.bookingRef}`,
    `Customer: ${customerName || booking.customerName || 'Guest'}`,
    venueName ? `Venue: ${venueName}` : null,
    `Event: ${booking.eventType?.name || booking.eventType || 'Private experience'}`,
    `Date: ${booking.bookingDate || 'TBD'}`,
    `Start time: ${booking.startTime || 'TBD'}`,
    `Guests: ${booking.numberOfGuests || 1}`,
    `Status: ${booking.status || 'PENDING'}`,
    `Payment status: ${booking.paymentStatus || 'PENDING'}`,
    `Payment method: ${booking.paymentMethod || 'To be decided'}`,
    `Total amount: Rs ${Number(booking.totalAmount || 0).toLocaleString()}`,
    '',
    'Add-ons',
    '-------',
    addOnLines.length > 0 ? addOnLines.join('\n') : 'No add-ons selected.',
    '',
    'Notes',
    '-----',
    booking.specialNotes || 'No special notes added.',
  ].filter(Boolean).join('\n');

  const file = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = window.URL.createObjectURL(file);
  const link = window.document.createElement('a');
  link.href = url;
  link.download = `${booking.bookingRef || 'booking-summary'}.txt`;
  window.document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}