import {esc} from './core.js';

const timers = new WeakMap();

export function showLinkResult(box, message = '', kind = '', details = '', onClear) {
  clearTimeout(timers.get(box));
  timers.delete(box);
  box.className = 'lc-result' + (kind ? ' ' + kind : '');
  box.hidden = !message;
  if (details) box.innerHTML = '<b>' + esc(message) + '</b>' + details;
  else box.textContent = message;
  if (kind === 'ok') {
    timers.set(box, setTimeout(() => {
      showLinkResult(box);
      onClear?.();
    }, 4000));
  }
}

export function trackLinkEdits(fields, indicator) {
  const update = () => {
    indicator.hidden = !fields.some(field => field.value !== field.defaultValue);
  };
  fields.forEach(field => field.addEventListener('input', update));
  update();
  return update;
}
