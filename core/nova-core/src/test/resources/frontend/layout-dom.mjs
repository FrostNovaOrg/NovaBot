class MiniNode {
  constructor(tagName) {
    this.tagName = String(tagName || '').toLowerCase();
    this.children = [];
    this.parentNode = null;
    this.className = '';
    this.id = '';
    this._text = '';
    this._html = '';
    this.attributes = {};
    this.style = {};
    this.listeners = {};
    this.hidden = false;
    this.disabled = false;
    this.type = '';
    this.value = '';
    const self = this;
    this.classList = {
      toggle(name, force) {
        const set = new Set(String(self.className || '').split(/\s+/).filter(Boolean));
        const on = force === undefined ? !set.has(name) : !!force;
        if (on) set.add(name);
        else set.delete(name);
        self.className = [...set].join(' ');
        return on;
      },
      add(name) {
        this.toggle(name, true);
      },
      remove(name) {
        this.toggle(name, false);
      },
      contains(name) {
        return String(self.className || '').split(/\s+/).includes(name);
      },
    };
  }

  get textContent() {
    if (this.children.length && this.tagName !== '#text') {
      return this.children.map(c => c.textContent).join('');
    }
    return this._text;
  }

  set textContent(value) {
    this._text = String(value ?? '');
    this.children = [];
    this._html = '';
  }

  get innerHTML() {
    return this._html;
  }

  set innerHTML(html) {
    this._html = String(html ?? '');
    this.children = [];
    parseInto(this, this._html);
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
    if (name === 'type') this.type = String(value);
    if (name === 'hidden') this.hidden = true;
    if (name === 'disabled') this.disabled = true;
  }

  getAttribute(name) {
    if (name === 'id' && this.id) return this.id;
    if (name === 'class' && this.className) return this.className;
    if (name === 'hidden') return this.hidden ? '' : null;
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  dispatchEvent(event) {
    
    if (!event.target) event.target = this;
    let node = this;
    while (node) {
      const list = (node.listeners[event.type] || []).slice();
      for (const fn of list) fn.call(node, event);
      node = node.parentNode;
    }
    for (const fn of docListeners[event.type] || []) fn(event);
    return true;
  }

  contains(node) {
    for (let n = node; n; n = n.parentNode) if (n === this) return true;
    return false;
  }

  get parentElement() { return this.parentNode; }

  insertBefore(child, before) {
    if (child.parentNode) child.parentNode.children = child.parentNode.children.filter(n => n !== child);
    child.parentNode = this;
    const at = this.children.indexOf(before);
    this.children.splice(at < 0 ? this.children.length : at, 0, child);
    return child;
  }

  click() {
    this.focus();
    this.dispatchEvent({type: 'click', target: this, preventDefault() {}, stopPropagation() {}});
  }

  focus() {
    currentFocus = this;
  }

  querySelector(selector) {
    return findAll(this, selector)[0] || null;
  }

  querySelectorAll(selector) {
    return findAll(this, selector);
  }

  closest(selector) {
    let node = this;
    while (node) {
      if (matches(node, selector)) return node;
      node = node.parentNode;
    }
    return null;
  }
}

function applyAttrs(node, raw) {
  const re = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*"([^"]*)"|([a-zA-Z_:][-a-zA-Z0-9_:.]*)/g;
  let m;
  while ((m = re.exec(raw))) {
    if (m[1] != null) node.setAttribute(m[1], m[2]);
    else if (m[3] != null) node.setAttribute(m[3], '');
  }
}


function parseInto(parent, html) {
  const stack = [parent];
  const re = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    if (m[4] != null) {
      const text = m[4];
      if (text.trim()) {
        const node = new MiniNode('#text');
        node.textContent = text;
        stack[stack.length - 1].appendChild(node);
      }
      continue;
    }
    const closing = m[1] === '/';
    const tag = m[2].toLowerCase();
    const rawAttrs = m[3] || '';
    if (closing) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const node = new MiniNode(tag);
    applyAttrs(node, rawAttrs);
    stack[stack.length - 1].appendChild(node);
    const selfClose = /\/\s*$/.test(rawAttrs) || tag === 'input' || tag === 'br' || tag === 'img';
    if (!selfClose) stack.push(node);
  }
}

function matches(node, sel) {
  const s = String(sel).trim();
  const attr = /^\[([a-zA-Z_-]+)(?:="([^"]*)")?\]$/.exec(s);
  if (attr) {
    const value = node.getAttribute(attr[1]);
    if (value == null) return false;
    return attr[2] === undefined || value === attr[2];
  }
  if (s.startsWith('#')) return node.id === s.slice(1);
  if (s.startsWith('.')) return String(node.className || '').split(/\s+/).includes(s.slice(1));
  return node.tagName === s.toLowerCase();
}

function findAll(root, selector) {
  const out = [];
  const walk = node => {
    if (matches(node, selector)) out.push(node);
    for (const child of node.children || []) walk(child);
  };
  walk(root);
  return out;
}

let currentFocus = null;
const docListeners = {};

globalThis.document = {
  createElement: tag => new MiniNode(tag),
  createTextNode: text => {
    const node = new MiniNode('#text');
    node.textContent = text;
    return node;
  },
  addEventListener(type, fn) {
    (docListeners[type] = docListeners[type] || []).push(fn);
  },
  removeEventListener() {},
  get activeElement() {
    return currentFocus;
  },
  body: null,
  documentElement: {classList: {toggle() {}, contains() { return false; }}},
  querySelector: selector => (globalThis.document.body && globalThis.document.body.querySelector(selector)) || null,
};

globalThis.document.body = new MiniNode('body');


export {MiniNode, bracedFrom};
function bracedFrom(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) return '';
  const open = src.indexOf('{', start + marker.length);
  if (open < 0) return '';
  let depth = 0;
  let quote = '';
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const prev = i > 0 ? src[i - 1] : '';
    if (quote) {
      if (c === quote && prev !== '\\') quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

