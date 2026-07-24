import { useMemo } from 'react';

/**
 * Itemised tax lines for a booking — the single shared renderer for the admin
 * reservation panel, the customer confirmation, and the printed receipt.
 *
 * Parses the booking's persisted `taxBreakdownJson` (array of
 * TaxComputationResult.TaxLine from the backend) so what's shown is exactly
 * what was charged, never a recomputation. Renders nothing when the booking
 * has no breakdown (taxes disabled for the binge, or a legacy booking).
 *
 * Each line explains WHAT the tax is: name, type (GST / OCCUPANCY / …),
 * jurisdiction (e.g. "US/IL/Chicago" — Cook County-style labelling), and how
 * it was computed (percentage, flat per reservation, or flat × hours).
 */

/** Parse a booking's taxBreakdownJson defensively. Returns [] when absent/bad. */
export function parseTaxBreakdown(taxBreakdownJson) {
  if (!taxBreakdownJson) return [];
  try {
    const arr = typeof taxBreakdownJson === 'string'
      ? JSON.parse(taxBreakdownJson)
      : taxBreakdownJson;
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

/** "18%" | "flat" | "flat × 2h" — how the line was computed. */
export function taxLineRateLabel(line) {
  if (line.calcMethod === 'FLAT_PER_HOUR') return `flat × ${line.units ?? 1}h`;
  if (line.calcMethod === 'FLAT_PER_BOOKING') return 'flat';
  const pct = Number(line.rateBps || 0) / 100;
  return `${pct % 1 === 0 ? pct.toFixed(0) : pct.toFixed(2)}%`;
}

export default function TaxBreakdown({ taxBreakdownJson, money, compact = false }) {
  const lines = useMemo(() => parseTaxBreakdown(taxBreakdownJson), [taxBreakdownJson]);
  if (lines.length === 0) return null;
  const fmt = money || ((v) => Number(v || 0).toFixed(2));

  return (
    <div className="tax-breakdown" style={{ fontSize: compact ? '0.8rem' : '0.88rem' }}>
      {lines.map((l, i) => (
        <div
          key={l.ruleId ?? i}
          style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '2px 0' }}
        >
          <span style={{ color: 'var(--text-secondary, #888)' }}>
            {l.name}
            {' '}
            <span style={{ opacity: 0.75 }}>({taxLineRateLabel(l)}</span>
            {l.taxType && l.taxType !== 'GENERIC' && (
              <span style={{ opacity: 0.75 }}> · {l.taxType}</span>
            )}
            {l.jurisdiction && l.jurisdiction !== 'GLOBAL' && (
              <span style={{ opacity: 0.75 }}> · {l.jurisdiction}</span>
            )}
            <span style={{ opacity: 0.75 }}>)</span>
            {l.inclusive && <span style={{ opacity: 0.6 }}> — included in price</span>}
          </span>
          <span style={{ whiteSpace: 'nowrap' }}>{fmt(l.amount)}</span>
        </div>
      ))}
    </div>
  );
}
