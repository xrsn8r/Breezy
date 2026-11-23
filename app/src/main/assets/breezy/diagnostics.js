// "Page diagnostics" menu action: describe viewport, scroll locks and large overlays.
(function () {
  var d = document.documentElement, b = document.body;
  function st(el) {
    if (!el) return 'none';
    var c = getComputedStyle(el);
    return 'overflow=' + c.overflowY + ' position=' + c.position + ' height=' + Math.round(el.getBoundingClientRect().height) +
      ' touch-action=' + c.touchAction + (el.style.top ? ' top=' + el.style.top : '');
  }
  var lines = [
    'Page: ' + location.host + location.pathname.slice(0, 60),
    'viewport ' + innerWidth + 'x' + innerHeight + '  dpr=' + devicePixelRatio + '  scrollY=' + Math.round(scrollY),
    'scrollHeight html=' + d.scrollHeight + ' body=' + (b ? b.scrollHeight : 0) + '  scrollingElement=' + (document.scrollingElement || {}).tagName,
    'html: ' + st(d),
    'body: ' + st(b),
    'prefers-dark=' + matchMedia('(prefers-color-scheme: dark)').matches,
    'Large fixed elements:'
  ];
  var all = document.querySelectorAll('body *'), w = innerWidth, h = innerHeight, found = 0;
  for (var i = 0; i < all.length && found < 6; i++) {
    var el = all[i], c = getComputedStyle(el);
    if (c.position !== 'fixed' || c.display === 'none') continue;
    var r = el.getBoundingClientRect();
    if (r.width * r.height < w * h * 0.25) continue;
    found++;
    lines.push(' - ' + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
      (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '') +
      ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' z=' + c.zIndex + ' opacity=' + c.opacity +
      ' pe=' + c.pointerEvents + ' bg=' + c.backgroundColor);
  }
  if (!found) lines.push(' (none)');
  return lines.join('\n');
})();
