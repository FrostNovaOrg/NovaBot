/**
 * 下拉框那两份夹具共用的假 DOM 与样式叠法
 *
 * 假 DOM 照字面解析 innerHTML，页面骨架照 index.html 摆：html > body > #app > 页容器，
 * 抽屉、弹层由产品码自己挂到 body 上；document.querySelector 在这棵树里真找，节点落在哪一层由产品码自己定。
 * 样式按浏览器的规矩叠：拆成规则、算权重、按先后排，只算静止状态（:hover、:focus 这类不算命中）。
 */

// —— 假 DOM ——

const VOID = new Set(['input', 'br', 'img', 'hr', 'meta', 'link', 'source', 'wbr', 'col', 'area']);

export class MiniNode {
  constructor(tagName) {
    this.tagName = String(tagName || '').toLowerCase();
    this.children = [];
    this.parentNode = null;
    this.className = '';
    this.id = '';
    this._text = '';
    this.attributes = {};
    this.dataset = {};
    this.style = {};
    this.listeners = {};
    this.disabled = false;
    this.hidden = false;
    this.type = '';
    this.value = '';
    this.placeholder = '';
  }

  get textContent() { return this._text; }
  set textContent(value) {
    this._text = String(value ?? '');
    this.children = [];
  }

  get innerHTML() { return ''; }
  set innerHTML(html) {
    for (const child of this.children) child.parentNode = null;
    this.children = [];
    parseInto(this, String(html ?? ''));
  }

  get firstElementChild() { return this.children[0] || null; }

  get isConnected() {
    let at = this;
    while (at.parentNode) at = at.parentNode;
    return at.tagName === 'html';
  }

