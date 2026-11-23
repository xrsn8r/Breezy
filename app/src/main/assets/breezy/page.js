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
  function playingElement() {
    var list = mediaElements().filter(function (m) { return !m.paused && !m.ended && m.readyState > 2; });
    // Prefer the largest playing video.
    list.sort(function (a, b) { return (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight); });
    return list[0] || null;
  }
  var last = '', timer = 0;
  function report() {
    clearTimeout(timer);
    timer = setTimeout(function () {
      var el = playingElement();
      var isVideo = !!(el && el.tagName === 'VIDEO' && el.videoWidth > 0);
      var aspect = isVideo ? el.videoWidth / el.videoHeight : 16 / 9;
      var key = (!!el) + ':' + isVideo + ':' + aspect.toFixed(2);
      if (key === last) return;
      last = key;
      try { bridge.mediaState(!!el, document.title || '', isVideo, aspect); } catch (e) {}
    }, 150);
  }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'resize'].forEach(function (type) {
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
      // Picture-in-picture without the Fullscreen API: pin the playing video over the whole page.
      focus: function (on) {
        var style = document.getElementById('__breezy_pip');
        if (!on) {
          if (style) style.remove();
          var old = document.querySelector('[data-breezy-pip]');
          if (old) old.removeAttribute('data-breezy-pip');
          return;
        }
        var v = playingElement() || mediaElements().filter(function (m) { return m.tagName === 'VIDEO'; })[0];
        if (!v) return;
        v.setAttribute('data-breezy-pip', '');
        if (!style) {
          style = document.createElement('style');
          style.id = '__breezy_pip';
          (document.head || document.documentElement).appendChild(style);
        }
        style.textContent =
          'html,body{overflow:hidden!important;background:#000!important}' +
          '[data-breezy-pip]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
          'max-width:none!important;max-height:none!important;transform:none!important;' +
          'object-fit:contain!important;background:#000!important;z-index:2147483647!important;margin:0!important}';
      },
    },
  });

  // --- Background play -----------------------------------------------------------------------------
  // Pages pause media on visibilitychange; when background play is on, report "always visible".
  if (cfg.bgPlay) {
    try {
      var visible = function () { return 'visible'; }, notHidden = function () { return false; };
      Object.defineProperty(Document.prototype, 'hidden', { get: notHidden, configurable: true });
      Object.defineProperty(Document.prototype, 'visibilityState', { get: visible, configurable: true });
      Object.defineProperty(Document.prototype, 'webkitHidden', { get: notHidden, configurable: true });
      Object.defineProperty(Document.prototype, 'webkitVisibilityState', { get: visible, configurable: true });
      Document.prototype.hasFocus = function () { return true; };
      var swallow = function (e) { e.stopImmediatePropagation(); };
      // Registered at document start on window (capture), so they run before any page listener.
      ['visibilitychange', 'webkitvisibilitychange'].forEach(function (t) {
        window.addEventListener(t, swallow, true);
        document.addEventListener(t, swallow, true);
      });
      ['blur', 'pagehide', 'freeze'].forEach(function (t) {
        window.addEventListener(t, function (e) { if (anyPlaying()) e.stopImmediatePropagation(); }, true);
        document.addEventListener(t, function (e) { if (anyPlaying()) e.stopImmediatePropagation(); }, true);
      });
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
