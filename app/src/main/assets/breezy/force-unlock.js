// "Unlock scrolling" menu action: release overflow/position locks on <html>/<body> and make large
// fixed overlays click-through. Returns the number of overlays disabled.
(function () {
  var n = 0;
  [document.documentElement, document.body].forEach(function (el) {
    if (!el) return;
    el.style.setProperty('overflow', 'auto', 'important');
    el.style.setProperty('overflow-y', 'auto', 'important');
    el.style.setProperty('touch-action', 'auto', 'important');
    if (getComputedStyle(el).position === 'fixed') el.style.setProperty('position', 'static', 'important');
  });
  var w = innerWidth, h = innerHeight;
  var all = document.querySelectorAll('body *');
  for (var i = 0; i < all.length; i++) {
    var el = all[i], cs = getComputedStyle(el);
    if (cs.position !== 'fixed' || cs.display === 'none') continue;
    var r = el.getBoundingClientRect();
    if (r.width * r.height > w * h * 0.6) {
      el.style.setProperty('pointer-events', 'none', 'important');
      n++;
    }
  }
  return n;
})();
