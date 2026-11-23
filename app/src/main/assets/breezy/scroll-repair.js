// Breezy scroll-lock repair. Injected at document start into the top frame, independent of
// shields. Consent dialogs, paywalls and "disable your adblocker" walls lock scrolling with
// overflow:hidden / position:fixed on <html> or <body>. If the dialog never renders (or was
// dismissed but the lock stayed), the page is frozen. When the page is locked but nothing large
// is visibly covering the viewport, release the lock.
(function () {
  'use strict';
  if (window.top !== window || window.__breezyScroll) return;
  Object.defineProperty(window, '__breezyScroll', { value: true });
  // --- Scroll-lock repair ----------------------------------------------------------------------
  // Consent dialogs and "disable your adblocker" walls lock scrolling (overflow:hidden on html or
  // body). When our rules hide the dialog, the lock stays and the page appears frozen. If the page
  // is locked but nothing large is actually covering the viewport, release the lock.
    var coversViewport = function () {
      var w = innerWidth, h = innerHeight;
      var pts = [[w / 2, h / 2], [w / 2, h / 4], [w / 2, h * 0.75]];
      for (var p = 0; p < pts.length; p++) {
        var el = document.elementFromPoint(pts[p][0], pts[p][1]);
        for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
          var cs = getComputedStyle(el);
          if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
          var r = el.getBoundingClientRect();
          if (r.width * r.height > w * h * 0.5 && cs.visibility !== 'hidden' && cs.opacity !== '0') return true;
        }
      }
      return false;
    };
    var isLocked = function (el) {
      if (!el) return false;
      var cs = getComputedStyle(el);
      return cs.overflowY === 'hidden' || cs.overflowY === 'clip';
    };
    var contentTaller = function () {
      var b = document.body;
      return b && Math.max(b.scrollHeight, document.documentElement.scrollHeight) > innerHeight + 40;
    };
    var unlock = function () {
      var html = document.documentElement, body = document.body;
      if (!body || !contentTaller()) return;
      if (!isLocked(html) && !isLocked(body)) return;
      if (coversViewport()) return; // a real dialog/menu is open; leave it alone
      [html, body].forEach(function (el) {
        if (isLocked(el)) el.style.setProperty('overflow-y', 'auto', 'important');
      });
      if (getComputedStyle(body).position === 'fixed' && !coversViewport()) {
        body.style.setProperty('position', 'static', 'important');
      }
    };
    var checks = 0;
    var scheduleUnlock = function () {
      if (checks > 20) return;
      checks++;
      setTimeout(unlock, 400);
    };
    addEventListener('load', scheduleUnlock);
    [1500, 3000, 6000].forEach(function (t) { setTimeout(unlock, t); });
    // Re-check briefly whenever the page toggles classes/styles on html/body (dialog scripts).
    var watchLocks = function () {
      [document.documentElement, document.body].forEach(function (el) {
        if (el) new MutationObserver(scheduleUnlock).observe(el, { attributes: true, attributeFilter: ['class', 'style'] });
      });
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchLocks, { once: true });
    else watchLocks();
})();
