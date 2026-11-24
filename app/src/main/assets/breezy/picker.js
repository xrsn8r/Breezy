// Breezy element picker. Injected into the top frame on demand. Swipe to scroll, tap to select.
// Reports the current selection to native via BreezyHost.postMessage({t:'pick', ...}):
//   { selector, count, label, canChild, canParent }
(function () {
  'use strict';
  if (window.__breezyPicker) { window.__breezyPicker.stop(); }
  var native = window.BreezyHost;
  var Z = 2147483647;

  // --- Overlay: captures touches so the page doesn't react while picking -------------------------
  var shield = document.createElement('div');
  shield.setAttribute('data-breezy-picker', '');
  shield.style.cssText = 'position:fixed;inset:0;z-index:' + Z + ';background:transparent;touch-action:none;';
  var box = document.createElement('div');
  box.setAttribute('data-breezy-picker', '');
  box.style.cssText = 'position:fixed;z-index:' + (Z - 1) + ';pointer-events:none;border:2px solid #ff5a5f;' +
    'background:rgba(255,90,95,0.18);border-radius:6px;transition:all .12s ease;display:none;';
  var preview = document.createElement('style');
  preview.setAttribute('data-breezy-picker', '');
  (document.documentElement).appendChild(preview);
  (document.body || document.documentElement).appendChild(box);
  (document.body || document.documentElement).appendChild(shield);

  var target = null, trail = [], selector = '', previewing = false;

  function isOurs(el) { return el && el.hasAttribute && el.hasAttribute('data-breezy-picker'); }

  function elementAt(x, y) {
    shield.style.pointerEvents = 'none';
    var list = document.elementsFromPoint(x, y);
    shield.style.pointerEvents = 'auto';
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!isOurs(el) && el !== document.documentElement && el !== document.body) return el;
    }
    return null;
  }

  // --- Selector generation -------------------------------------------------------------------------
  function esc(s) { return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/([^\w-])/g, '\\$1'); }

  // Skip ids/classes that look generated (hashes, long numbers) - they change between page loads.
  function stable(name) {
    if (!name || name.length > 40) return false;
    if (/\d{3,}/.test(name)) return false;
    if (/^(css|sc|jsx|emotion|styled)-/i.test(name)) return false;
    // Any hyphen/underscore segment that mixes letters and digits (x9k2j3, 1q2w3e4) is a hash.
    var segs = name.split(/[-_]+/);
    for (var i = 0; i < segs.length; i++) {
      if (segs[i].length >= 5 && /\d/.test(segs[i]) && /[a-z]/i.test(segs[i])) return false;
    }
    if (/[A-Z].*[A-Z].*[A-Z]/.test(name) && /\d/.test(name)) return false;
    if (/^(active|selected|hover|focus|open|visible|show|hidden|is-|has-)/.test(name)) return false;
    return true;
  }

  function count(sel) {
    try { return document.querySelectorAll(sel).length; } catch (e) { return 0; }
  }

  function part(el) {
    var tag = el.tagName.toLowerCase();
    if (el.id && stable(el.id)) return '#' + esc(el.id);
    var classes = [];
    if (typeof el.className === 'string') {
      el.className.trim().split(/\s+/).forEach(function (c) { if (stable(c)) classes.push('.' + esc(c)); });
    }
    if (classes.length) return tag + classes.slice(0, 3).join('');
    var attrs = ['data-testid', 'data-test', 'data-ad', 'data-ad-slot', 'aria-label', 'role', 'name'];
    for (var i = 0; i < attrs.length; i++) {
      var v = el.getAttribute(attrs[i]);
      if (v && v.length < 60 && !/\d{4,}/.test(v)) return tag + '[' + attrs[i] + '="' + v.replace(/"/g, '\\"') + '"]';
    }
    return tag;
  }

  function nth(el) {
    var tag = el.tagName, i = 1;
    for (var s = el.previousElementSibling; s; s = s.previousElementSibling) if (s.tagName === tag) i++;
    return ':nth-of-type(' + i + ')';
  }

  // Shortest selector that still matches `el`; prefer ones that match few elements.
  function build(el) {
    var tag = el.tagName.toLowerCase();
    var p = part(el);
    if (p.charAt(0) === '#' && count(p) === 1) return p;
    if (p !== tag && count(p) > 0 && count(p) <= 3) return p;
    // Pass 1: ancestor chain of semantic parts. Pass 2: pin each level by position.
    for (var pass = 0; pass < 2; pass++) {
      var path = [], cur = el;
      for (var depth = 0; depth < 6 && cur && cur !== document.body && cur !== document.documentElement; depth++) {
        var q = part(cur);
        if (pass === 1 && q.charAt(0) !== '#') q += nth(cur);
        path.unshift(q);
        var sel = path.join(' > ');
        if (count(sel) === 1) return sel;
        if (q.charAt(0) === '#') break; // an id anchors the chain; going higher won't help
        cur = cur.parentElement;
      }
    }
    return path.join(' > ');
  }

  function label(el) {
    var t = el.tagName.toLowerCase();
    var r = el.getBoundingClientRect();
    return t + ' · ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  // --- Highlight + reporting -----------------------------------------------------------------------
  function draw() {
    if (!target) { box.style.display = 'none'; return; }
    var r = target.getBoundingClientRect();
    box.style.display = 'block';
    box.style.left = r.left + 'px'; box.style.top = r.top + 'px';
    box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
  }

  function report() {
    draw();
    applyPreview();
    if (!native) return;
    native.postMessage(JSON.stringify({
      t: 'pick',
      selector: selector,
      count: count(selector),
      label: target ? label(target) : '',
      canChild: trail.length > 0,
      canParent: !!(target && target.parentElement && target.parentElement !== document.body &&
        target.parentElement !== document.documentElement),
    }));
  }

  function select(el) {
    target = el; trail = [];
    selector = build(el);
    report();
  }

  function applyPreview() {
    preview.textContent = previewing && selector ? selector + '{visibility:hidden!important}' : '';
  }

  // --- Touch handling: drag scrolls the page, tap selects ------------------------------------------
  var sx = 0, sy = 0, lx = 0, ly = 0, moved = false;
  shield.addEventListener('touchstart', function (e) {
    var t = e.touches[0]; sx = lx = t.clientX; sy = ly = t.clientY; moved = false;
    e.preventDefault();
  }, { passive: false });
  shield.addEventListener('touchmove', function (e) {
    var t = e.touches[0];
    if (Math.abs(t.clientX - sx) + Math.abs(t.clientY - sy) > 10) moved = true;
    window.scrollBy(lx - t.clientX, ly - t.clientY);
    lx = t.clientX; ly = t.clientY;
    e.preventDefault();
  }, { passive: false });
  shield.addEventListener('touchend', function (e) {
    e.preventDefault();
    if (moved) return;
    var el = elementAt(sx, sy);
    if (el) select(el);
  }, { passive: false });
  shield.addEventListener('click', function (e) {
    // Mouse / accessibility fallback.
    e.preventDefault(); e.stopPropagation();
    var el = elementAt(e.clientX, e.clientY);
    if (el) select(el);
  }, true);
  window.addEventListener('scroll', draw, true);
  window.addEventListener('resize', draw);

  window.__breezyPicker = {
    parent: function () {
      if (!target || !target.parentElement || target.parentElement === document.body) return;
      trail.push(target);
      target = target.parentElement;
      selector = build(target);
      report();
    },
    child: function () {
      if (!trail.length) return;
      target = trail.pop();
      selector = build(target);
      report();
    },
    setSelector: function (sel) {
      selector = sel;
      var n = count(sel);
      var first = n ? document.querySelector(sel) : null;
      if (first) target = first;
      report();
    },
    preview: function (on) { previewing = !!on; applyPreview(); },
    commit: function () {
      var sel = selector;
      this.stop();
      if (!sel) return;
      var st = document.createElement('style');
      st.textContent = sel + '{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
    },
    stop: function () {
      window.removeEventListener('scroll', draw, true);
      window.removeEventListener('resize', draw);
      [shield, box, preview].forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
      window.__breezyPicker = null;
    },
  };
  return true;
})();
