// In-place page translation (top frame). Evaluated on demand when the user taps Translate.
// Text nodes are sent to Breezy in batches (BreezyPage.translateBatch); Breezy translates them
// natively and calls window.__breezyTranslate.apply(batchId, [...]). New content (infinite
// scroll, SPA navigation) is translated as it appears. restore() puts the original text back.
(function () {
  'use strict';
  if (window.__breezyTranslate) { window.__breezyTranslate.start(); return; }
  var bridge = window.BreezyPage;
  if (!bridge || !bridge.translateBatch) return;

  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1, SVG: 1, MATH: 1, KBD: 1, SAMP: 1, VAR: 1 };
  var originals = new Map(); // text node -> original text
  var pending = {};          // batch id -> [nodes]
  var queued = new Set();
  var nextId = 1, active = false, observer = null, flushTimer = 0;
  var MAX_CHARS = 3500, MAX_ITEMS = 60;

  function skippable(el) {
    for (; el && el !== document.body; el = el.parentElement) {
      if (SKIP[el.tagName && el.tagName.toUpperCase()]) return true;
      if (el.isContentEditable) return true;
      var t = el.getAttribute && el.getAttribute('translate');
      if (t === 'no' || (el.classList && el.classList.contains('notranslate'))) return true;
    }
    return false;
  }

  function collect(root) {
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var v = n.nodeValue;
        if (!v || v.trim().length < 2 || !/\p{L}/u.test(v)) return NodeFilter.FILTER_REJECT;
        if (originals.has(n) || queued.has(n)) return NodeFilter.FILTER_REJECT;
        return skippable(n.parentElement) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    var n;
    while ((n = walker.nextNode())) queued.add(n);
    scheduleFlush();
  }

  function scheduleFlush() {
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flush, 60);
  }

  function flush() {
    var nodes = [], texts = [], chars = 0;
    function send() {
      if (!nodes.length) return;
      var id = nextId++;
      pending[id] = nodes;
      try { bridge.translateBatch(id, JSON.stringify(texts)); } catch (e) {}
      nodes = []; texts = []; chars = 0;
    }
    queued.forEach(function (n) {
      queued.delete(n);
      if (!n.isConnected) return;
      var t = n.nodeValue.trim();
      if (chars + t.length > MAX_CHARS || nodes.length >= MAX_ITEMS) send();
      nodes.push(n); texts.push(t); chars += t.length;
    });
    send();
  }

  function applyBatch(id, results) {
    var nodes = pending[id];
    delete pending[id];
    if (!nodes || !active || !results) return;
    if (observer) observer.disconnect();
    nodes.forEach(function (n, i) {
      var r = results[i];
      if (typeof r !== 'string' || !n.isConnected) return;
      var v = n.nodeValue;
      if (!originals.has(n)) originals.set(n, v);
      var lead = v.match(/^\s*/)[0], trail = v.match(/\s*$/)[0];
      n.nodeValue = lead + r + trail;
    });
    observe();
  }

  function observe() {
    if (!observer) {
      observer = new MutationObserver(function (records) {
        records.forEach(function (r) {
          r.addedNodes.forEach(function (n) {
            if (n.nodeType === 3) { if (!originals.has(n)) collect(n.parentNode || document.body); }
            else if (n.nodeType === 1) collect(n);
          });
          if (r.type === 'characterData' && originals.has(r.target)) {
            originals.delete(r.target); // the page replaced the text itself: translate the new text
            collect(r.target.parentNode || document.body);
          }
        });
      });
    }
    if (document.body) observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  window.__breezyTranslate = {
    start: function () {
      if (active) return;
      active = true;
      if (document.title) {
        var id = nextId++;
        pending[id] = 'title';
        try { bridge.translateBatch(id, JSON.stringify([document.title])); } catch (e) {}
      }
      collect(document.body);
      observe();
    },
    apply: function (id, results) {
      if (pending[id] === 'title') { delete pending[id]; if (active && results && results[0]) { originals.set('title', originals.get('title') || document.title); document.title = results[0]; } return; }
      applyBatch(id, results);
    },
    restore: function () {
      active = false;
      if (observer) observer.disconnect();
      queued.clear();
      pending = {};
      originals.forEach(function (v, n) {
        if (n === 'title') document.title = v;
        else if (n.isConnected) n.nodeValue = v;
      });
      originals.clear();
    },
  };
  window.__breezyTranslate.start();
})();
