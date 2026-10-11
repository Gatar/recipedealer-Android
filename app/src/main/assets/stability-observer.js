// Reports (via the `Android` JavascriptInterface) when the DOM had no mutations for QUIET_MS.
// Idempotent: re-injecting after another page load just restarts the quiet timer.
// 5 s is long on purpose: capture time is not critical, and shorter windows fired between the render steps of
// client-rendered pages (a 2 s idle gap was enough to capture an empty SPA shell).
(function () {
  var QUIET_MS = 5000;
  if (window.__rcStability) {
    window.__rcStability.restart();
    return;
  }
  var timer = null;
  var observer = null;
  function report() {
    try { Android.onDomStable(); } catch (e) { /* bridge gone */ }
  }
  function restart() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(report, QUIET_MS);
  }
  observer = new MutationObserver(restart);
  observer.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true, characterData: true
  });
  window.__rcStability = { restart: restart };
  restart();
})();
