"use strict";

/* =========================================================================
 * Velocity RGS - shared top chrome behaviour.
 *
 * The primary nav is a horizontal scroll strip on narrow screens (see .vx-nav
 * in styles.css), which means the current page's tab can start life off-screen:
 * "Reconciliation" is the fourth of four, so on a phone the Reconciliation page
 * would open showing no highlighted tab at all and no hint that one exists.
 *
 * This nudges the active tab into view on load. Only when the strip actually
 * scrolls - on a wide screen there is nothing to do and nothing is touched.
 * ======================================================================= */

(function revealActiveNavTab() {
  const run = () => {
    for (const nav of document.querySelectorAll(".vx-nav")) {
      const active = nav.querySelector("a.on");
      if (!active || nav.scrollWidth <= nav.clientWidth) continue;

      // Centre the tab in the strip rather than scrollIntoView()'s nearest-edge alignment, so the
      // neighbouring tabs stay half-visible and the strip still reads as scrollable.
      const target = active.offsetLeft - (nav.clientWidth - active.offsetWidth) / 2;
      // `auto` overrides the stylesheet's smooth scrolling: this is the page's initial state, not a
      // transition from somewhere, and animating it on load just looks like a glitch.
      nav.scrollTo({ left: Math.max(0, target), behavior: "auto" });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run, { once: true });
  } else {
    run();
  }
})();
