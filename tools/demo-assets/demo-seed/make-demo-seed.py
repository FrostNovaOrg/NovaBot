#!/usr/bin/env python3
"""Generate an isolated demo world for screenshots and response recording.

Four synthetic streamers share three groups and a friend. Archives, minute
details, fan snapshots, monitoring evidence, credentials and weekly deliveries
use dates relative to startup. No online session is fabricated in saved state.
Use --now YYYY-MM-DDTHH:MM:SS for reproducible checks; start after 01:00.
"""

import json
import base64
import hashlib
import sys
from datetime import datetime, timedelta
from pathlib import Path

IDENTITIES = json.loads((Path(__file__).parent / 'synthetic-identities.json').read_text())
DEMO_UID = IDENTITIES['streamers'][0]
DEMO_ROOM = IDENTITIES['rooms'][0]
DEMO_GROUP = IDENTITIES['groups'][0]
DEMO_UNAME = "演示主播小星"
DEMO_PLATFORM = "bilibili"
PUSH_PLATFORM = "qq-onebot"
CHANNEL = "群 " + str(DEMO_GROUP)
VIEWER_UIDS = IDENTITIES['viewers']
USERS = json.loads((Path(__file__).parent / 'datasource.demo.json').read_text())

# Past sessions: (days_ago, hour, minute, duration_min, danmu, gift_yuan, sc_yuan,
#                 enter, like, watched, kind) — kind is normal / cutoff / gap.
# Counts per month go 2, 3, 5, 2, 2 so the monthly trend has ups and downs.
SESSIONS = [
    (152, 20, 5, 154, 812, 86.6, 30.0, 1240, 2103, 5214, "normal"),
    (149, 19, 30, 95, 540, 42.0, 0.0, 830, 1420, 3480, "normal"),
    (121, 20, 0, 181, 1105, 128.8, 50.0, 1720, 2960, 6720, "normal"),
    (118, 21, 15, 132, 766, 64.2, 12.0, 1010, 1875, 4390, "normal"),
    (115, 19, 45, 210, 1402, 156.0, 66.6, 2080, 3405, 8110, "normal"),
    (92, 20, 10, 168, 980, 92.4, 28.0, 1350, 2410, 5890, "normal"),
    (88, 19, 50, 76, 410, 21.0, 0.0, 620, 980, 2600, "normal"),
    (63, 20, 30, 47, 298, 18.8, 0.0, 480, 720, 1980, "cutoff"),
    (60, 20, 0, 175, 1021, 98.6, 30.0, 1440, 2580, 6210, "normal"),
    (58, 21, 30, 118, 690, 55.5, 10.0, 940, 1690, 4020, "gap"),
    (31, 20, 15, 190, 1288, 142.2, 52.0, 1900, 3150, 7540, "normal"),
    (27, 19, 40, 143, 851, 70.0, 20.0, 1170, 2150, 5120, "normal"),
    (9, 20, 5, 162, 1032, 101.3, 36.0, 1490, 2690, 6480, "normal"),
    (3, 19, 55, 128, 744, 58.8, 12.0, 1020, 1780, 4450, "normal"),
]

# The session that also changed its title mid-stream (first entry is the
# opening title, so two entries mean one change on the sessions page).
TITLE_CHANGE_ROW = 2


def ms(moment):
    return int(moment.timestamp() * 1000)


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def session_lines(now):
    records = []
    for index, user in enumerate(USERS):
        rows = list(SESSIONS)
        if index == 0:
            rows.append((0, 0, 0, 40, 426, 48.0, 12.0, 710, 930, 2480, 'normal'))
        else:
            ages = (range(1, 73, 3) if index == 1 else
                    [65, 70, 84, 100, 115, 130, 150, 170] if index == 2 else
                    [3, 8, 15, 22, 29, 40, 55, 65, 77, 90])
            rows = [(age, 20, 0, 90 + n % 4 * 15, 500 + n * 21, 40 + n * 2,
                     12.0, 810 + n * 30, 1200 + n * 55, 3200 + n * 70,
                     'gap' if n == 2 else 'cutoff' if n == 4 else 'normal')
                    for n, age in enumerate(ages)]
        records.extend(streamer_sessions(now, user, rows))
    for record in records:
        record['peaks'] = detail_of(record)['peaks']
    return [compact(row) for row in sorted(records, key=lambda row: row['startTime'])]


