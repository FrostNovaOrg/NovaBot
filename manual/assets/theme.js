// 顶栏三档：深色、浅色、跟随系统。选择记在 localStorage 的 novabot-manual-theme。
// 读不到，或存的不是这三档，按跟随系统。写不进时这一页仍显示刚点的那档，下次打开再按跟随系统。出错不往外抛。
// 跟随系统时，系统深浅变化，页面跟着变。
(function () {
  "use strict";

  var KEY = "novabot-manual-theme";
  var ORDER = ["dark", "light", "system"];

  function readMode() {
    try {
      var saved = localStorage.getItem(KEY);
      if (saved === "dark" || saved === "light" || saved === "system") return saved;
    } catch (e) {}
    return "system";
  }

  function writeMode(mode) {
    try {
      localStorage.setItem(KEY, mode);
      return true;
    } catch (e) {
      return false;
    }
  }

  function systemDark() {
    return !!(window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: dark)").matches);
  }

  function apply(mode) {
    var dark = mode === "dark" || (mode === "system" && systemDark());
    var root = document.documentElement;
    root.setAttribute("data-theme", dark ? "dark" : "light");
    root.setAttribute("data-mode", mode);
  }

  apply(readMode());

  if (window.matchMedia) {
    var mq = window.matchMedia("(prefers-color-scheme: dark)");
    var onScheme = function () {
      if (document.documentElement.getAttribute("data-mode") === "system") apply("system");
    };
    if (mq.addEventListener) mq.addEventListener("change", onScheme);
    else if (mq.addListener) mq.addListener(onScheme);
  }

  var btn = document.querySelector("button.theme");
  if (!btn) return;

  function setHint(mode) {
    var el = btn.querySelector(".opt-" + mode);
    var text = el && el.getAttribute("data-hint");
    if (!text) return;
    btn.title = text;
    btn.setAttribute("aria-label", text);
  }

  setHint(document.documentElement.getAttribute("data-mode") || "system");

  btn.addEventListener("click", function () {
    var cur = document.documentElement.getAttribute("data-mode") || "system";
    var i = ORDER.indexOf(cur);
    var nxt = ORDER[(i + 1) % ORDER.length];
    writeMode(nxt);
    apply(nxt);
    setHint(nxt);
  });
})();
