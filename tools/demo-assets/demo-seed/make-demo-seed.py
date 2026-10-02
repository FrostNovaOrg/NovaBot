#!/usr/bin/env python3
"""Preset sample data into a demo instance's working directory.

Used by start-demo.sh when DEMO_SEED=1. Takes the throwaway working directory
as its only argument and writes:

  datasource.json   one demo streamer (nickname says it is a demo) pushing to
                    one demo group            (copied from datasource.demo.json)
  state.json        the demo group's command switch, revenue visibility and
                    subscriptions             (copied from state.demo.json)
  application.yml   quiet hours around the start moment, three fake alert
                    channels, agreement record, and switches that keep the
                    instance from reaching the live platform
                                                    (rendered from application.demo.yml)
  sessions.jsonl    past sessions dated back from the start day, spanning more
                    than three months, with varying monthly totals, one session
                    cut off by the platform and one with a collection gap
  timeline/<today>.jsonl   today's push records: PUSH_SENT x3, PUSH_MUTED x1,
                          PUSH_FAILED x1
  logs/<YYYY-MM>/novabot-<today>.log   today's engineering log with ERROR and
                          WARN lines; the ERROR sits in the same minute as the
                          PUSH_FAILED timeline entry, so the log page's "view
                          this moment" jump lands exactly on it

Dates are computed from the start moment; nothing date-like is hardcoded. All
names and numbers are made up for the demo and use ranges the platforms do not
hand out, so no real account or group can be hit by accident.
"""

import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