  appendChild(child) {
    if (child.parentNode) child.remove();
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  remove() {
    if (!this.parentNode) return;
    const list = this.parentNode.children;
    list.splice(list.indexOf(this), 1);
    this.parentNode = null;
  }

  querySelector(selector) { return findAll(this, selector)[0] || null; }
  querySelectorAll(selector) { return findAll(this, selector); }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
  }
  getAttribute(name) {
    if (name === 'id') return this.id || null;
    if (name === 'class') return this.className || null;
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  removeAttribute(name) { delete this.attributes[name]; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener() {}
  dispatchEvent(event) {
    for (const fn of (this.listeners[event.type] || []).slice()) fn.call(this, event);
    return true;
  }
  click() {
    this.dispatchEvent({type: 'click', target: this, preventDefault() {}, stopPropagation() {}});
  }
  focus() { globalThis.document.activeElement = this; }
}

const TOKEN = /<!--[\s\S]*?-->|<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:\s+[^\s=>\/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(\/?)>|[^<]+/g;
const ATTR = /([^\s=>\/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;

/** 只够这几份模板用的拆法：标签、属性、注释、字面；不认实体 */
function parseInto(host, text) {
  const stack = [host];
  for (const m of text.matchAll(TOKEN)) {
    const top = stack[stack.length - 1];
    if (m[0].startsWith('<!--')) continue;
    if (m[1]) {
      const name = m[1].toLowerCase();
      for (let i = stack.length - 1; i > 0; i--) {
        if (stack[i].tagName === name) {
          stack.length = i;
          break;
        }
      }
      continue;
    }
    if (m[2]) {
      const node = new MiniNode(m[2]);
      for (const a of (m[3] || '').matchAll(ATTR)) {
        node.setAttribute(a[1].toLowerCase(), a[2] ?? a[3] ?? a[4] ?? '');
      }
      if (node.getAttribute('hidden') != null) node.hidden = true;
      top.appendChild(node);
      if (!m[4] && !VOID.has(node.tagName)) stack.push(node);
      continue;
    }
    if (m[0].trim()) top._text += m[0];
  }
}

function findAll(root, selector) {
  const out = [];
  const walk = node => {
    for (const child of node.children || []) {
      if (simpleMatch(child, selector)) out.push(child);
      walk(child);
    }
  };
  walk(root);
  return out;
}

/** 产品码查节点用的那几种写法：#id、.class、tag、tag[attr] */
function simpleMatch(node, selector) {
  return String(selector).split(',').some(part => {
    const sel = part.trim();
    if (sel.startsWith('#')) return node.id === sel.slice(1);
    if (sel.startsWith('.')) return String(node.className || '').split(/\s+/).includes(sel.slice(1));
    const attr = /^([a-z]+)\[([a-z-]+)]$/.exec(sel);
    if (attr) return node.tagName === attr[1] && node.getAttribute(attr[2]) != null;
    return node.tagName === sel.toLowerCase();
  });
}

/**
 * 建页面骨架并装上 document、location、fetch 这几个全局量
 * @param pageId 页容器的 id，例如 page-push
 */
export function installDom(pageId) {
  const html = new MiniNode('html');
  const head = html.appendChild(new MiniNode('head'));
  const body = html.appendChild(new MiniNode('body'));
  const app = body.appendChild(new MiniNode('div'));
  app.id = 'app';
  const section = app.appendChild(new MiniNode('section'));
  section.className = 'page';
  section.id = pageId;
  // 页上没有的节点交一个游离的替身，免得无关的那几处查不到就抛
  const lookup = selector => html.querySelector(selector) || new MiniNode('div');
  globalThis.document = {
    querySelector: lookup,
    querySelectorAll: selector => html.querySelectorAll(selector),
    getElementById: id => html.querySelector('#' + id),
    createElement: tag => new MiniNode(tag),
    createTextNode: text => {
      const node = new MiniNode('#text');
      node.textContent = text;
      return node;
    },
    addEventListener() {},
    removeEventListener() {},
    head,
    body,
    activeElement: null,
    documentElement: {classList: {toggle() {}, contains: () => false}},
  };
  globalThis.location = {hash: '', href: '', replace() {}, reload() {}};
  globalThis.fetch = () => Promise.resolve({status: 200, json: () => Promise.resolve({})});
  return {html, head, body, app, section};
}

// —— 样式：拆成规则、算权重、按静止状态叠 ——

export function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** 在顶层（不在括号、引号里）按某个字符切 */
export function splitTop(text, sep) {
  const out = [];
  let depth = 0;
  let quote = '';
  let from = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) quote = '';
      continue;
    }
    if (c === '"' || c === '\'') quote = c;
    else if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth--;
    else if (c === sep && depth === 0) {
      out.push(text.slice(from, i));
      from = i + 1;
    }
  }
  out.push(text.slice(from));
  return out;
}

/** 规则按出现先后编号；@media 里的照样算进来（哪一档宽度下冲掉了箭头也是故障），@keyframes 之类跳过 */
export function rulesOf(css, origin, into) {
  const text = stripComments(css);
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('{', i);
    if (open < 0) break;
    const prelude = text.slice(i, open).trim();
    let depth = 1;
    let j = open + 1;
    for (; j < text.length && depth; j++) {
      if (text[j] === '{') depth++;
      else if (text[j] === '}') depth--;
    }
    const inner = text.slice(open + 1, j - 1);
    if (prelude.startsWith('@media') || prelude.startsWith('@supports')) {
      rulesOf(inner, origin + ' ' + prelude, into);
    } else if (!prelude.startsWith('@')) {
      const decls = {};
      for (const part of splitTop(inner, ';')) {
        const k = part.indexOf(':');
        if (k < 0) continue;
        decls[part.slice(0, k).trim().toLowerCase()] = part.slice(k + 1).trim();
      }
      for (const sel of splitTop(prelude, ',')) {
        into.push({selector: sel.trim(), decls, order: into.length, origin});
      }
    }
    i = j;
  }
  return into;
}

/** 拆一个复合选择器：tag、#id、.class、[attr]、:伪类(参数)、::伪元素 */
function compoundParts(text) {
  const parts = [];
  let i = 0;
  const ident = () => {
    const m = /^[\w-]+|^\\./.exec(text.slice(i));
    let out = '';
    while (i < text.length) {
      const mm = /^(?:[\w-]+|\\.)/.exec(text.slice(i));
      if (!mm) break;
      out += mm[0];
      i += mm[0].length;
    }
    return m ? out : '';
  };
  while (i < text.length) {
    const c = text[i];
    if (c === '*') {
      i++;
      parts.push({kind: 'any'});
    } else if (c === '#') {
      i++;
      parts.push({kind: 'id', name: ident()});
    } else if (c === '.') {
      i++;
      parts.push({kind: 'class', name: ident()});
    } else if (c === '[') {
      const end = text.indexOf(']', i);
      const m = /^\[\s*([\w-]+)\s*(?:=\s*["']?([^"'\]]*)["']?)?\s*\]$/.exec(text.slice(i, end + 1));
      parts.push({kind: 'attr', name: m ? m[1] : '', value: m ? m[2] : undefined});
      i = end + 1;
    } else if (c === ':') {
      const element = text[i + 1] === ':';
      i += element ? 2 : 1;
      const name = ident().toLowerCase();
      let arg = null;
      if (text[i] === '(') {
        let depth = 0;
        const from = i + 1;
        for (; i < text.length; i++) {
          if (text[i] === '(') depth++;
          else if (text[i] === ')' && --depth === 0) break;
        }
        arg = text.slice(from, i);
        i++;
      }
      parts.push({kind: element ? 'element' : 'pseudo', name, arg});
    } else {
      parts.push({kind: 'tag', name: ident().toLowerCase()});
    }
  }
  return parts;
}

/** 拆一条复杂选择器：从左到右的复合段与它们之间的连接符 */
function complexParts(selector) {
  const out = [];
  let buf = '';
  let depth = 0;
  let pending = null;
  const flush = () => {
    if (buf.trim()) {
      out.push({combinator: out.length ? (pending || ' ') : null, parts: compoundParts(buf.trim())});
      pending = null;
    }
    buf = '';
  };
  for (const c of selector) {
    if (c === '(' || c === '[') depth++;
    if (c === ')' || c === ']') depth--;
    if (depth === 0 && (c === '>' || c === '+' || c === '~')) {
      flush();
      pending = c;
    } else if (depth === 0 && /\s/.test(c)) {
      flush();
    } else {
      buf += c;
    }
  }
  flush();
  return out;
}

function hasClass(node, name) {
  return String(node.className || '').split(/\s+/).includes(name);
}

/** 静止状态下就成立的伪类才算命中；:hover、:focus、:disabled 这类此刻不成立 */
function partMatches(node, part) {
  switch (part.kind) {
    case 'any': return true;
    case 'tag': return node.tagName === part.name;
    case 'id': return node.id === part.name;
    case 'class': return hasClass(node, part.name);
    case 'attr': {
      const got = node.getAttribute(part.name);
      return got != null && (part.value === undefined || got === part.value);
    }
    case 'element': return false;
    case 'pseudo':
      if (part.name === 'root') return node.tagName === 'html' && !node.parentNode;
      if (part.name === 'is' || part.name === 'where') {
        return splitTop(part.arg, ',').some(sel => selectorMatches(node, sel.trim()));
      }
      if (part.name === 'not') {
        return !splitTop(part.arg, ',').some(sel => selectorMatches(node, sel.trim()));
      }
      return false;
    default: return false;
  }
}

export function selectorMatches(node, selector) {
  const chain = complexParts(selector);
  const at = (index, el) => {
    if (!el || !chain[index].parts.every(part => partMatches(el, part))) return false;
    if (index === 0) return true;
    const comb = chain[index].combinator;
    if (comb === '>') return at(index - 1, el.parentNode);
    if (comb === ' ') {
      for (let up = el.parentNode; up; up = up.parentNode) if (at(index - 1, up)) return true;
      return false;
    }
    // 兄弟连接符要知道左右邻居；这几条规则里没有落到下拉框上的，一律不算命中
    return false;
  };
  return chain.length > 0 && at(chain.length - 1, node);
}

/** [id, class 类, 类型] 三位权重；:is() 取参数里最重的那条，:where() 记零 */
export function specificity(selector) {
  const sum = [0, 0, 0];
  for (const seg of complexParts(selector)) {
    for (const part of seg.parts) {
      if (part.kind === 'id') sum[0]++;
      else if (part.kind === 'class' || part.kind === 'attr') sum[1]++;
      else if (part.kind === 'tag' || part.kind === 'element') sum[2]++;
      else if (part.kind === 'pseudo') {
        if (part.name === 'where') continue;
        if (part.name === 'is' || part.name === 'not') {
          const best = splitTop(part.arg, ',').map(sel => specificity(sel.trim()))
            .sort((a, b) => cmp(b, a))[0] || [0, 0, 0];
          for (let k = 0; k < 3; k++) sum[k] += best[k];
        } else {
          sum[1]++;
        }
      }
    }
  }
  return sum;
}

export function cmp(a, b) {
  for (let k = 0; k < a.length; k++) if (a[k] !== b[k]) return a[k] - b[k];
  return 0;
}

export function ancestry(node) {
  const names = [];
  for (let at = node; at; at = at.parentNode) {
    names.push(at.tagName + (at.id ? '#' + at.id : '') + (at.className ? '.' + String(at.className).split(/\s+/).join('.') : ''));
  }
  return names.reverse().join(' > ');
}
