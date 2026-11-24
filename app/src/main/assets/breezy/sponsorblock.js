// SponsorBlock for YouTube (www / m.youtube.com), top frame only.
// Breezy fetches the video's segments natively (privacy-preserving hash-prefix lookup) and hands
// them to window.__breezySB.segments(); this script skips them as the video plays.
(function () {
  'use strict';
  if (window.top !== window || window.__breezySB) return;
  var bridge = window.BreezyPage;
  var channel = window.BreezyHost;
  if (!bridge || !channel || !bridge.sponsorEnabled || !bridge.sponsorEnabled()) return;
  function send(msg) { try { channel.postMessage(JSON.stringify(msg)); } catch (e) {} }

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
    if (id && /^[\w-]{11}$/.test(id)) send({ t: 'sb', id: id });
  }

  Object.defineProperty(window, '__breezySB', { value: true });
  // Segments arrive as a reply on the same channel.
  channel.addEventListener('message', function (e) {
    var m; try { m = JSON.parse(e.data); } catch (_) { return; }
    if (m && m.t === 'sb' && m.id === current) { segments = m.segments || []; skipped = {}; }
  });

  // --- Toast with Undo ---------------------------------------------------------------------------
  var toast = null, toastTimer = 0;
  function showToast(text, onUndo) {
    if (!document.body) return;
    if (!toast) {
      toast = document.createElement('div');
      toast.setAttribute('style', [
        'position:fixed', 'left:50%', 'bottom:15%', 'transform:translateX(-50%)', 'z-index:2147483647',
        'background:rgba(32,33,36,.94)', 'color:#fff', 'font:500 14px/1.3 Roboto,system-ui,sans-serif',
        'padding:10px 8px 10px 16px', 'border-radius:12px', 'display:flex', 'align-items:center', 'gap:12px',
        'box-shadow:0 4px 16px rgba(0,0,0,.35)', 'max-width:90vw',
      ].join(';'));
    }
    // In fullscreen only the fullscreen element is shown, so the toast has to live inside it.
    var parent = document.fullscreenElement || document.webkitFullscreenElement || document.body;
    if (toast.parentNode !== parent) parent.appendChild(toast);
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
  // Checked from timeupdate (window capture, before the page's own listeners can stop it) and
  // from a 250 ms poll of the playing <video>, so a page swallowing events can't defeat it.
  function largestVideo() {
    var vids = document.getElementsByTagName('video'), best = null;
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!best || (!v.paused && best.paused) || v.clientWidth * v.clientHeight > best.clientWidth * best.clientHeight) best = v;
    }
    return best;
  }
  function maybeSkip(v) {
    if (!v || !segments.length || v.readyState < 1) return;
    var t = v.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var s = segments[i];
      if (skipped[s.uuid] || t < s.start || t >= s.end - 0.25) continue;
      // An ad playing in the same element is much shorter than the video the segment belongs to.
      if (s.duration > 0 && v.duration > 0 && isFinite(v.duration) && v.duration < s.duration * 0.5) continue;
      skipped[s.uuid] = true;
      var from = t;
      v.currentTime = s.end;
      send({ t: 'sbSkipped', category: s.category, seconds: Math.round(s.end - from) });
      showToast('Skipped ' + (LABELS[s.category] || 'segment'), function () { v.currentTime = Math.max(0, from - 0.1); });
    }
  }
  window.addEventListener('timeupdate', function (e) {
    if (e.target instanceof HTMLVideoElement) maybeSkip(e.target);
  }, true);
  setInterval(function () { if (segments.length) maybeSkip(largestVideo()); }, 250);

  window.addEventListener('yt-navigate-finish', check);
  window.addEventListener('popstate', check);
  document.addEventListener('DOMContentLoaded', check);
  setInterval(check, 1000);
  check();
})();
