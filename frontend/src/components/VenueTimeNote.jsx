import { FiClock, FiGlobe } from 'react-icons/fi';
import { venueZoneLabel, viewerLocalEquivalent } from '../utils/format';
import './VenueTimeNote.css';

/**
 * A small, unobtrusive note that makes venue-local booking times unambiguous.
 *
 * - Always states the venue's timezone (e.g. "Venue local time · IST (GMT+5:30)").
 * - When a specific booking date+time is supplied AND the viewer's browser is in a
 *   different timezone, it also shows the equivalent in the viewer's own local time
 *   ("≈ Jul 2, 11:30 PM your time") — directly fixing the "I booked from the US but
 *   it showed Kolkata time" confusion.
 *
 * Fails soft: renders nothing without a timezone; drops the local-equivalent hint
 * silently if it can't be computed.
 */
export default function VenueTimeNote({ timezone, date, time, variant = 'inline', className = '' }) {
  if (!timezone) return null;

  const zoneLabel = venueZoneLabel(timezone);
  const localEq = date && time ? viewerLocalEquivalent(date, time, timezone) : null;

  return (
    <span className={`venue-time-note venue-time-note--${variant} ${className}`.trim()}>
      <FiGlobe aria-hidden className="venue-time-note__icon" />
      <span>
        Venue local time · <strong>{zoneLabel}</strong>
        {localEq && (
          <span className="venue-time-note__local">
            {' '}· <FiClock aria-hidden className="venue-time-note__icon" /> ≈ {localEq} your time
          </span>
        )}
      </span>
    </span>
  );
}
