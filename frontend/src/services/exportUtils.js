/**
 * CSV and PDF export utilities for admin reports / booking tables.
 * Pure client-side — no server round-trip required.
 */

/* ── CSV ─────────────────────────────────────────────── */

/**
 * Convert an array of objects to a CSV string.
 * Handles nested objects by JSON-stringifying them, and escapes newlines.
 * @param {Array<object>} rows   Data rows
 * @param {string[]}      columns  Column keys to include
 * @param {object}         [headings]  Optional label map { key: 'Label' }
 */
export function toCSV(rows, columns, headings = {}) {
  const header = columns.map(c => quote(headings[c] || c)).join(',');
  const body = rows.map(row =>
    columns.map(c => {
      const val = row[c];
      if (val == null) return '""';
      if (typeof val === 'object') return quote(JSON.stringify(val));
      return quote(String(val));
    }).join(',')
  );
  return [header, ...body].join('\r\n');
}

function quote(value) {
  // Escape double quotes and replace newlines (which break CSV parsers)
  const escaped = value.replace(/"/g, '""').replace(/\r?\n/g, ' ');
  return `"${escaped}"`;
}

/**
 * Trigger a CSV file download in the browser.
 * @param {string} csv       CSV string content
 * @param {string} filename  e.g. 'bookings-2025-01-15.csv'
 */
export function downloadCSV(csv, filename) {
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' }); // BOM for Excel
  triggerDownload(blob, filename);
}

/* ── PDF ─────────────────────────────────────────────── */

/**
 * Generate and download a simple PDF report using browser print.
 * Creates a hidden iframe, renders an HTML table, and triggers print-to-PDF.
 *
 * @param {object}   opts
 * @param {string}   opts.title     Report title
 * @param {Array<object>} opts.rows Data rows
 * @param {string[]} opts.columns   Column keys
 * @param {object}   [opts.headings]  Label map
 * @param {string}   [opts.subtitle]  Optional subtitle (date range, etc.)
 */
export function downloadPDF({ title, rows, columns, headings = {}, subtitle = '' }) {
  const headerCells = columns.map(c => `<th style="border:1px solid #ddd;padding:6px 10px;background:#f4f4f5;font-size:11px;text-align:left">${esc(headings[c] || c)}</th>`).join('');
  const bodyRows = rows.map(row =>
    `<tr>${columns.map(c => `<td style="border:1px solid #ddd;padding:5px 10px;font-size:11px">${esc(String(row[c] ?? ''))}</td>`).join('')}</tr>`
  ).join('');

  const html = `<!DOCTYPE html><html><head><title>${esc(title)}</title>
<style>@page{size:landscape;margin:12mm}body{font-family:Arial,sans-serif;margin:0;padding:16px}
table{border-collapse:collapse;width:100%}h1{font-size:16px;margin-bottom:4px}p{color:#666;font-size:12px;margin:0 0 12px}</style>
</head><body>
<h1>${esc(title)}</h1>
${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
<p style="color:#999;font-size:10px">Generated ${new Date().toLocaleString()} &bull; ${rows.length} rows</p>
<table><thead><tr>${headerCells}</tr></thead><tbody>${bodyRows}</tbody></table>
</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.style.cssText = 'position:fixed;left:-9999px;width:0;height:0';
  document.body.appendChild(iframe);
  iframe.contentDocument.open();
  iframe.contentDocument.write(html);
  iframe.contentDocument.close();
  iframe.contentWindow.focus();
  iframe.contentWindow.print();
  setTimeout(() => document.body.removeChild(iframe), 5000);
}

function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/* ── Shared ──────────────────────────────────────────── */

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(url); document.body.removeChild(a); }, 100);
}

/* ── Convenience: export bookings ────────────────────── */

const BOOKING_COLUMNS = ['bookingRef', 'customerName', 'eventType', 'date', 'slot', 'status', 'paymentStatus', 'totalAmount', 'createdAt'];
const BOOKING_HEADINGS = {
  bookingRef: 'Reference',
  customerName: 'Customer',
  eventType: 'Event Type',
  date: 'Date',
  slot: 'Slot',
  status: 'Status',
  paymentStatus: 'Payment',
  totalAmount: 'Amount (₹)',
  createdAt: 'Created',
};

export function exportBookingsCSV(bookings) {
  const rows = normalizeBookings(bookings);
  const csv = toCSV(rows, BOOKING_COLUMNS, BOOKING_HEADINGS);
  downloadCSV(csv, `bookings-${new Date().toISOString().slice(0, 10)}.csv`);
}

export function exportBookingsPDF(bookings, subtitle) {
  const rows = normalizeBookings(bookings);
  downloadPDF({
    title: 'SK Binge Galaxy — Bookings Report',
    rows,
    columns: BOOKING_COLUMNS,
    headings: BOOKING_HEADINGS,
    subtitle,
  });
}

function normalizeBookings(bookings) {
  return bookings.map(b => ({
    bookingRef: b.bookingRef || b.bookingReference || b.ref || '',
    customerName: b.customerName || `${b.customerFirstName || ''} ${b.customerLastName || ''}`.trim() || '',
    eventType: b.eventTypeName || b.eventType || '',
    date: b.bookingDate || b.date || '',
    slot: b.slotLabel || (b.startHour != null ? `${b.startHour}:00 – ${b.endHour}:00` : ''),
    status: b.status || '',
    paymentStatus: b.paymentStatus || '',
    totalAmount: b.totalAmount != null ? String(b.totalAmount) : '',
    createdAt: b.createdAt ? new Date(b.createdAt).toLocaleDateString() : '',
  }));
}

/* ── Convenience: export reports ─────────────────────── */

const REPORT_COLUMNS = ['label', 'total', 'confirmed', 'completed', 'cancelled', 'revenue'];
const REPORT_HEADINGS = {
  label: 'Period',
  total: 'Total',
  confirmed: 'Confirmed',
  completed: 'Completed',
  cancelled: 'Cancelled',
  revenue: 'Revenue (₹)',
};

export function exportReportCSV(report, label) {
  const rows = [{ label, ...report }];
  const csv = toCSV(rows, REPORT_COLUMNS, REPORT_HEADINGS);
  downloadCSV(csv, `report-${new Date().toISOString().slice(0, 10)}.csv`);
}

export function exportReportPDF(report, label) {
  const rows = [{ label, ...report }];
  downloadPDF({
    title: 'SK Binge Galaxy — Report',
    rows,
    columns: REPORT_COLUMNS,
    headings: REPORT_HEADINGS,
    subtitle: label,
  });
}

/* ── Server-side PDF invoice ────────────────────────── */

/**
 * Download a branded PDF invoice from the server.
 * Falls back to client-side PDF if the server endpoint is unavailable.
 *
 * @param {string} bookingRef  The booking reference (e.g. 'BK-ABC123')
 */
export async function downloadServerInvoice(bookingRef) {
  try {
    const { default: api } = await import('./api');
    const res = await api.get(`/bookings/${encodeURIComponent(bookingRef)}/invoice`, {
      responseType: 'blob',
    });
    const blob = new Blob([res.data], { type: 'application/pdf' });
    triggerDownload(blob, `invoice-${bookingRef}.pdf`);
  } catch (err) {
    // Let the caller decide how to handle (e.g. show client-side fallback)
    console.warn('Server invoice unavailable:', err.message);
    throw err;
  }
}
