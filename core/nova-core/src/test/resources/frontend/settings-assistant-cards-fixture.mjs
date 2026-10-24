/** 管理员在常用设置中填写模型密钥；开启确认后的草稿可保存并在重进页面后显示。 */
import {readFileSync} from 'node:fs';
import {MiniNode, bracedFrom} from './layout-dom.mjs';
import * as model from '../../../main/resources/config-ui/settings-model.js';

// 复用布局夹具的元素，补齐设置表单用到的 DOM 操作。
MiniNode.prototype.append = function (...nodes) { nodes.forEach(node => this.appendChild(node)); };
MiniNode.prototype.replaceChildren = function (...nodes) { this.children = []; this.append(...nodes); };
const el = (tag, cls = '') => {
  const node = new MiniNode(tag);
  node.className = cls;
  node.dataset = {};
  return node;
};
const $ = selector => document.querySelector(selector);
for (const id of ['groups', 'save']) {
  const node = el('div');
  node.id = id;
  document.body.appendChild(node);
}
const ui = new URL('../../../main/resources/config-ui/', import.meta.url);
const source = name => readFileSync(new URL(name, ui), 'utf8');
const noop = () => {};
const failures = [];
let checks = 0;
function eq(actual, expected, what) {
  checks++;
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    failures.push(what + '：得到 ' + JSON.stringify(actual) + '，应为 ' + JSON.stringify(expected));
  }
}

// 每项都带服务端的 level；未标级别的字段为 ADVANCED，而不是省略这个属性。
const fields = [
  ['enabled', '启用智能助手', 'boolean', 'false'],
  ['base-url', '模型接口地址', 'text', 'https://api.deepseek.com'],
  ['model', '模型名称', 'text', 'deepseek-flash'],
  ['monthly-budget-yuan', '每月金额上限', 'number', '30'],
  ['daily-questions-per-account', '每个账号每日提问次数', 'integer', '20'],
  ['answer-limit', '回答输出上限', 'integer', '800'],
  ['price-input-yuan-per-million', '未命中缓存的输入单价', 'number', '2.2'],
  ['price-cached-input-yuan-per-million', '命中缓存的输入单价', 'number', '0.05'],
  ['price-output-yuan-per-million', '输出单价', 'number', '8.6'],
  ['max-tool-calls', '每问查数次数上限', 'integer', '6'],
  ['question-input-limit', '每问累计输入上限', 'integer', '30000'],
  ['excluded-uids', '观众拒绝名单', 'list', null],
].map(([key, label, widget, defaultValue], order) => ({
  name: 'novabot.assistant.' + key, label, widget, defaultValue, order,
  group: 'assistant', level: 'ADVANCED', effect: 'RESTART', sensitive: false,
}));
const group = {group: 'assistant', title: '智能助手', advanced: false, allRestart: true, fields};
const store = {role: 'ADMIN', tab: 'settings', schema: [group], ungrouped: [],
  values: {}, dirty: {}, legacy: {}};
let saved = {};
let accepted = false;
const confirmations = [];
const requests = [];
const api = async (path, options) => {
  requests.push({path, options});
  if (path === '/assistant/acknowledge') accepted = true;
  if (path === '/assistant/status') return {
    available: false, missing: [], keySet: false, spentYuan: '0', monthlyBudgetYuan: '30',
    acknowledgementVersion: 'current', acknowledgementText: '配置者说明正文',
    acknowledgement: accepted ? {version: 'current', actor: 'local-admin', at: '2026-01-01'} : null,
  };
  if (path === '/schema') return {groups: store.schema, ungrouped: []};
  if (path === '/values') {
    if (options?.method === 'POST') {
      saved = {...saved, ...JSON.parse(options.body)};
      return {success: true};
    }
    return {values: {...saved}};
  }
  return {};
};
const ask = async options => { confirmations.push(options); return true; };
const assistantSource = source('settings-assistant.js').replace(/^import .*;\s*$/gm, '').replace(/export /g, '');
const assistant = new Function('$', 'api', 'el', 'ask', 'store', assistantSource
  + '\nreturn {assistantCards, confirmAssistantEnable};')($, api, el, ask, store);
const switchControl = new Function('el', bracedFrom(source('core.js'), 'export function switchControl(')
  .replace(/^export /, '') + '\nreturn switchControl;')(el);

