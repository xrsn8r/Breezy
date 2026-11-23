// Breezy privacy signals and fingerprint "farbling", injected at document start into every frame.
//
// - Global Privacy Control (navigator.globalPrivacyControl) and, optionally, Do Not Track.
// - Farbling (as in Brave): the APIs most used for fingerprinting return values with tiny,
//   deterministic noise. The noise is keyed per site (eTLD+1) and per app session, so a site sees
//   a stable fingerprint during a session but can't link it to other sites or later sessions.
//   Canvas and audio output change by a few least-significant bits: invisible and inaudible.
(function () {
  'use strict';
  if (window.__breezyPrivacy) return;
  try { Object.defineProperty(window, '__breezyPrivacy', { value: true }); } catch (e) { return; }
  var bridge = window.BreezyPage;
  if (!bridge || !bridge.privacyConfig) return;
  var host = location.hostname;
  if (!host) { try { host = parent.location.hostname; } catch (e) {} } // about:blank / srcdoc frames
  var cfg;
  try { cfg = JSON.parse(bridge.privacyConfig(host || '')); } catch (e) { return; }

  function getter(proto, name, value) {
    try {
      Object.defineProperty(proto, name, { get: native(function () { return value; }, 'get ' + name), configurable: true });
    } catch (e) {}
  }

  // Wrapped functions keep reporting "[native code]" from toString().
  var nativeToString = Function.prototype.toString;
  var disguised = new WeakMap();
  function native(fn, name) { disguised.set(fn, 'function ' + name + '() { [native code] }'); return fn; }
  try {
    Function.prototype.toString = new Proxy(nativeToString, {
      apply: function (target, self, args) {
        if (disguised.has(self)) return disguised.get(self);
        return Reflect.apply(target, self, args);
      },
    });
    disguised.set(Function.prototype.toString, 'function toString() { [native code] }');
  } catch (e) {}

  function wrap(proto, name, make) {
    if (!proto) return;
    var original = proto[name];
    if (typeof original !== 'function') return;
    try { proto[name] = native(make(original), name); } catch (e) {}
  }

  // --- Privacy signals ---------------------------------------------------------------------------
  if (cfg.gpc) getter(Navigator.prototype, 'globalPrivacyControl', true);
  if (cfg.dnt) getter(Navigator.prototype, 'doNotTrack', '1');

  if (!cfg.seed) return;

  // --- Farbling ----------------------------------------------------------------------------------
  var seed = parseInt(cfg.seed, 16) >>> 0;
  // Deterministic hash of (seed, n) -> [0, 2^32).
  function hash(n) {
    var h = (seed ^ Math.imul(n | 0, 0x9e3779b1)) >>> 0;
    h = Math.imul(h ^ (h >>> 16), 0x85ebca6b) >>> 0;
    h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35) >>> 0;
    return (h ^ (h >>> 16)) >>> 0;
  }

  // Flip the lowest bit of one colour channel in a sparse, seed-chosen set of pixels.
  function farblePixels(data) {
    var pixels = data.length >> 2;
    if (!pixels) return;
    var step = 1 + (hash(pixels) % 23);
    for (var p = hash(7) % step; p < pixels; p += step + (hash(p) & 15)) {
      var i = (p << 2) + (hash(p + 1) % 3);
      data[i] ^= 1;
    }
  }

  var CanvasProto = window.HTMLCanvasElement && HTMLCanvasElement.prototype;
  var Ctx2D = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  var origGetImageData = Ctx2D && Ctx2D.getImageData;

  // A farbled copy of a canvas, for toDataURL/toBlob (the page's own canvas is left untouched).
  function farbledCopy(canvas) {
    var w = canvas.width, h = canvas.height;
    if (!w || !h || w * h > 4000000) return null;
    var copy = document.createElement('canvas');
    copy.width = w; copy.height = h;
    var ctx = copy.getContext('2d');
    ctx.drawImage(canvas, 0, 0);
    var img = origGetImageData.call(ctx, 0, 0, w, h);
    farblePixels(img.data);
    ctx.putImageData(img, 0, 0);
    return copy;
  }

  if (CanvasProto && origGetImageData) {
    wrap(CanvasProto, 'toDataURL', function (orig) {
      return function () {
        var copy = null;
        try { copy = farbledCopy(this); } catch (e) {}
        return orig.apply(copy || this, arguments);
      };
    });
    wrap(CanvasProto, 'toBlob', function (orig) {
      return function () {
        var copy = null;
        try { copy = farbledCopy(this); } catch (e) {}
        return orig.apply(copy || this, arguments);
      };
    });
    wrap(Ctx2D, 'getImageData', function (orig) {
      return function () {
        var img = orig.apply(this, arguments);
        try { farblePixels(img.data); } catch (e) {}
        return img;
      };
    });
  }

  [window.WebGLRenderingContext, window.WebGL2RenderingContext].forEach(function (GL) {
    if (!GL) return;
    wrap(GL.prototype, 'readPixels', function (orig) {
      return function () {
        var r = orig.apply(this, arguments);
        var out = arguments[6];
        try { if (out && out.length) farblePixels(out); } catch (e) {}
        return r;
      };
    });
  });

  // Audio: scale samples by a factor within ±1e-7 (far below audibility).
  var farbledBuffers = new WeakSet();
  function audioFactor(i) { return 1 + ((hash(i) % 2001) - 1000) * 1e-10; }
  if (window.AudioBuffer) {
    wrap(AudioBuffer.prototype, 'getChannelData', function (orig) {
      return function () {
        var data = orig.apply(this, arguments);
        try {
          if (!farbledBuffers.has(data)) {
            farbledBuffers.add(data);
            for (var i = 0; i < data.length; i += 1 + (hash(i) & 7)) data[i] *= audioFactor(i);
          }
        } catch (e) {}
        return data;
      };
    });
  }
  if (window.AnalyserNode) {
    wrap(AnalyserNode.prototype, 'getFloatFrequencyData', function (orig) {
      return function (arr) {
        var r = orig.apply(this, arguments);
        try { for (var i = 0; i < arr.length; i++) arr[i] *= audioFactor(i); } catch (e) {}
        return r;
      };
    });
  }

  // Hardware: report a plausible, per-site core count instead of the real one.
  try {
    var cores = navigator.hardwareConcurrency;
    if (cores > 2) getter(Navigator.prototype, 'hardwareConcurrency', 2 + (hash(3) % (cores - 1)));
  } catch (e) {}
})();
