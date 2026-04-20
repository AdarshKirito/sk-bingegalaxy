/**
 * NavOverflowBar — "Priority+" navigation pattern
 *
 * Renders nav links in a horizontal row. When the bar is too narrow to show
 * all items (any language, any screen width), the items that don't fit are
 * collapsed into a "More ▾" dropdown at the right end.
 *
 * Used by: GitHub, Linear, Google, Microsoft Fluent, Stripe Dashboard.
 *
 * Measurement strategy: a hidden off-screen "ghost" container always renders
 * every item at full natural size, so we can measure widths accurately even
 * after some items have been moved to the dropdown (where they'd measure 0).
 */
import { useState, useRef, useLayoutEffect, useEffect } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { FiMoreHorizontal, FiChevronDown } from 'react-icons/fi';

const GAP = 6; // px between items — matches CSS gap: 0.375rem ≈ 6px

export default function NavOverflowBar({ links, className = '' }) {
  const barRef        = useRef(null);
  const moreRef       = useRef(null);
  const ghostRefs     = useRef([]);   // refs for off-screen measurement items
  const [itemWidths,  setItemWidths]  = useState([]);
  const [barWidth,    setBarWidth]    = useState(Infinity);
  const [moreOpen,    setMoreOpen]    = useState(false);
  const location = useLocation();

  // ── Close dropdown on navigation ────────────────────────────────────
  useEffect(() => { setMoreOpen(false); }, [location.pathname]);

  // ── Close on click outside ───────────────────────────────────────────
  useEffect(() => {
    if (!moreOpen) return;
    const onDown = (e) => {
      if (!moreRef.current?.contains(e.target)) setMoreOpen(false);
    };
    document.addEventListener('pointerdown', onDown, true);
    return () => document.removeEventListener('pointerdown', onDown, true);
  }, [moreOpen]);

  // ── Measure ghost item widths whenever links change (incl. i18n) ────
  useLayoutEffect(() => {
    const widths = ghostRefs.current.map(el => (el ? el.getBoundingClientRect().width : 0));
    setItemWidths(widths);
  }, [links]);

  // ── Track bar width with ResizeObserver ──────────────────────────────
  useLayoutEffect(() => {
    const bar = barRef.current;
    if (!bar) return;
    const update = () => setBarWidth(bar.clientWidth);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(bar);
    return () => ro.disconnect();
  }, []);

  // ── Compute overflowStart ────────────────────────────────────────────
  // overflowStart = index after which items go into the "More" dropdown.
  // Default to links.length (all visible) until we have measurements.
  let overflowStart = links.length;

  if (itemWidths.length === links.length && barWidth < Infinity) {
    const totalW = itemWidths.reduce((s, w) => s + w + GAP, -GAP);

    if (totalW > barWidth) {
      // Need the "More" button — calculate its width from the DOM or use fallback
      const moreBtnW = (moreRef.current?.getBoundingClientRect().width ?? 88) + GAP;
      let used = moreBtnW;
      overflowStart = 0;

      for (let i = 0; i < itemWidths.length; i++) {
        const next = used + itemWidths[i] + GAP;
        if (next <= barWidth) {
          used = next;
          overflowStart = i + 1;
        } else {
          break;
        }
      }
    }
  }

  const hasOverflow = overflowStart < links.length;

  // ── Render ────────────────────────────────────────────────────────────
  return (
    <>
      {/*
        Ghost measurement container
        • position:fixed + far-left keeps it off-screen and out of layout
        • aria-hidden + pointer-events:none means it's invisible to users
        • All items render here at FULL natural size for accurate width reads
      */}
      <div
        aria-hidden="true"
        style={{
          position: 'fixed',
          top: 0,
          left: '-9999px',
          display: 'flex',
          gap: GAP + 'px',
          pointerEvents: 'none',
          visibility: 'hidden',
          whiteSpace: 'nowrap',
          zIndex: -1,
        }}
      >
        {links.map((link, i) => (
          <div
            key={link.to}
            ref={el => (ghostRefs.current[i] = el)}
            className="nav-link"
            style={{ flexShrink: 0 }}
          >
            {link.icon}
            <span>{link.label}</span>
          </div>
        ))}
      </div>

      {/* Actual visible bar */}
      <div ref={barRef} className={`nav-overflow-bar${className ? ' ' + className : ''}`}>

        {/* Visible items */}
        {links.slice(0, overflowStart).map(link => (
          <NavLink
            key={link.to}
            to={link.to}
            className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}
            onClick={() => setMoreOpen(false)}
          >
            {link.icon}
            <span>{link.label}</span>
          </NavLink>
        ))}

        {/* More button + dropdown */}
        <div
          ref={moreRef}
          className={`nav-more-wrapper${hasOverflow ? '' : ' nav-more-hidden'}`}
          aria-hidden={!hasOverflow}
        >
          <button
            className={`nav-link nav-more-trigger${moreOpen ? ' nav-link-active' : ''}`}
            onClick={() => setMoreOpen(o => !o)}
            aria-haspopup="true"
            aria-expanded={moreOpen}
            tabIndex={hasOverflow ? 0 : -1}
          >
            <FiMoreHorizontal />
            <span>More</span>
            <FiChevronDown className={`nav-more-chevron${moreOpen ? ' nav-more-chevron-open' : ''}`} />
          </button>

          {moreOpen && (
            <div className="nav-more-dropdown" role="menu">
              {links.slice(overflowStart).map(link => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  className={({ isActive }) =>
                    `nav-more-item${isActive ? ' nav-more-item-active' : ''}`
                  }
                  role="menuitem"
                  onClick={() => setMoreOpen(false)}
                >
                  {link.icon}
                  <span>{link.label}</span>
                </NavLink>
              ))}
            </div>
          )}
        </div>
      </div>
    </>
  );
}
