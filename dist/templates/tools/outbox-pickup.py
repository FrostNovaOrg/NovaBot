#!/usr/bin/env python3
"""从 NovaBot 外发箱取件，用本机文件夹交接通知。"""

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import errno
from http.client import HTTPException
from ipaddress import ip_address
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


API_PATH = '/nova/api/v1/outbox/'
SETTINGS_NAME = 'outbox-pickup.json'
STATE_NAME = '.outbox.json'
REJECTION_NAME = '回报未被接受.txt'
TOKEN_PLACEHOLDER = '<访问令牌>'
DEFAULT_SETTINGS = {
    'address': 'http://127.0.0.1:7827',
    'token': TOKEN_PLACEHOLDER,
    'work_folder': '取件',
    'interval_seconds': 60,
}
OUTCOMES = {'已发': 'SENT', '没发成': 'FAILED', '跳过': 'SKIPPED'}
STATUS_LABELS = {
    'PENDING': '待发', 'SENDING': '发送中', 'SENT': '已发', 'FAILED': '没发成',
    'SKIPPED': '跳过', 'UNKNOWN': '结果不明', 'EXPIRED': '过期未发',
    'LIMITED': '超限未入箱', 'VOID': '已作废',
}
KIND_LABELS = {
    'LIVE_ON': '开播通知', 'LIVE_OFF': '下播通知', 'DYNAMIC': '动态',
    'LIVE_REPORT': '直播报告', 'REWARD_DIGEST': '下播打赏播报',
}
IMAGE_EXTENSIONS = {
    'image/png': '.png', 'image/jpeg': '.jpg', 'image/gif': '.gif',
    'image/bmp': '.bmp', 'image/x-ms-bmp': '.bmp', 'image/tiff': '.tiff',
    'image/webp': '.webp', 'image/avif': '.avif', 'image/x-icon': '.ico',
    'image/vnd.microsoft.icon': '.ico', 'image/vnd.wap.wbmp': '.wbmp', 'image/x-portable-anymap': '.pnm',
}


class SettingsError(Exception):
    pass


class RoundError(Exception):
    def __init__(self, detail, retry_after=0):
        super().__init__(detail)
        self.retry_after = retry_after


class ApiError(RoundError):
    def __init__(self, status, code, scope, retry_after):
        self.status = status
        self.rejected = 400 <= status < 500 and status not in (401, 403, 408, 429)
        detail = {
            400: 'NovaBot 不接受这次请求',
            401: '访问令牌无效或暂不可用，请到控制台「访问令牌」重签或检查账号状态',
            403: f'请求权限不足，请检查令牌是否勾选 {scope}、来源地址和令牌所属账号',
            404: '找不到接口或记录，请检查「外发」插件和账号可见范围',
            409: '记录状态已变化或本次认领已失效，请到控制台「外发箱」核对',
            429: 'NovaBot 正在限速，请检查访问令牌',
        }.get(status, 'NovaBot 请求失败')
        if status == 403 and code == 'insufficient_scope':
            detail = f'访问令牌缺少 {scope}，请到控制台重签并勾选此范围'
        if status == 403 and code == 'source_not_allowed':
            detail = '此来源地址未获准访问 NovaBot，请检查连接方式或允许访问的来源 IP'
        if 300 <= status < 400:
            detail = 'NovaBot 地址返回了重定向，请在设置中填最终地址'
        if status in (401, 403, 429):
            retry_after = max(retry_after, 60)
        super().__init__(f'{detail}（HTTP {status}）', retry_after)