// 运行页面的真实分组、建组、建行与卡片代码；外观、导航和其他功能的卡片使用桩。
const env = {...model, ...assistant, el, $, api, ask, store, switchControl,
  ALERT_GROUP: 'alert', AUTH_GROUP: 'auth', AUTH_CARD_FIELDS: new Set(), cardFields: () => new Set(),
  closeHelp: noop, stopWatchingGroups: noop, filterSettings: noop, recountChanged: noop,
  buildThemeGroup: () => el('div'), buildUngrouped: () => null, buildNav: noop,
  memberGroup: () => null, agreementDangerZone: () => el('div'),
  alertCards: () => el('section', 'alert-cards'), authCards: () => el('section', 'auth-cards'),
  markDirty: noop, showIssues: noop, say: (message, tone) => { if (tone === 'err') failures.push(message); },
  saveTarget: () => 'values', load: how => load(how),
};
const declarations = ['function valuesOf(', 'function buildControl(', 'function buildRow(',
  'function buildGroup(', 'export function renderGeneral(', 'export async function save(']
  .map(marker => bracedFrom(source('settings.js'), marker).replace(/^export /, '')).join('\n');
const page = new Function(...Object.keys(env), 'let arranged;\n' + declarations
  + '\nreturn {renderGeneral, save};')(...Object.values(env));
const loadEnv = {...model, $, api, store, renderGeneral: page.renderGeneral,
  say: env.say, parseHash: () => ({name: 'settings', card: ''}), focusCard: noop,
  reloadChangeSources: noop, renderStatus: noop, renderConfigPath: noop,
  refreshPages: noop, refreshHome: noop, fillBotForms: noop, applyConnectionVocab: noop,
  markDirty: noop, notePlatformsArrived: noop,
};
const load = new Function(...Object.keys(loadEnv), bracedFrom(source('main.js'), 'export async function load(')
  .replace(/^export /, '') + '\nreturn load;')(...Object.values(loadEnv));

page.renderGeneral();
const key = $('#assistant-key');
eq(!!key, true, '全部普通项为高级时，管理员仍能找到模型密钥输入框');
eq(key?.closest('.setgrp')?.dataset.grp, 'assistant', '模型密钥位于常用的智能助手组');
eq($('#adv-groups')?.contains(key), false, '模型密钥不在收着的高级区');
eq($('#adv-groups')?.classList.contains('hide'), true, '高级区默认收起');

// 告警和登录与安全即使没有普通行，也沿用同一条按组保留卡片的路径。
store.schema = ['alert', 'auth'].map(id => ({group: id, title: id, fields: [], advanced: false}));
page.renderGeneral();
eq(!!$('.alert-cards') && !!$('.auth-cards'), true, '没有普通行的告警和登录安全组仍挂卡');

// 当前配置的五项常用字段：从未存过智能助手设置开始，确认开启、保存、重新进入。
store.schema = [{...group, fields: fields.map((field, i) => ({...field, level: i < 5 ? 'COMMON' : 'ADVANCED'}))}];
await load();
const rowFor = name => $('#groups').querySelectorAll('.setitem').find(row => row.dataset.name === name);
let enabledRow = rowFor('novabot.assistant.enabled');
eq(enabledRow.querySelector('input').checked, false, '首次进入时默认关闭');
const input = enabledRow.querySelector('input');
input.checked = true;
for (const change of input.listeners.change) await change();
eq(confirmations[0]?.confirmText, '我已了解，开启', '开启前展示配置者说明并确认');
eq(accepted, true, '确认已提交');
eq(store.dirty['novabot.assistant.enabled'], 'true', '确认开启进入待保存草稿');
await page.save();
eq(requests.find(r => r.path === '/values' && r.options?.method === 'POST')?.options.body,
  '{"novabot.assistant.enabled":"true"}', '保存提交开启值');
store.values = {};
store.dirty = {};
await load();
enabledRow = rowFor('novabot.assistant.enabled');
eq(enabledRow.querySelector('input').checked, true, '重新进入设置后开关保持开启');
eq(enabledRow.querySelector('.chgdot').classList.contains('hide'), false, '保存后显示已修改');
eq(enabledRow.querySelector('.defline').classList.contains('hide'), false, '保存后显示默认值与恢复默认');
for (const name of ['answer-limit', 'question-input-limit']) {
  const row = rowFor('novabot.assistant.' + name);
  eq(row.querySelector('input').type, 'number', name + ' 使用数字输入框');
  eq(row.dataset.changed, '0', name + ' 默认值不标为已修改');
}

console.log('跑了 ' + checks + ' 格，红 ' + failures.length + ' 格');
for (const failure of failures) console.log('  红：' + failure);
process.exit(failures.length ? 1 : 0);
