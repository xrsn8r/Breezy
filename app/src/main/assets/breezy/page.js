// Breezy page helpers, injected at document start into the top frame:
//  - media state reporting (for background play / media notification) and playback speed
//  - background play: keep pages from noticing the app went to the background
//  - "force allow zoom": undo viewport meta tags that disable pinch zoom
(function () {
  'use strict';
  if (window.top !== window || window.__breezyMedia) return;
  var bridge = window.BreezyPage;
  if (!bridge) return;
  var cfg = {};
  try { cfg = JSON.parse(bridge.config()); } catch (e) {}
  var rate = cfg.rate || 1;

  // --- Media -------------------------------------------------------------------------------------
  function mediaElements() { return Array.prototype.slice.call(document.querySelectorAll('video, audio')); }
  function anyPlaying() {
    return mediaElements().some(function (m) { return !m.paused && !m.ended && m.readyState > 2; });
  }
  var last = null, timer = 0;
  function report() {
    clearTimeout(timer);
    timer = setTimeout(function () {
      var playing = anyPlaying();
      if (playing === last) return;
      last = playing;
      try { bridge.mediaState(playing, document.title || ''); } catch (e) {}
    }, 150);
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (type === 'play' && rate !== 1 && e.target && e.target.playbackRate !== rate) {
        try { e.target.playbackRate = rate; } catch (_) {}
      }
      report();
    }, true);
  });
  Object.defineProperty(window, '__breezyMedia', {
    value: {
      play: function () {
        var m = mediaElements().filter(function (x) { return x.paused; })[0];
        if (m) { var p = m.play(); if (p && p.catch) p.catch(function () {}); }
      },
      pause: function () { mediaElements().forEach(function (m) { if (!m.paused) m.pause(); }); },
      setRate: function (r) {
        rate = r;
        mediaElements().forEach(function (m) { try { m.playbackRate = r; } catch (e) {} });
      },
    },
  });

  // --- Background play -----------------------------------------------------------------------------
  // Pages pause media on visibilitychange; when background play is on, report "always visible".
  if (cfg.bgPlay) {
    try {
      Object.defineProperty(Document.prototype, 'hidden', { get: function () { return false; }, configurable: true });
      Object.defineProperty(Document.prototype, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
      document.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
      window.addEventListener('pagehide', function (e) { if (anyPlaying()) e.stopImmediatePropagation(); }, true);
    } catch (e) {}
  }

  // --- Force allow zoom ----------------------------------------------------------------------------
  if (cfg.forceZoom) {
    var fix = function (meta) {
      var c = meta.getAttribute('content') || '';
      var fixed = c.split(',').map(function (s) { return s.trim(); }).filter(function (s) {
        return s && !/^(user-scalable|maximum-scale|minimum-scale)\s*=/i.test(s);
      }).concat(['user-scalable=yes', 'maximum-scale=10']).join(', ');
      if (fixed !== c) meta.setAttribute('content', fixed);
    };
    var scan = function () {
      var metas = document.querySelectorAll('meta[name="viewport"]');
      for (var i = 0; i < metas.length; i++) fix(metas[i]);
    };
    new MutationObserver(scan).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    scan();
  }
})();
