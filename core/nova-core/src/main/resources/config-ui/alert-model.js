/**
 * 告警「配好了没有」——前端这一份
 *
 * 设置页药丸、初始设置完成页那一行，邮件这一路都只认收件与 SMTP 主机这两栏。
 * 判定只留这一份：两处各写各的话，完成页写着「已配」、药丸却是「未配置」。
 *
 * 首页待办走的是服务端 {@code /api/status} 的 {@code alerts.mail}，与这里同一对栏。
 * @param to 收件邮箱
 * @param host SMTP 主机
 * @return {boolean} 两栏都有时为 true
 */
export function mailAlertConfigured(to, host) {
  return !!(String(to || '').trim() && String(host || '').trim());
}
