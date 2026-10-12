// 顶栏三档：深色、浅色、跟随系统，与控制台共用 novabot-theme。
// 只存 light / dark，跟随系统删除键且不设 data-theme，由 CSS 媒体查询换色。
// 存储出错不挡本页换色；另一个标签页改了选择，本页跟着变。
(function () {
  "use strict";

  var KEY = "novabot-theme";
  var ORDER = ["dark", "light", "system"];

  function readMode() {
    try {
      var saved = localStorage.getItem(KEY);
      if (saved === "dark" || saved === "light") return saved;
    } catch (e) {}
    return "system";
  }

  function writeMode(mode) {
    try {
      if (mode === "system") localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, mode);
      return true;
    } catch (e) {
      return false;
    }
  }

  function apply(mode) {
    var root = document.documentElement;
    if (mode === "system") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", mode);
    root.setAttribute("data-mode", mode);
  }

  apply(readMode());

  var btn = document.querySelector("button.theme");

  function setHint(mode) {
    if (!btn) return;
    var el = btn.querySelector(".opt-" + mode);
    var text = el && el.getAttribute("data-hint");
    if (!text) return;
    btn.title = text;
    btn.setAttribute("aria-label", text);
  }

  setHint(document.documentElement.getAttribute("data-mode") || "system");

  window.addEventListener("storage", function (event) {
    if (!event || (event.key !== KEY && event.key !== null)) return;
    var mode = readMode();
    apply(mode);
    setHint(mode);
  });

  if (!btn) return;
  btn.addEventListener("click", function () {
    var cur = document.documentElement.getAttribute("data-mode") || "system";
    var i = ORDER.indexOf(cur);
    var nxt = ORDER[(i + 1) % ORDER.length];
    writeMode(nxt);
    apply(nxt);
    setHint(nxt);
  });
})();
