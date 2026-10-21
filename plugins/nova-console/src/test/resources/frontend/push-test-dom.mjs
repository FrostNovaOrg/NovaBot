export function installPushDom() {
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
      this.disabled = false;
      this.checked = false;
      this.hidden = false;
      this.title = '';
      this.type = '';
      this.value = '';
      this.classList = {
        contains: name => this.className.split(/\s+/).includes(name),
        toggle: (name, force) => {
          const names = new Set(this.className.split(/\s+/).filter(Boolean));
          const on = force ?? !names.has(name);
          if (on) names.add(name); else names.delete(name);
          this.className = [...names].join(' ');
        },
      };
    }

    get textContent() {
      return this._text;
    }

    set textContent(value) {
      this._text = String(value ?? '');
    }

    get innerHTML() {
      return this._html;
    }

    set innerHTML(html) {
      this._html = String(html ?? '');
      this.children = [];
      const stack = [this];
      const tags = /<(\/?)([\w-]+)([^>]*)>/g;
      let match;
      while ((match = tags.exec(this._html))) {
        if (match[1]) { if (stack.length > 1) stack.pop(); continue; }
        const node = new MiniNode(match[2]);
        const attrs = /([\w-]+)(?:="([^"]*)")?/g;
        let attr;
        while ((attr = attrs.exec(match[3]))) {
          node.setAttribute(attr[1], attr[2] ?? '');
          if (['checked', 'disabled', 'hidden'].includes(attr[1])) node[attr[1]] = true;
        }
        stack[stack.length - 1].appendChild(node);
        if (!['input', 'img', 'br', 'hr', 'meta', 'link'].includes(node.tagName)) stack.push(node);
      }
    }

    get href() {
      return this.attributes.href || '';
    }

    set href(value) {
      this.attributes.href = String(value);
    }

    appendChild(child) {
      if (!child) return child;
      child.parentNode = this;
      this.children.push(child);
      return child;
    }

    append(...items) {
      for (const item of items) {
        if (typeof item === 'string') {
          const text = new MiniNode('#text');
          text.textContent = item;
          this.appendChild(text);
        } else {
          this.appendChild(item);
        }
      }
    }

    querySelector(selector) {
      return findAll(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
      return findAll(this, selector);
    }

    setAttribute(name, value) {
      this.attributes[name] = String(value);
      if (name === 'id') this.id = String(value);
      if (name === 'class') this.className = String(value);
      if (name === 'type') this.type = String(value);
    }

    getAttribute(name) {
      if (name === 'id' && this.id) return this.id;
      if (name === 'class' && this.className) return this.className;
      return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    }

    removeEventListener(type, fn) {
      const list = this.listeners[type] || [];
      const index = list.indexOf(fn);
      if (index >= 0) list.splice(index, 1);
    }

    dispatchEvent(event) {
      const list = (this.listeners[event.type] || []).slice();
      return Promise.all(list.map(fn => fn.call(this, event)));
    }

    focus() { document.activeElement = this; }

    closest(selector) {
      return matches(this, selector) ? this : this.parentNode?.closest(selector);
    }

    click() {
      return this.dispatchEvent({type: 'click', target: this, stopPropagation() {}});
    }
  }

  function findAll(root, selector) {
    const sel = String(selector).trim();
    const out = [];
    const walk = node => {
      if (matches(node, sel)) out.push(node);
      for (const child of node.children || []) walk(child);
    };
    walk(root);
    return out;
  }

  function matches(node, sel) {
    const attr = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(sel);
    if (attr) return node.getAttribute(attr[1]) !== null && (attr[2] === undefined || node.getAttribute(attr[1]) === attr[2]);
    if (sel.startsWith('#')) return node.id === sel.slice(1);
    if (sel.startsWith('.')) return String(node.className || '').split(/\s+/).includes(sel.slice(1));
    return node.tagName === sel.toLowerCase();
  }

  const byId = new Map();

  function idNode(id) {
    if (!byId.has(id)) {
      const node = new MiniNode('div');
      node.id = id;
      byId.set(id, node);
    }
    return byId.get(id);
  }

  const htmlClasses = new Set();

  globalThis.document = {
    querySelector: selector => {
      const sel = String(selector);
      return sel.startsWith('#') ? idNode(sel.slice(1)) : null;
    },
    createElement: tag => new MiniNode(tag),
    createTextNode: text => {
      const node = new MiniNode('#text');
      node.textContent = text;
      return node;
    },
    addEventListener(type, fn) { (document.listeners[type] ||= []).push(fn); },
    listeners: {},
    hidden: false,
    removeEventListener() {},
    body: idNode('body'),
    activeElement: null,
    documentElement: {
      classList: {
        toggle: (name, on) => {
          if (on) htmlClasses.add(name);
          else htmlClasses.delete(name);
        },
        contains: name => htmlClasses.has(name),
      },
    },
  };

  globalThis.location = {hash: '', href: '', replace() {}, reload() {}};
  return {idNode, node: selector => document.querySelector(selector)};


}
