#!/usr/bin/env python3
"""演示实例用的假 OneBot HTTP 端点（只用 Python 标准库）。

start-demo.sh 带种子起动时把它开在种子配的地址端口上（demo-seed/application.demo.yml
里的 one-bot-address / one-bot-http-port，现为 127.0.0.1:39000），等它连得上再起
NovaBot：启动那次体检只在程序起好时跑一次，错过了要等 300 秒才有下一次。

只答 NovaBot 会问的那几支，外层 retcode 为 0、数据放 data：
  体检   get_version_info / get_login_info / get_status
  名单   get_group_list / get_friend_list / get_group_member_list / get_group_member_info
  发送   send_group_msg / send_private_msg
其余接口一律回一份空 data，不回错误码——不验令牌，也不挑请求方法。

名字与号段都是编的，取平台不会发出去的那段，跟 make-demo-seed.py 里那批同一个规矩：
随便点也点不到真账号真群。种子里 websocket 是关的，这个端点不答 Websocket。
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 与 demo-seed 里那批同一个规矩：号取平台不发的号段，名字说清是演示
NICKNAME = "演示机器人"
USER_ID = 9400000001
GROUP_ID = 910000001          # 与 application.demo.yml 里告警群、种子的推送目标同一个
GROUP_NAME = "演示群"
FRIEND_ID = 9400000002
FRIEND_NAME = "演示好友"
APP_VERSION = "9.9.9"

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 39000          # 与 demo-seed/application.demo.yml 里的 one-bot-http-port 同一个


def data_of(path):
    """按接口名回 data 里那一层。名单与发送类照 FakeOneBotHttpServer 的那份应答表答上。

    发送类回 message_id，名单类回数组或对象——形状不能错：取数那头按方法声明的返回类型
    分流，名单接口回成对象会安静地拿到 null。
    """
    if path == "/get_version_info":
        return {"app_version": APP_VERSION}
    if path == "/get_login_info":
        return {"nickname": NICKNAME, "user_id": USER_ID}
    if path == "/get_status":
        return {"good": True, "online": True}
    if path == "/get_group_member_list":
        return [{"user_id": USER_ID, "nickname": NICKNAME, "card": ""}]
    if path == "/get_group_member_info":
        return {"role": "member"}
    if path == "/get_group_list":
        return [{"group_id": GROUP_ID, "group_name": GROUP_NAME, "member_count": 1}]
    if path == "/get_friend_list":
        return [{"user_id": FRIEND_ID, "nickname": FRIEND_NAME, "remark": ""}]
    if path in ("/send_group_msg", "/send_private_msg"):
        return {"message_id": "1"}
    return {}


def reply(path):
    """整包应答。取数那头只认 retcode 与 data，缺 retcode 会当场炸。"""
    return {"retcode": 0, "data": data_of(path)}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self._answer()

    def do_POST(self):
        self._answer()

    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        path = self.path.split("?", 1)[0]
        payload = json.dumps(reply(path), ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        """请求行照样记（落进 start-demo.sh 指给它的那份日志），不带时间戳刷屏。"""
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


def main():
    args = sys.argv[1:]
    host, port = DEFAULT_HOST, DEFAULT_PORT
    while args:
        if args[0] == "--host" and len(args) > 1:
            host = args[1]
            args = args[2:]
        elif args[0] == "--port" and len(args) > 1:
            try:
                port = int(args[1])
            except ValueError:
                print(f"bad --port value: {args[1]}", file=sys.stderr)
                return 2
            args = args[2:]
        else:
            print("usage: fake-onebot.py [--host HOST] [--port PORT]", file=sys.stderr)
            return 2

    server = ThreadingHTTPServer((host, port), Handler)
    print(f"fake OneBot endpoint answering on {host}:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