class ImageError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, address, token, open_request=None):
        self.address = address
        self.image_path = urlsplit(address).path + API_PATH
        self.token = token
        self.open_request = open_request or build_opener(ProxyHandler({}), NoRedirect()).open

    def request(self, method, route, body=None):
        headers = {'Authorization': f'Bearer {self.token}'}
        if body is not None:
            body = json.dumps(body, ensure_ascii=False).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        request = Request(self.address + API_PATH + route, data=body, headers=headers, method=method)
        try:
            with self.open_request(request, timeout=15) as response:
                return response.read(), response.headers
        except HTTPError as error:
            with error:
                code = None
                if error.code == 403:
                    try:
                        code = json.load(error)['error']['code']
                    except (ValueError, KeyError, TypeError, OSError, HTTPException):
                        pass
                try:
                    retry_after = int(error.headers.get('Retry-After', '0'))
                except ValueError:
                    retry_after = 0
                scope = 'outbox:read' if method == 'GET' else 'outbox:deliver'
                raise ApiError(error.code, code, scope, retry_after) from None
        except (OSError, HTTPException):
            raise RoundError('连不上 NovaBot 或连接已中断，请检查网络和 NovaBot 是否在运行') from None

    def data(self, method, route, body=None):
        raw, _ = self.request(method, route, body)
        try:
            response = json.loads(raw)
            if response['ok'] is True and isinstance(response['data'], dict):
                return response['data']
        except (ValueError, KeyError, TypeError):
            pass
        raise RoundError('NovaBot 返回了无法识别的响应，请检查地址和「外发」插件')

    def pending(self):
        cursor = None
        while True:
            query = {'status': 'PENDING', 'limit': 100}
            if cursor is not None:
                query['cursor'] = cursor
            page = self.data('GET', 'messages?' + urlencode(query))
            yield from page['items']
            next_cursor = page['next']
            if next_cursor is None:
                return
            if int(next_cursor) <= int(cursor or 0):
                raise RoundError('NovaBot 返回了重复的待发列表游标')
            cursor = next_cursor

    def get_image(self, number, part):
        if part.get('unavailable') or part['byteCount'] is None:
            raise ImageError('NovaBot 标记图片不可读取')
        index = part['index']
        if type(index) is not int or index < 0:
            raise ImageError('NovaBot 返回的图片下标无效')
        route = f'messages/{number}/images/{index}'
        if part['url'] != self.image_path + route:
            raise ImageError('本机图片地址与设置的 NovaBot 路径不符')
        try:
            data, headers = self.request('GET', route)
        except ApiError as error:
            if error.rejected:
                raise ImageError(f'取不到本机图片（HTTP {error.status}）') from None
            raise
        if len(data) < part['byteCount']:
            raise RoundError('本机图片传输未完成')
        if len(data) > part['byteCount']:
            raise ImageError('取到的图片字节数与记录不符')
        content_type = headers.get('Content-Type', '').partition(';')[0].strip().lower()
        return data, IMAGE_EXTENSIONS.get(content_type, '.bin')

    def message(self, number):
        try:
            return self.data('GET', f'messages/{number}')
        except ApiError as error:
            if error.status != 404:
                raise
            return {}


def folder_name(number, original):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f\x7f]', '_', original)
    name = name.encode('utf-8')[:120].decode('utf-8', errors='ignore').strip(' .')
    return f'{number}-{name or "会话"}'


def save_state(folder, state):
    path = folder / STATE_NAME
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as file:
        json.dump(state, file, ensure_ascii=False)
        file.flush()
        os.fsync(file.fileno())
    temporary.replace(path)


def read_state(folder):
    try:
        return json.loads((folder / STATE_NAME).read_text(encoding='utf-8'))
    except FileNotFoundError:
        return None


def write_text(path, text):
    path.write_bytes(text.encode('utf-8'))


def list_folders(path):
    return sorted(item for item in path.iterdir() if item.is_dir() and not item.is_symlink())


