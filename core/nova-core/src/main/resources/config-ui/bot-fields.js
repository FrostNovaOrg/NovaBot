/** 连接页与设置引导共用：地址一行，HTTP 与 WS 各一行端口和 Token。 */
import {esc} from './core.js';

export function botFieldsHtml(p, parameters = {}, tokenType = 'text') {
  const input = (key, label, value, type = 'text', hint = '') =>
    '<div class="bot-field"><label for="' + p + '-' + key + '">' + label + '</label>'
    + '<input id="' + p + '-' + key + '" type="' + type + '" value="' + esc(value)
    + '" autocomplete="off"' + (key.endsWith('port') ? ' inputmode="numeric"' : '')
    + (hint ? ' placeholder="' + hint + '"' : '') + '></div>';
  return '<div class="bot-fields">'
    + input('addr', '地址', parameters.address ?? '127.0.0.1')
    + '<div class="bot-protocol">'
    + input('hport', 'HTTP 端口', parameters.httpPort ?? '3000')
    + input('htoken', 'HTTP Token', parameters.httpToken ?? '', tokenType, '与机器人配置一致') + '</div>'
    + '<div class="bot-protocol">'
    + input('wport', 'WS 端口', parameters.wsPort ?? '3001')
    + input('wtoken', 'WS Token', parameters.wsToken ?? '', tokenType, '与机器人配置一致') + '</div></div>';
}
