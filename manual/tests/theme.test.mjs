import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../assets/theme.js', import.meta.url), 'utf8');
const boot = execFileSync('python3', ['-c',
  'import runpy; print(runpy.run_path("manual/build.py")["_THEME_BOOT"])'],
{ encoding: 'utf8' }).replace(/<\/?script>/g, '');
const key = 'novabot-theme';

function page(script, saved = {}, { denied = false, button = true } = {}) {
  const values = new Map(Object.entries(saved));
  const attrs = new Map();
  const events = new Map();
  let click;
  const btn = {
    querySelector: selector => ({ getAttribute: () => selector }),
    setAttribute: (name, value) => { btn[name] = value; },
    addEventListener: (name, listener) => { if (name === 'click') click = listener; },
  };
  const window = { addEventListener: (name, listener) => events.set(name, listener) };
  const localStorage = {
    getItem: name => { if (denied) throw Error('unavailable'); return values.get(name) ?? null; },
    setItem: (name, value) => { if (denied) throw Error('unavailable'); values.set(name, value); },
    removeItem: name => { if (denied) throw Error('unavailable'); values.delete(name); },
  };
  const document = {
    documentElement: {
      setAttribute: (name, value) => attrs.set(name, value),
      removeAttribute: name => attrs.delete(name),
      getAttribute: name => attrs.get(name) ?? null,
    },
    querySelector: () => button ? btn : null,
  };
  vm.runInNewContext(script, { window, localStorage, document });
  return { attrs, values, btn, click: () => click(), storage: k => events.get('storage')({ key: k }) };
}

test('控制台选的深色或浅色，手册首帧与按钮都用同一档', () => {
  for (const mode of ['dark', 'light']) {
    for (const script of [boot, source]) {
      const p = page(script, { [key]: mode });
      assert.equal(p.attrs.get('data-theme'), mode);
      assert.equal(p.attrs.get('data-mode'), mode);
    }
  }
});

test('旧手册选择、空值和无效值回到系统，首帧不锁定深浅色', () => {
  for (const script of [boot, source]) {
    for (const saved of [{}, { 'novabot-manual-theme': 'dark' }, { [key]: 'system' }, { [key]: 'invalid' }]) {
      const p = page(script, saved);
      assert.equal(p.attrs.has('data-theme'), false);
      assert.equal(p.attrs.get('data-mode'), 'system');
    }
  }
});

test('按钮按深色、浅色、系统循环，系统选择删除共用键并更新提示', () => {
  const p = page(source);
  for (const mode of ['dark', 'light', 'system', 'dark']) {
    p.click();
    assert.equal(p.attrs.get('data-mode'), mode);
    assert.equal(p.attrs.get('data-theme'), mode === 'system' ? undefined : mode);
    assert.equal(p.values.get(key), mode === 'system' ? undefined : mode);
    assert.equal(p.btn['aria-label'], '.opt-' + mode);
  }
});

test('控制台在另一页改主题或清空存储，手册主题与按钮提示跟着变', () => {
  const p = page(source, { [key]: 'dark' });
  p.values.set(key, 'light');
  p.storage(key);
  assert.equal(p.attrs.get('data-theme'), 'light');
  assert.equal(p.btn['aria-label'], '.opt-light');
  p.values.clear();
  p.storage(null);
  assert.equal(p.attrs.has('data-theme'), false);
  assert.equal(p.attrs.get('data-mode'), 'system');
  assert.equal(p.btn['aria-label'], '.opt-system');
});

test('不相干的存储事件不改主题，同名事件现读本地选择', () => {
  const p = page(source, { [key]: 'dark' });
  p.values.set(key, 'light');
  p.storage('unrelated');
  assert.equal(p.attrs.get('data-theme'), 'dark');
  p.values.set(key, 'dark');
  p.storage(key);
  assert.equal(p.attrs.get('data-theme'), 'dark');
});

test('浏览器拒绝存储时仍可切换，系统模式仍移除主题属性', () => {
  for (const script of [boot, source]) {
    const p = page(script, {}, { denied: true });
    assert.equal(p.attrs.has('data-theme'), false);
  }
  const p = page(source, {}, { denied: true });
  p.click();
  assert.equal(p.attrs.get('data-theme'), 'dark');
  p.click();
  p.click();
  assert.equal(p.attrs.has('data-theme'), false);
});

test('页面没有按钮时仍监听其他页面的主题变化', () => {
  const p = page(source, {}, { button: false });
  p.values.set(key, 'dark');
  p.storage(key);
  assert.equal(p.attrs.get('data-theme'), 'dark');
});