class Pickup:
    def __init__(self, root, api, log, now=lambda: datetime.now(timezone.utc)):
        self.pending = root / '待发'
        self.results = {root / name: outcome for name, outcome in OUTCOMES.items()}
        self.archived = root / '已回报'
        self.staging = self.archived / '.outbox-staging'
        for folder in (self.pending, *self.results, self.archived, self.staging):
            folder.mkdir(exist_ok=True)
        self.api = api
        self.log = log
        self.now = now

    def run_round(self):
        for folder in list_folders(self.staging):
            self.recover(folder)

        # 人或发件程序可以在扫描期间把文件夹从待发移进结果文件夹。
        active = set()
        for directory in (self.pending, *self.results):
            for folder in list_folders(directory):
                state = read_state(folder)
                if state is not None:
                    active.add(state['id'])
        for item in self.api.pending():
            if item['id'] not in active:
                self.claim(item)

        for directory, outcome in self.results.items():
            for folder in list_folders(directory):
                state = read_state(folder)
                if state is not None:
                    self.report(folder, state, outcome, self.note(folder))
        for folder in list_folders(self.pending):
            state = read_state(folder)
            if state is not None and self.expired(state):
                staged = self.staging / folder.name
                try:
                    folder.rename(staged)
                except FileNotFoundError:
                    # 文件夹已被移走，下轮按所在位置处理。
                    continue
                self.report(staged, state, 'SKIPPED', '过期未发')

    def expired(self, state):
        expires = datetime.fromisoformat(state['expiresAt'].replace('Z', '+00:00'))
        return self.now() >= expires

    @staticmethod
    def note(folder):
        try:
            with (folder / '原因.txt').open(encoding='utf-8-sig', errors='replace') as file:
                return file.readline(200).rstrip('\r\n') or None
        except FileNotFoundError:
            return None

    @staticmethod
    def discard_attempt(folder):
        path = folder / STATE_NAME
        path.with_suffix('.tmp').unlink(missing_ok=True)
        path.unlink(missing_ok=True)
        folder.rmdir()

    def claim(self, item):
        number = item['id']
        if type(number) is not int or number < 1:
            raise RoundError('NovaBot 返回的通知编号无效')
        folder = self.staging / folder_name(number, item['conversation']['name'])
        folder.mkdir()
        # 先落盘再发请求。断线或中途停机时，服务端可能已完成认领。
        state = {'id': number}
        save_state(folder, state)
        try:
            claimed = self.api.data('POST', f'messages/{number}/claim')
        except ApiError as error:
            if error.rejected or error.status in (401, 403, 429):
                self.discard_attempt(folder)
            if error.status not in (404, 409):
                raise
            return
        message = claimed['message']
        state.update(claimNumber=claimed['claimNumber'], expiresAt=message['expiresAt'], message=message)
        save_state(folder, state)
        self.prepare(folder, state)

    def recover(self, folder):
        state = read_state(folder)
        if state is None:
            self.discard_attempt(folder)
            return
        if 'report' in state:
            self.report(folder, state)
            return
        if 'claimNumber' in state and self.report_expired(folder, state):
            return
        number = state['id']
        record = self.api.message(number)
        status = record.get('status')
        if 'claimNumber' not in state:
            if status == 'PENDING':
                self.discard_attempt(folder)
            else:
                label = STATUS_LABELS.get(status, '记录不可查')
                self.archive(folder, number, f'未能保存认领编号，无法回报。当前状态为「{label}」。')
        elif status in ('SENDING', 'UNKNOWN') and record['expiresAt'] == state['expiresAt']:
            self.prepare(folder, state)
        else:
            label = STATUS_LABELS.get(status, '记录不可查')
            self.archive(folder, number, f'文件夹尚未交接，记录当前为「{label}」，无法继续该次认领。')

    def report_expired(self, folder, state):
        if not self.expired(state):
            return False
        if 'imageRetry' in state:
            self.report(folder, state, 'FAILED', f'到期仍未取到图片：{state["imageRetry"]}')
        else:
            self.report(folder, state, 'SKIPPED', '过期未发')
        return True

    def prepare(self, folder, state):
        if self.report_expired(folder, state):
            return
        if 'message' in state:
            try:
                self.write_message(folder, state['message'])
            except ImageError as error:
                self.report(folder, state, 'FAILED', str(error))
                return
            except RoundError as error:
                state['imageRetry'] = str(error)
                save_state(folder, state)
                raise RoundError(f'通知 {state["id"]} 取图暂时失败：{error}', error.retry_after) from None
            del state['message']
            state.pop('imageRetry', None)
            save_state(folder, state)
        if not self.report_expired(folder, state):
            folder.rename(self.pending / folder.name)

    def write_message(self, folder, message):
        name = message['conversation']['name']
        if '\n' in name or '\r' in name:
            name = json.dumps(name, ensure_ascii=False)
        write_text(folder / '会话.txt', name + '\n')
        parts = message['segments']
        write_text(folder / '正文.txt', ''.join(part['text'] for part in parts if part['type'] == 'TEXT'))
        for old_image in folder.glob('图片-*'):
            old_image.unlink()
        images = (part for part in parts if part['type'] == 'IMAGE_BYTES')
        for index, part in enumerate(images, start=1):
            image, extension = self.api.get_image(message['id'], part)
            (folder / f'图片-{index}{extension}').write_bytes(image)
        streamer = message['streamer']
        identity = ' / '.join(str(value) for value in (streamer.get('platform'), streamer.get('uid'))
                              if value is not None)
        name = streamer.get('name') or '未提供'
        kind = message['kind']
        lines = [
            f'主播：{name}' + (f'（{identity}）' if identity else ''),
            f'推送种类：{KIND_LABELS.get(kind, kind)}',
            f'到期时间（UTC）：{message["expiresAt"]}',
            '@ 全体：' + ('是' if message['atAll'] else '否'),
        ]
        lines.extend('图片网址：' + part['url'] for part in parts if part['type'] == 'IMAGE_URL')
        write_text(folder / '说明.txt', '\n'.join(lines) + '\n')

    @staticmethod
    def save_report(folder, state, outcome, note):
        state.pop('message', None)
        state.pop('imageRetry', None)
        state['report'] = {'outcome': outcome, 'note': note[:200] if note else None}
        save_state(folder, state)

    def report(self, folder, state, outcome=None, note=None):
        if 'report' not in state:
            self.save_report(folder, state, outcome, note)
        report = state['report']
        number = state['id']
        if 'rejection' not in report:
            body = {'claimNumber': state['claimNumber'], 'outcome': report['outcome'], 'note': report['note']}
            try:
                self.api.data('POST', f'messages/{number}/receipt', body)
            except ApiError as error:
                if not error.rejected:
                    raise
                report['rejection'] = str(error)
                if error.status == 409:
                    # 补查状态之前先记下拒绝，重启时只需继续归档。
                    save_state(folder, state)
                    try:
                        label = STATUS_LABELS.get(self.api.message(number).get('status'))
                    except RoundError:
                        label = None
                    if label:
                        report['rejection'] += f'。当前状态为「{label}」'
            else:
                report['rejection'] = None
            save_state(folder, state)
        self.archive(folder, number, report['rejection'])

    def archive(self, folder, number, rejection):
        if rejection:
            write_text(folder / REJECTION_NAME, rejection + '\n')
        day = self.archived / self.now().astimezone().date().isoformat()
        day.mkdir(exist_ok=True)
        destination = day / folder.name
        suffix = 2
        while destination.exists():
            destination = day / f'{folder.name} ({suffix})'
            suffix += 1
        folder.rename(destination)
        if rejection:
            self.log(f'通知 {number} 回报未被接受：{rejection}')