def streamer_sessions(now, user, rows):
    lines = []
    for row_index, row in enumerate(rows):
        days_ago, hour, minute, dur_min, danmu, gift, sc, enter, like, watched, kind = row
        start = (now - timedelta(days=days_ago)).replace(hour=hour, minute=minute,
                                                         second=0, microsecond=0)
        end = start + timedelta(minutes=dur_min)
        if days_ago == 0:
            end = now - timedelta(minutes=10)
            start = end - timedelta(minutes=dur_min)

        danmu_users = 1
        gift_users = 1 if gift else 0
        viewers = VIEWER_UIDS[row_index % 3:] + VIEWER_UIDS[:row_index % 3]

        titles = [{"at": ms(start), "title": "晚间歌回·点歌之夜（演示）", "area": "唱见电台"}]
        if row_index == TITLE_CHANGE_ROW:
            titles.append({"at": ms(start + timedelta(minutes=dur_min // 2)),
                           "title": "凌晨加播·聊天回（演示）", "area": "唱见电台"})

        record = {
            "platform": DEMO_PLATFORM,
            "uid": user['uid'],
            "uname": user['uname'],
            "roomId": user['roomId'],
            "startTime": ms(start),
            "endTime": ms(end),
            "durationSeconds": dur_min * 60,
            "metrics": {
                "danmu_count": danmu,
                "gift_value": gift,
                "super_chat_value": sc,
                "enter_count": enter,
                "like_total": like,
                "watched_count": watched,
                "fans_at_start": 2200 + USERS.index(user) * 500 + (180 - days_ago) * 7,
                "follow_count": 20,
                "gift_paid": gift,
                "super_chat_count": 1 if sc else 0,
            },
            "userCounts": {"danmu_users": danmu_users, "gift_users": gift_users},
            "endReason": "CUT_OFF" if kind == "cutoff" else "NORMAL",
            "titles": titles,
            "maintenanceGapSeconds": 1320 if kind == "gap" else 0,
            "userSets": {
                "danmu_users": viewers[:danmu_users],
                "gift_users": viewers[:gift_users],
            },
            "anonymousCounts": {},
            "roomOutageSeconds": 300 if kind == "cutoff" else 0,
            "peaks": {
                "danmu_count": {"at": ms(start + timedelta(minutes=dur_min // 2)),
                                "value": max(3, danmu // 30)},
                "watched_count": {"at": ms(start + timedelta(minutes=dur_min)),
                                  "value": watched},
            },
        }
        lines.append(record)
    return lines


def detail_of(session):
    detail = {key: session[key] for key in ('platform', 'uid', 'uname', 'roomId',
              'startTime', 'endTime', 'durationSeconds', 'metrics', 'userCounts', 'titles')}
    minutes = session['durationSeconds'] // 60
    base = session['startTime'] // 60000 * 60000
    gap_start = base + 30 * 60000
    gap_end = gap_start + session['maintenanceGapSeconds'] * 1000
    weights = [1 + min(m, minutes - 1 - m) // 15 for m in range(minutes)]
    # Missing intervals contribute no invented observations. Other minute sums
    # match the archived totals, including a zero-activity opening minute.
    observed = [m for m in range(minutes) if not gap_start <= base + m * 60000 < gap_end]
    weights[0] = 0
    total = sum(weights[m] for m in observed)
    series = {}
    for metric in ('danmu_count', 'enter_count', 'gift_value', 'super_chat_value'):
        cumulative = 0
        points = {}
        amount = session['metrics'][metric]
        precision = 0 if metric in ('danmu_count', 'enter_count') else 2
        for m in observed:
            previous = round(amount * cumulative / total, precision)
            cumulative += weights[m]
            points[str(base + m * 60000)] = round(round(amount * cumulative / total, precision) - previous, precision)
        series[metric] = points
    series['online_count'] = {str(base + m * 60000): 60 + min(m, minutes - 1 - m) // 10 * 5 for m in observed}
    rankings = {}
    for metric, count in session['userCounts'].items():
        amount = session['metrics']['danmu_count' if metric == 'danmu_users' else 'gift_value']
        precision = 0 if metric == 'danmu_users' else 2
        rankings[metric] = [dict(uid=uid, uname=f'演示观众{VIEWER_UIDS.index(uid) + 1}', face='',
                                 score=round(round(amount * (i + 1) / count, precision)
                                             - round(amount * i / count, precision), precision))
                            for i, uid in enumerate(session['userSets'][metric])]
    rankings['revenue_users'] = [dict(rankings['gift_users'][0],
        score=session['metrics']['gift_value'] + session['metrics']['super_chat_value'])]
    detail['metrics'] = dict(session['metrics'])
    detail['metrics']['online_count'] = max(series['online_count'].values())
    detail.update(version=1, series=series, rankings=rankings,
                  words={'好听': 48, '晚安': 32},
                  highlights=[dict(at=base + 20 * 60000, value=24, ratio=2.8)],
                  gaps=[dict(from_=gap_start, to=gap_end, reason='MAINTENANCE')]
                  if session['maintenanceGapSeconds'] else [],
                  peaks={metric: dict(at=int(max(points, key=points.get)), value=max(points.values()))
                         for metric, points in series.items()},
                  gifts=[dict(name='演示星星', price=0.1, count=round(session['metrics']['gift_value'] * 10))])
    for gap in detail['gaps']:
        gap['from'] = gap.pop('from_')
    return detail


def extra_files(now, sessions):
    """Return seed files in their actual persisted formats, never API responses."""
    files = {}
    pretty = lambda value: json.dumps(value, ensure_ascii=False, indent=2) + '\n'
    snapshots = []
    monitoring = {}
    for index, user in enumerate(USERS):
        for age in range(180, -1, -1):
            at = now - timedelta(days=age, minutes=2)
            snapshots.append(dict(platform=DEMO_PLATFORM, uid=user['uid'], uname=user['uname'],
                                  at=ms(at), metrics={'fans': 2200 + index * 500 + (180 - age) * 7
                                                     + (age % 5) * 2,
                                                     'fans_medal': 120 + index * 30 + (180 - age) // 5,
                                                     'guard': 8 + index + (180 - age) // 60}))
        # Two separate stretches leave a full uncertain day between them.
        monitoring[str(user['uid'])] = [
            dict(from_=ms(now - timedelta(days=170)), to=ms(now - timedelta(days=12)), roomId=user['roomId']),
            dict(from_=ms(now - timedelta(days=10)), to=ms(now - timedelta(minutes=1)), roomId=user['roomId'])]
        for interval in monitoring[str(user['uid'])]:
            interval['from'] = interval.pop('from_')
    files['snapshots.jsonl'] = '\n'.join(compact(row) for row in sorted(snapshots, key=lambda row: row['at'])) + '\n'
    files['data.json'] = pretty({'Monitoring:bilibili': monitoring})
    recent = [row for user in USERS for row in sorted(
        [row for row in sessions if row['uid'] == user['uid']],
        key=lambda row: row['startTime'], reverse=True)[:3 if USERS.index(user) < 2 else 2]]
    for session in recent:
        directory = f"details/bilibili-{session['uid']}-{session['startTime']}"
        detail = detail_of(session)
        files[directory + '/detail.json'] = pretty(detail)
        times = [max(session['startTime'], int(at))
                 for at, count in detail['series']['danmu_count'].items() for _ in range(int(count))]
        viewers = [row['uid'] for row in detail['rankings']['danmu_users'] for _ in range(int(row['score']))]
        files[directory + '/danmu.jsonl'] = '\n'.join(compact(dict(
            at=at, uid=uid, uname=f'演示观众{VIEWER_UIDS.index(uid) + 1}',
            text=('好听！', '欢迎来到演示直播间', '再来一首', '晚安')[i % 4], type='DANMU', recovered=False))
            for i, (at, uid) in enumerate(zip(times, viewers))) + '\n'
    state = json.loads((Path(__file__).parent / 'state.demo.json').read_text())
    state['passkey'] = {f'demo-not-a-real-credential-{i}': dict(
        name=f'演示通行密钥·{name}', publicKey='DEMO-NOT-A-REAL-PUBLIC-KEY', algorithm=-7,
        signCount=i, createdAt=(now - timedelta(days=20 + i)).astimezone().isoformat(),
        **({'lastUsedAt': (now - timedelta(days=1)).astimezone().isoformat()} if i == 1 else {}))
        for i, name in enumerate(('备用设备', '日常设备'))}
    files['state.json'] = pretty(state)
    files['event-stream-tokens.jsonl'] = '\n'.join(compact(dict(
        hash=base64.urlsafe_b64encode(hashlib.sha256(f'demo-not-a-real-token-{i}'.encode()).digest()).decode().rstrip('='),
        label=f'演示只读口令·{label}', issuedAt=ms(now - timedelta(days=30)),
        revokedAt=ms(now - timedelta(days=2)) if i else 0))
        for i, label in enumerate(('演示面板', '已吊销设备'))) + '\n'
    monday = (now - timedelta(days=now.weekday())).replace(hour=10, minute=0, second=0, microsecond=0)
    if monday > now:
        monday -= timedelta(days=7)
    # Persist the preceding issue as sent. Observe the current period without
    # scheduling synthetic sends during startup.
    keys = [f"bilibili:{user['uid']}:qq-onebot:{'GROUP' if t['type'] == 1 else 'FRIEND'}:{t['num']}"
            for user in USERS for t in user['targets']]
    files['weekly-report-deliveries.json'] = pretty(dict(lastSeen=ms(now), period=ms(monday),
        subscriptions=keys, records=[dict(subscription=key, dueAt=ms(monday - timedelta(days=7)),
                                          status='SENT', reason='', updatedAt=ms(monday - timedelta(days=7) + timedelta(minutes=2)))
                                    for key in keys]))
    # Push history is in memory only. The recorder replays these inputs through
    # the real sender and the OneBot transport, with a clock for each send.
    files['push-replay.json'] = pretty([dict(at=ms(now - timedelta(minutes=offset)),
        num=IDENTITIES['groups'][age % 2], summary=f'演示推送·{name}', fail=age == 1)
        for age, offset, name in ((3, 50, '开播通知'), (2, 47, '下播报告'),
                                  (0, 45, '运营周报'), (1, 40, '动态发送失败'))])
    return files


def timeline_lines(now):
    # 已推送与推送失败的时刻都要落在静音时段之外：静音段是起动前 30 分钟到起动后 90 分钟，
    # 静音期间的推送是被丢弃、根本不去发的（PushGate），段里再摆一条「发送失败」就自相矛盾。
    # 这里把三条 PUSH_SENT 挪到起动前 45–50 分钟、PUSH_FAILED 挪到起动前 40 分钟（落在三条
    # 已推送与静音段起点之间），图上「已推送」「静音丢弃」「发送失败」三种不再同屏打架。
    # 静音丢弃那条留在段内：它本来就是静音时段里发生的那件事。
    sent_minutes = (50, 47, 45)
    failed_minute = 40
    muted_minute = 5
    summaries = ("开播通知（演示主播小星）", "下播报告（演示主播小星）", "动态推送（演示主播小星）")

    def event(minutes_ago, type_name, type_text, category, category_text, level,
              channel, text, detail):
        at = now - timedelta(minutes=minutes_ago)
        record = {
            "at": ms(at),
            "type": type_name,
            "typeText": type_text,
            "category": category,
            "categoryText": category_text,
            "level": level,
            "streamer": DEMO_UNAME,
            "channel": channel,
            "text": text,
            "detail": detail,
        }
        return compact(record)

    lines = []
    for offset, summary in zip(sent_minutes, summaries):
        lines.append(event(offset, "PUSH_SENT", "推送成功", "PUSH", "推送", "info",
                           CHANNEL, "已推送：" + summary,
                           {"platform": PUSH_PLATFORM, "elapsed_ms": str(180 + offset * 7)}))
    # 落盘顺序按时刻排：时间线与日志都是按行读的，倒着摆出来列表里的时刻会来回跳
    lines.append(event(failed_minute, "PUSH_FAILED", "推送失败", "PUSH", "推送", "error",
                       CHANNEL, "推送失败：连接超时",
                       {"platform": PUSH_PLATFORM, "summary": "下播报告（演示主播小星）",
                        "elapsed_ms": "10024"}))
    lines.append(event(muted_minute, "PUSH_MUTED", "静音丢弃", "PUSH", "推送", "warn",
                       CHANNEL, "处于静音时段，丢弃了发往" + CHANNEL + "的一条消息",
                       {"summary": "动态推送（演示主播小星）", "platform": PUSH_PLATFORM}))
    return lines, muted_minute, failed_minute


def log_lines(now, muted_minute, failed_minute):
    # Same line shape as logback.xml writes:
    #   %d{yyyy-MM-dd HH:mm:ss.SSS} %5p ${PID:-} --- [%20.20t] %-40.40logger{39} : %msg%n
    pid = "41000"

    def line(moment, level, thread, logger, message):
        return "{} {:>5} {} --- [{:>20}] {:<40} : {}".format(
            moment.strftime("%Y-%m-%d %H:%M:%S.") + f"{moment.microsecond // 1000:03d}",
            level, pid, thread, logger, message)

    def at(minutes_ago, second=0, milli=0):
        moment = (now - timedelta(minutes=minutes_ago)).replace(second=second, microsecond=milli * 1000)
        return moment

    sender = "o.f.nova.core.sender.NovaMessageSender"
    bilibili = "o.f.nova.bilibili.LiveRoomService"

    return [
        line(at(50, 4, 215), "INFO", "nova-core-4", sender,
             "已推送 1 条消息到 " + CHANNEL + "，耗时 263 ms"),
        line(at(50, 4, 640), "INFO", "bilibili-scheduler-1", bilibili,
             "检测到开播: " + DEMO_UNAME + "（uid " + str(DEMO_UID) + "）"),
        line(at(45, 51, 82), "INFO", "nova-core-2", sender,
             "已推送 1 条消息到 " + CHANNEL + "，耗时 201 ms"),
        # 与时间线里 PUSH_FAILED 那条同一分钟：日志页点「看这一刻」要落在这里
        line(at(failed_minute, 9, 317), "ERROR", "nova-core-1", sender,
             "推送消息发送失败: 连接超时, 目标: " + CHANNEL + ", 已重试 3 次"),
        line(at(failed_minute, 9, 318), "ERROR", "nova-core-1", sender,
             "java.net.SocketTimeoutException: connect timed out"),
        # 与时间线里 PUSH_MUTED 那条同一分钟
        line(at(muted_minute, 30, 114), "WARN", "nova-core-3", sender,
             "处于静音时段, 已丢弃消息: [群] " + str(DEMO_GROUP) + ": 动态推送（演示主播小星）"),
    ]


def parse_moment(text):
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M",
                "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


def main():
    usage = "usage: make-demo-seed.py [--now YYYY-MM-DDTHH:MM:SS] <work-dir>"
    args = sys.argv[1:]
    moment_text = None
    if args and args[0] == "--now":
        if len(args) < 2:
            print(usage, file=sys.stderr)
            return 2
        moment_text = args[1]
        args = args[2:]

    if len(args) != 1:
        print(usage, file=sys.stderr)
        return 2

    work = Path(args[0]).resolve()
    if not work.is_dir():
        print(f"not a directory: {work}", file=sys.stderr)
        return 2

    here = Path(__file__).resolve().parent
    now = parse_moment(moment_text) if moment_text is not None else datetime.now()
    if now is None:
        print(f"bad --now value: {moment_text}", file=sys.stderr)
        return 2

    # 样例按「起动前几十分钟」排，起动在 00:59 之前就有一截落在前一天、却被写进当天的文件，
    # 列表里的时刻和日期对不上。因此零点到一点不排种子；过了 01:00 起动，样例才都落在当天。
    if now.hour == 0:
        print(f"拒绝在 {now:%H:%M} 排种子：样例按起动前几十分钟排，00:00–00:59 起动会有样例"
              f"落在前一天、却写进当天的文件；过了 01:00 再起", file=sys.stderr)
        return 2

    today = now.date()

    def fresh_write(path, content):
        if path.exists():
            print(f"refusing to overwrite existing file: {path}", file=sys.stderr)
            raise SystemExit(1)
        path.write_text(content, encoding="utf-8")

    # 1) static sample files
    fresh_write(work / "datasource.json", (here / "datasource.demo.json").read_text(encoding="utf-8"))

    # 2) config rendered for this start moment
    template = (here / "application.demo.yml").read_text(encoding="utf-8")
    quiet_start = (now - timedelta(minutes=30)).strftime("%H:%M")
    quiet_end = (now + timedelta(minutes=90)).strftime("%H:%M")
    fresh_write(work / "application.yml", template
                .replace("__QUIET_START__", quiet_start)
                .replace("__QUIET_END__", quiet_end)
                .replace("__ACCEPTED_AT__", now.strftime("%Y-%m-%dT%H:%M:%S")))

    # 3) past sessions
    sessions = session_lines(now)
    fresh_write(work / "sessions.jsonl", "\n".join(sessions) + "\n")
    for name, content in extra_files(now, [json.loads(line) for line in sessions]).items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        fresh_write(path, content)

    # 4) today's timeline
    timeline_dir = work / "timeline"
    timeline_dir.mkdir(exist_ok=True)
    events, muted_minute, failed_minute = timeline_lines(now)
    fresh_write(timeline_dir / f"{today.isoformat()}.jsonl", "\n".join(events) + "\n")
    for age in (1, 2, 3):
        day = now - timedelta(days=age)
        fresh_write(timeline_dir / f"{day.date().isoformat()}.jsonl",
                    "\n".join(timeline_lines(day)[0]) + "\n")

    # 5) today's engineering log (the appender appends after these lines)
    log_dir = work / "logs" / today.strftime("%Y-%m")
    log_dir.mkdir(parents=True, exist_ok=True)
    fresh_write(log_dir / f"novabot-{today.isoformat()}.log", "\n".join(log_lines(now, muted_minute, failed_minute)) + "\n")

    print(f"seeded demo data into {work}: {len(USERS)} streamers, 3 groups, 1 friend,"
          f" {len(sessions)} sessions, {sum(3 if i < 2 else 2 for i in range(len(USERS)))} with details, {181 * len(USERS)} snapshots,"
          f" {len(events) * 4} timeline entries, quiet {quiet_start}-{quiet_end}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
