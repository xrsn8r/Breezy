// SponsorBlock for YouTube (www / m.youtube.com), top frame only.
// Breezy fetches the video's segments natively (privacy-preserving hash-prefix lookup) and hands
// them to window.__breezySB.segments(); this script skips them as the video plays.
(function () {
  'use strict';
  if (window.top !== window || window.__breezySB) return;
  var bridge = window.BreezyPage;
  if (!bridge || !bridge.sponsorEnabled || !bridge.sponsorEnabled()) return;

  var LABELS = {
    sponsor: 'sponsor', selfpromo: 'self-promotion', interaction: 'reminder to subscribe',
    intro: 'intro', outro: 'outro', preview: 'preview', music_offtopic: 'non-music section', filler: 'filler',
  };
  var current = null, segments = [], skipped = {};

  function videoId() {
    try {
      var u = new URL(location.href);
      if (u.pathname === '/watch') return u.searchParams.get('v');
      var m = u.pathname.match(/^\/(?:shorts|embed|live)\/([\w-]{11})/);
      return m ? m[1] : null;
    } catch (e) { return null; }
  }

  function check() {
    var id = videoId();
    if (id === current) return;
    current = id; segments = []; skipped = {};
    if (id && /^[\w-]{11}$/.test(id)) { try { bridge.sponsorSegments(id); } catch (e) {} }
  }

  window.__breezySB = {
    segments: function (id, list) { if (id === current) { segments = list || []; skipped = {}; } },
  };

  // --- Toast with Undo ---------------------------------------------------------------------------
  var toast = null, toastTimer = 0;
  function showToast(text, onUndo) {
    if (!document.body) return;
    if (!toast) {
      toast = document.createElement('div');
      toast.setAttribute('style', [
        'position:fixed', 'left:50%', 'bottom:96px', 'transform:translateX(-50%)', 'z-index:2147483647',
        'background:rgba(32,33,36,.94)', 'color:#fff', 'font:500 14px/1.3 Roboto,system-ui,sans-serif',
        'padding:10px 8px 10px 16px', 'border-radius:12px', 'display:flex', 'align-items:center', 'gap:12px',
        'box-shadow:0 4px 16px rgba(0,0,0,.35)', 'max-width:90vw',
      ].join(';'));
      document.body.appendChild(toast);
    }
    toast.textContent = '';
    var label = document.createElement('span');
    label.textContent = text;
    var undo = document.createElement('button');
    undo.textContent = 'Undo';
    undo.setAttribute('style', 'background:none;border:0;color:#8ab4f8;font:inherit;padding:4px 8px;cursor:pointer');
    undo.onclick = function () { onUndo(); hide(); };
    toast.appendChild(label); toast.appendChild(undo);
    toast.style.display = 'flex';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(hide, 4000);
  }
  function hide() { if (toast) toast.style.display = 'none'; }

  // --- Skipping ----------------------------------------------------------------------------------
  document.addEventListener('timeupdate', function (e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement) || !segments.length) return;
    // Don't act on ads that play in the same element (their duration differs from the video's).
    var t = v.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var s = segments[i];
      if (skipped[s.uuid] || t < s.start || t >= s.end - 0.25) continue;
      if (s.duration && v.duration && Math.abs(v.duration - s.duration) > 4) continue;
      skipped[s.uuid] = true;
      var from = t;
      v.currentTime = s.end;
      showToast('Skipped ' + (LABELS[s.category] || 'segment'), function () { v.currentTime = Math.max(0, from - 0.1); });
    }
  }, true);

  window.addEventListener('yt-navigate-finish', check);
  window.addEventListener('popstate', check);
  document.addEventListener('DOMContentLoaded', check);
  setInterval(check, 1000);
  check();
})();