@contextmanager
def work_lock(path):
    with path.open('a+b') as file:
        try:
            if os.name == 'nt':
                import msvcrt
                file.seek(0)
                msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            if error.errno not in (errno.EACCES, errno.EAGAIN, errno.EDEADLK):
                raise
            raise SettingsError('这个工作文件夹已经有一个取件程序在运行。') from None
        # 保留锁文件，仅在关闭文件时释放锁，避免另一进程锁到不同文件。
        yield


def restrict_settings(path):
    if os.name != 'nt':
        path.chmod(0o600)
        return

    # Windows 的 chmod 只能设只读位。用当前进程用户的 SID 设置独立的文件访问权限。
    from ctypes import (POINTER, WinDLL, WinError, byref, c_void_p, cast, create_string_buffer)
    from ctypes.wintypes import BOOL, DWORD, HANDLE, LPCWSTR, LPWSTR

    security = WinDLL('advapi32', use_last_error=True)
    kernel = WinDLL('kernel32', use_last_error=True)
    functions = (
        (security.OpenProcessToken, BOOL, HANDLE, DWORD, POINTER(HANDLE)),
        (security.GetTokenInformation, BOOL, HANDLE, DWORD, c_void_p, DWORD, POINTER(DWORD)),
        (security.ConvertSidToStringSidW, BOOL, c_void_p, POINTER(LPWSTR)),
        (security.ConvertStringSecurityDescriptorToSecurityDescriptorW, BOOL,
         LPCWSTR, DWORD, POINTER(c_void_p), POINTER(DWORD)),
        (security.GetSecurityDescriptorDacl, BOOL, c_void_p, POINTER(BOOL), POINTER(c_void_p), POINTER(BOOL)),
        (security.SetNamedSecurityInfoW, DWORD, LPWSTR, DWORD, DWORD, c_void_p, c_void_p, c_void_p, c_void_p),
        (kernel.CloseHandle, BOOL, HANDLE),
        (kernel.LocalFree, c_void_p, c_void_p),
    )
    for function, result, *arguments in functions:
        function.restype = result
        function.argtypes = arguments

    token = HANDLE()
    if not security.OpenProcessToken(HANDLE(-1), 8, byref(token)):  # TOKEN_QUERY
        raise WinError()
    sid_text = LPWSTR()
    descriptor = c_void_p()
    try:
        size = DWORD()
        security.GetTokenInformation(token, 1, None, 0, byref(size))  # TokenUser
        user = create_string_buffer(size.value)
        if not security.GetTokenInformation(token, 1, user, size.value, byref(size)):
            raise WinError()
        sid = cast(user, POINTER(c_void_p))[0]  # TOKEN_USER 的第一个字段
        if not security.ConvertSidToStringSidW(sid, byref(sid_text)):
            raise WinError()
        sddl = f'D:P(A;;FA;;;{sid_text.value})'
        if not security.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, byref(descriptor), None):
            raise WinError()
        present, defaulted, acl = BOOL(), BOOL(), c_void_p()
        if not security.GetSecurityDescriptorDacl(descriptor, byref(present), byref(acl), byref(defaulted)):
            raise WinError()
        # SE_FILE_OBJECT, PROTECTED_DACL_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION
        result = security.SetNamedSecurityInfoW(str(path), 1, 0x80000004, None, None, acl, None)
        if result:
            raise WinError(result)
    finally:
        kernel.LocalFree(descriptor)
        kernel.LocalFree(cast(sid_text, c_void_p))
        kernel.CloseHandle(token)


