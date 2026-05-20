// Classify booking/payment API errors related to slot availability so the
// UI can recover gracefully (release stale holds, refresh slots, suggest
// alternatives, preserve form state, etc.).
//
// The backend uses BusinessException (mapped to HTTP 400 by default, 409 in
// some paths) with predictable English messages. When the backend later adds
// machine-readable `errorCode` values (e.g. SLOT_TAKEN, HOLD_EXPIRED) the
// classifier prefers those — the message regex is the safety net for today's
// payloads.
//
// Returns { kind, message } where `kind` is one of:
//   - 'SLOT_TAKEN'     another booking grabbed this slot between hold/submit
//   - 'HOLD_EXPIRED'   our slot hold timed out
//   - 'HOLD_INVALID'   our hold was released/consumed/mutated server-side
//   - 'ROOM_FULL'      requested room is full for this slot
//   - null             not a slot-availability conflict
//
// Callers should treat any non-null `kind` as "user must re-pick a slot" and
// should NOT discard event/add-ons/guest count/billing — only the date/time
// portion of the form is invalidated.

const PATTERNS = [
  {
    kind: 'HOLD_EXPIRED',
    re: /(slot hold|slot reservation)[^.]*expired|hold token[^.]*expired/i,
  },
  {
    kind: 'HOLD_INVALID',
    re: /slot hold (is no longer valid|does not belong|was modified|has already been)|booking details do not match your slot hold|hold token is required/i,
  },
  {
    kind: 'SLOT_TAKEN',
    re: /slot is not available|slot is no longer available|slot conflicts with an existing booking|cannot hold a slot in the past/i,
  },
  {
    kind: 'ROOM_FULL',
    re: /room[^.]*(is fully booked|fully booked for)/i,
  },
];

const FRIENDLY = {
  SLOT_TAKEN:    'This slot was just taken by another guest. Please pick another time.',
  HOLD_EXPIRED:  'Your slot reservation expired. Please pick another time to continue.',
  HOLD_INVALID:  'Your slot reservation is no longer valid. Please pick another time.',
  ROOM_FULL:     'The selected room is fully booked for this slot. Please pick another time or room.',
};

export function classifyBookingError(err) {
  if (!err) return { kind: null, message: '' };
  const status = err?.response?.status;
  const data = err?.response?.data || {};
  const rawCode = data.errorCode || data.code || '';
  const serverMsg = err?.userMessage || data.message || err?.message || '';

  // 1) Prefer backend errorCode when present.
  if (typeof rawCode === 'string' && rawCode) {
    const upper = rawCode.toUpperCase();
    if (upper === 'HOLD_EXPIRED' || upper === 'SLOT_HOLD_EXPIRED') {
      return { kind: 'HOLD_EXPIRED', message: serverMsg || FRIENDLY.HOLD_EXPIRED };
    }
    if (upper === 'HOLD_INVALID' || upper === 'SLOT_HOLD_INVALID' || upper === 'HOLD_NOT_FOUND') {
      return { kind: 'HOLD_INVALID', message: serverMsg || FRIENDLY.HOLD_INVALID };
    }
    if (upper === 'SLOT_TAKEN' || upper === 'SLOT_UNAVAILABLE' || upper === 'SLOT_CONFLICT') {
      return { kind: 'SLOT_TAKEN', message: serverMsg || FRIENDLY.SLOT_TAKEN };
    }
    if (upper === 'ROOM_FULL') {
      return { kind: 'ROOM_FULL', message: serverMsg || FRIENDLY.ROOM_FULL };
    }
  }

  // 2) Pattern-match the message.
  for (const p of PATTERNS) {
    if (p.re.test(serverMsg)) {
      return { kind: p.kind, message: serverMsg || FRIENDLY[p.kind] };
    }
  }

  // 3) HTTP 409 without a recognised pattern: most likely a slot conflict at
  // the booking-creation layer (the only 409 this domain emits during a
  // wizard submit). Idempotency 409s carry their own distinctive messages
  // and would have matched above if relevant.
  if (status === 409 && !/payment already|idempot/i.test(serverMsg)) {
    return { kind: 'SLOT_TAKEN', message: serverMsg || FRIENDLY.SLOT_TAKEN };
  }

  return { kind: null, message: serverMsg };
}

export function isBookingConflict(err) {
  return !!classifyBookingError(err).kind;
}

export const CONFLICT_FRIENDLY_MESSAGES = FRIENDLY;
