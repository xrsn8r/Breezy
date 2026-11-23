// Breezy cosmetic filtering. Injected at document start into every frame.
// Talks to the Rust engine through the synchronous `BreezyShield` JavaScript interface.
(function () {
  'use strict';
  if (window.__breezyCosmetic) return;
  Object.defineProperty(window, '__breezyCosmetic', { value: true });

  var api = window.BreezyShield;
  if (!api || !/^https?:$/.test(location.protocol)) return;

  var res;
  try { res = JSON.parse(api.resources(location.href)); } catch (e) { return; }
  if (!res || !res.hide_selectors) return; // shields off for this site, or engine not ready

  // --- Scriptlets (uBO-style ##+js(...)) --------------------------------------------------
  if (res.injected_script) {
    try {
      (0, eval)(res.injected_script);
    } catch (e) {
      // CSP forbids eval: ask native to inject via evaluateJavascript (not subject to CSP).
      try { api.injectScriptlets(location.href); } catch (_) {}
    }
  }

  // --- Hiding stylesheet --------------------------------------------------------------------
  // One rule per selector so a single invalid selector cannot void the rest. Constructable
  // stylesheets are not subject to the page's style-src CSP.
  var rules = [];
  var sheet = null;
  function hide(selectors) {
    if (!selectors || !selectors.length) return;
    for (var i = 0; i < selectors.length; i++) rules.push(selectors[i] + '{display:none!important}');
    try {
      if (!sheet) {
        sheet = new CSSStyleSheet();
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      }
      sheet.replaceSync(rules.join('\n'));
    } catch (e) {
      var style = document.createElement('style');
      style.textContent = rules.join('\n');
      (document.head || document.documentElement).appendChild(style);
    }
  }
  hide(res.hide_selectors);

  // --- Procedural / action filters (supported subset) ---------------------------------------
  var procedural = [];
  (res.procedural_actions || []).forEach(function (json) {
    try {
      var f = JSON.parse(json);
      if (f.selector.length !== 1 || f.selector[0].type !== 'css-selector') return;
      procedural.push({ css: f.selector[0].arg, action: f.action });
    } catch (e) {}
  });
  function applyProcedural(root) {
    procedural.forEach(function (p) {
      var nodes;
      try { nodes = root.querySelectorAll(p.css); } catch (e) { return; }
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i], a = p.action;
        if (!a) el.style.setProperty('display', 'none', 'important');
        else if (a.type === 'remove') el.remove();
        else if (a.type === 'style') el.style.cssText += ';' + a.arg;
        else if (a.type === 'remove-attr') el.removeAttribute(a.arg);
        else if (a.type === 'remove-class') el.classList.remove(a.arg);
      }
    });
  }

  // --- Generic class/id hiding --------------------------------------------------------------
  var exceptions = JSON.stringify(res.exceptions || []);
  var seenClasses = new Set(), seenIds = new Set();
  var newClasses = [], newIds = [];
  var pending = false;

  function collect(el) {
    if (el.nodeType !== 1) return;
    if (el.id && !seenIds.has(el.id)) { seenIds.add(el.id); newIds.push(el.id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      if (!seenClasses.has(cl[i])) { seenClasses.add(cl[i]); newClasses.push(cl[i]); }
    }
  }
  function collectTree(root) {
    collect(root);
    if (!root.querySelectorAll) return;
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) collect(all[i]);
  }
  function flush() {
    pending = false;
    if (!newClasses.length && !newIds.length) return;
    var c = JSON.stringify(newClasses), i = JSON.stringify(newIds);
    newClasses = []; newIds = [];
    try { hide(JSON.parse(api.hiddenSelectors(c, i, exceptions))); } catch (e) {}
  }
  function schedule() {
    if (pending) return;
    pending = true;
    setTimeout(flush, 50);
  }

  var observer = new MutationObserver(function (mutations) {
    for (var m = 0; m < mutations.length; m++) {
      var mu = mutations[m];
      if (mu.type === 'attributes') collect(mu.target);
      else for (var n = 0; n < mu.addedNodes.length; n++) {
        var node = mu.addedNodes[n];
        if (!res.generichide) collectTree(node);
        if (procedural.length && node.nodeType === 1) applyProcedural(node.parentNode || node);
      }
    }
    if (!res.generichide) schedule();
  });

  // Observe from document start so elements are handled as the parser inserts them.
  var opts = { childList: true, subtree: true };
  if (!res.generichide) { opts.attributes = true; opts.attributeFilter = ['class', 'id']; }
  if (!res.generichide) { collectTree(document.documentElement); flush(); }
  if (procedural.length) applyProcedural(document);
  observer.observe(document.documentElement, opts);
})();