@dataclass(frozen=True)
class Settings:
    address: str
    token: str
    work_folder: Path
    interval: int


def load_settings(path):
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        restrict_settings(path)
    else:
        os.close(descriptor)
        restrict_settings(path)
        write_text(path, json.dumps(DEFAULT_SETTINGS, ensure_ascii=False, indent=2) + '\n')
        return None

    try:
        data = json.loads(path.read_text(encoding='utf-8-sig'))
    except json.JSONDecodeError as error:
        raise SettingsError(f'{SETTINGS_NAME} 第 {error.lineno} 行格式有误，请核对引号和逗号。') from None
    if not isinstance(data, dict):
        raise SettingsError(f'请按 {SETTINGS_NAME} 的初始样板填写设置。')
    address = data.get('address')
    if not isinstance(address, str):
        raise SettingsError('请在 address 填写 NovaBot 的 HTTP 或 HTTPS 地址。')
    address = address.strip().rstrip('/')
    try:
        url = urlsplit(address)
        port = url.port
    except ValueError:
        raise SettingsError('请检查 NovaBot 地址和端口。') from None
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username is not None
            or url.query or url.fragment or re.search(r'[\x00-\x20\x7f\\?#]', address) or port == 0):
        raise SettingsError('请在 address 填写 NovaBot 的 HTTP 或 HTTPS 地址，可包含部署前缀。')
    token = os.environ.get('NOVABOT_OUTBOX_TOKEN', data.get('token'))
    if not isinstance(token, str) or token == TOKEN_PLACEHOLDER or not re.fullmatch(r'[!-~]+', token):
        raise SettingsError('请在设置或 NOVABOT_OUTBOX_TOKEN 环境变量中填写控制台签发的访问令牌。')
    work = data.get('work_folder', DEFAULT_SETTINGS['work_folder'])
    if not isinstance(work, str) or not work.strip():
        raise SettingsError('请在 work_folder 填写工作文件夹路径。')
    interval = data.get('interval_seconds', DEFAULT_SETTINGS['interval_seconds'])
    if type(interval) is not int or interval < 1:
        raise SettingsError('请在 interval_seconds 填写正整数秒数。')
    return Settings(address, token, (path.parent / Path(work).expanduser()).resolve(), interval)


