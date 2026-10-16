export const walk = node => node ? [node, ...node.children.flatMap(walk)] : [];
export function enhance(node) {
  node.style.setProperty ||= function(key, value) { this[key] = String(value); };
  node.dataset ||= {};
  node.className ||= '';
  node.classList.add = (...names) => { node.className += ' ' + names.join(' '); };
  node.querySelector ||= selector => walk(node).slice(1).find(child => selector.startsWith('.') ? child.className.split(' ').includes(selector.slice(1)) : child.id === selector.slice(1)) || null;
  node.querySelectorAll ||= selector => walk(node).slice(1).filter(child => child.tagName === selector);
  node.append = (...children) => children.forEach(child => node.appendChild(typeof child === 'string' ? Object.assign(document.createElement('span'), {textContent: child}) : child));
  node.replaceChildren = (...children) => { node.innerHTML = ''; node.append(...children); };
  node.contains = other => walk(node).includes(other);
  node.getBoundingClientRect = () => ({width: 640, height: 180});
  node.focus = () => { document.activeElement = node; };
  node.scrollIntoView = () => { node.scrolled = true; };
  node.showModal = () => { node.open = true; };
  node.close = () => { node.open = false; node.fire('close'); };
  node.fire = (type, fields = {}) => {
    const listeners = node.listeners[type];
    const event = {preventDefault() {}, target: node, ...fields};
    return Promise.all((Array.isArray(listeners) ? listeners : listeners ? [listeners] : []).map(fn => fn(event)));
  };
  return node;
}
export function installCharts(doc) {
  doc.createElementNS = (_, tag) => doc.createElement(tag);
  const observers = [];
  globalThis.ResizeObserver = class {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe(target) { this.target = target; }
    disconnect() { this.disconnected = true; }
  };
  return observers;
}