DEMO_UID = 9200000001          # demo streamer uid; far above any issued bilibili uid
DEMO_ROOM = 9210000001         # demo room id, same idea
DEMO_GROUP = 910000001         # demo QQ group number (placeholder range)
DEMO_UNAME = "演示主播小星"
DEMO_PLATFORM = "bilibili"
PUSH_PLATFORM = "qq-onebot"
CHANNEL = "群 " + str(DEMO_GROUP)
VIEWER_UIDS = [9300000000 + i for i in range(1, 9)]  # demo viewer uids, 93-billion range

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
    lines = []
    for row_index, row in enumerate(SESSIONS):
        days_ago, hour, minute, dur_min, danmu, gift, sc, enter, like, watched, kind = row
        start = (now - timedelta(days=days_ago)).replace(hour=hour, minute=minute,
                                                         second=0, microsecond=0)
        end = start + timedelta(minutes=dur_min)

        danmu_users = min(len(VIEWER_UIDS), max(2, danmu // 180))
        gift_users = min(len(VIEWER_UIDS), max(1, round(gift / 20)) if gift else 0)

        titles = [{"at": ms(start), "title": "晚间歌回·点歌之夜（演示）", "area": "唱见电台"}]
        if row_index == TITLE_CHANGE_ROW:
            titles.append({"at": ms(start + timedelta(minutes=dur_min // 2)),
                           "title": "凌晨加播·聊天回（演示）", "area": "唱见电台"})

        record = {
            "platform": DEMO_PLATFORM,
            "uid": DEMO_UID,
            "uname": DEMO_UNAME,
            "roomId": DEMO_ROOM,
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
            },
            "userCounts": {"danmu_users": danmu_users, "gift_users": gift_users},
            "endReason": "CUT_OFF" if kind == "cutoff" else "NORMAL",
            "titles": titles,
            "maintenanceGapSeconds": 1320 if kind == "gap" else 0,
            "userSets": {
                "danmu_users": VIEWER_UIDS[:danmu_users],
                "gift_users": VIEWER_UIDS[:gift_users],
            },
            "roomOutageSeconds": 300 if kind == "cutoff" else 0,
            "peaks": {
                "danmu_count": {"at": ms(start + timedelta(minutes=dur_min // 2)),
                                "value": max(3, danmu // 30)},
                "watched_count": {"at": ms(start + timedelta(minutes=dur_min)),
                                  "value": watched},
            },
        }
        lines.append(compact(record))
    return lines


def timeline_lines(now):
    # 已推送的时刻要落在静音时段之外：静音段是起动前 30 分钟到起动后 90 分钟，
    # 这里把三条 PUSH_SENT 挪到起动前 45–50 分钟，图上「已推送」和「静音」不再同屏打架。
    sent_minutes = (50, 47, 45)
    muted_minute = 5
    failed_minute = 3
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
    lines.append(event(muted_minute, "PUSH_MUTED", "静音丢弃", "PUSH", "推送", "warn",
                       CHANNEL, "处于静音时段，丢弃了发往" + CHANNEL + "的一条消息",
                       {"summary": "动态推送（演示主播小星）", "platform": PUSH_PLATFORM}))
    lines.append(event(failed_minute, "PUSH_FAILED", "推送失败", "PUSH", "推送", "error",
                       CHANNEL, "推送失败：连接超时",
                       {"platform": PUSH_PLATFORM, "summary": "下播报告（演示主播小星）",
                        "elapsed_ms": "10024"}))
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
        # 与时间线里 PUSH_MUTED 那条同一分钟
        line(at(muted_minute, 30, 114), "WARN", "nova-core-3", sender,
             "处于静音时段, 已丢弃消息: [群] " + str(DEMO_GROUP) + ": 动态推送（演示主播小星）"),
        # 与时间线里 PUSH_FAILED 那条同一分钟：日志页点「看这一刻」要落在这里
        line(at(failed_minute, 9, 317), "ERROR", "nova-core-1", sender,
             "推送消息发送失败: 连接超时, 目标: " + CHANNEL + ", 已重试 3 次"),
        line(at(failed_minute, 9, 318), "ERROR", "nova-core-1", sender,
             "java.net.SocketTimeoutException: connect timed out"),
    ]


def main():
    if len(sys.argv) != 2:
        print("usage: make-demo-seed.py <work-dir>", file=sys.stderr)
        return 2

    work = Path(sys.argv[1]).resolve()
    if not work.is_dir():
        print(f"not a directory: {work}", file=sys.stderr)
        return 2

    here = Path(__file__).resolve().parent
    now = datetime.now()
    today = now.date()

    def fresh_write(path, content):
        if path.exists():
            print(f"refusing to overwrite existing file: {path}", file=sys.stderr)
            raise SystemExit(1)
        path.write_text(content, encoding="utf-8")

    # 1) static sample files
    fresh_write(work / "datasource.json", (here / "datasource.demo.json").read_text(encoding="utf-8"))
    fresh_write(work / "state.json", (here / "state.demo.json").read_text(encoding="utf-8"))

    # 2) config rendered for this start moment
    template = (here / "application.demo.yml").read_text(encoding="utf-8")
    quiet_start = (now - timedelta(minutes=30)).strftime("%H:%M")
    quiet_end = (now + timedelta(minutes=90)).strftime("%H:%M")
    fresh_write(work / "application.yml", template
                .replace("__QUIET_START__", quiet_start)
                .replace("__QUIET_END__", quiet_end)
                .replace("__ACCEPTED_AT__", now.strftime("%Y-%m-%dT%H:%M:%S")))

    # 3) past sessions
    fresh_write(work / "sessions.jsonl", "\n".join(session_lines(now)) + "\n")

    # 4) today's timeline
    timeline_dir = work / "timeline"
    timeline_dir.mkdir(exist_ok=True)
    events, muted_minute, failed_minute = timeline_lines(now)
    fresh_write(timeline_dir / f"{today.isoformat()}.jsonl", "\n".join(events) + "\n")

    # 5) today's engineering log (the appender appends after these lines)
    log_dir = work / "logs" / today.strftime("%Y-%m")
    log_dir.mkdir(parents=True, exist_ok=True)
    fresh_write(log_dir / f"novabot-{today.isoformat()}.log", "\n".join(log_lines(now, muted_minute, failed_minute)) + "\n")

    print(f"seeded demo data into {work}: streamer {DEMO_UNAME} (uid {DEMO_UID}) -> {CHANNEL},"
          f" {len(SESSIONS)} sessions, {len(events)} timeline entries, quiet {quiet_start}-{quiet_end}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