def warn_if_cleartext(address, log):
    url = urlsplit(address)
    if url.scheme == 'https' or url.hostname.rstrip('.') == 'localhost':
        return
    try:
        if ip_address(url.hostname).is_loopback:
            return
    except ValueError:
        pass
    log('注意：NovaBot 地址使用非本机的 HTTP，访问令牌会明文经过网络。')


def run(client, interval, once, sleep=time.sleep):
    failures = 0
    while True:
        try:
            client.run_round()
        except (RoundError, OSError, ValueError, KeyError, TypeError) as error:
            if isinstance(error, RoundError):
                detail, retry_after = str(error), error.retry_after
            elif isinstance(error, OSError):
                detail, retry_after = '无法读写工作文件夹，请检查权限和可用空间', 0
            else:
                detail, retry_after = '通知或本机记录的格式无法识别，请检查设置和工作文件夹', 0
            if once:
                client.log(detail + '。')
                return 1
            failures += 1
            backoff = min(max(900, interval * 2), max(5, interval) * 2 ** min(failures, 8))
            delay = max(backoff, retry_after)
            client.log(f'{detail}，{delay} 秒后再试。')
        else:
            if once:
                client.log('已完成一轮取件和回报。')
                return 0
            failures, delay = 0, interval
        sleep(delay)


def main(argv=None, settings_path=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--once', action='store_true', help='只运行一轮，便于排查或定时调用')
    args = parser.parse_args(argv)
    path = settings_path or Path(__file__).resolve().with_name(SETTINGS_NAME)

    def log(message):
        print(message, file=sys.stderr, flush=True)

    try:
        settings = load_settings(path)
        if settings is None:
            log(f'已生成设置 {SETTINGS_NAME}，请填写后再运行。')
            return 0
        warn_if_cleartext(settings.address, log)
        settings.work_folder.mkdir(parents=True, exist_ok=True)
        with work_lock(settings.work_folder / '.outbox.lock'):
            client = Pickup(settings.work_folder, Api(settings.address, settings.token), log)
            return run(client, settings.interval, args.once)
    except SettingsError as error:
        log(str(error))
    except (OSError, ValueError):
        log('无法读取设置或打开工作文件夹，请检查设置格式、路径和权限。')
    except KeyboardInterrupt:
        return 0
    return 1


if __name__ == '__main__':
    sys.exit(main())
