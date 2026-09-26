// 手册站搜索：索引由生成脚本预先生成在 search-data.js（SEARCH_DATA），
// 这里只做原生子串匹配，中文直接按字符匹配，不引任何外部库。
(function () {
  'use strict';

  var MAX = 30;          // 最多列出的结果数
  var AROUND = 26;       // 摘要里命中词前后各取多少字

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function excerpt(text, q) {
    var i = text.indexOf(q);
    if (i < 0) return '';
    var from = Math.max(0, i - AROUND);
    var to = Math.min(text.length, i + q.length + AROUND);
    return (from > 0 ? '…' : '') + text.slice(from, to) + (to < text.length ? '…' : '');
  }

  function search(q) {
    var out = [];
    var needle = q.toLowerCase();
    for (var i = 0; i < SEARCH_DATA.length && out.length < MAX; i++) {
      var page = SEARCH_DATA[i];
      var hay = (page.t + '\n' + page.c).toLowerCase();
      var at = hay.indexOf(needle);
      if (at >= 0) {
        out.push({
          u: page.u,
          t: page.t,
          ex: excerpt(page.c.toLowerCase(), needle) || page.t
        });
      }
    }
    return out;
  }

  function render(box, q) {
    if (!q) { box.innerHTML = ''; box.hidden = true; return; }
    var hits = search(q);
    var html = hits.map(function (h) {
      return '<a href="' + esc(h.u) + '"><span class="where">' + esc(h.t) +
        '</span><span class="ex">' + esc(h.ex) + '</span></a>';
    }).join('');
    if (!hits) html = '';
    if (!html) html = '<span class="ex">没有匹配的页</span>';
    box.innerHTML = html;
    box.hidden = false;
  }

  var inputs = document.querySelectorAll('input.q');
  Array.prototype.forEach.call(inputs, function (input) {
    var box = input.parentNode.querySelector('.hits');
    input.addEventListener('input', function () { render(box, input.value.trim()); });
  });
})();
