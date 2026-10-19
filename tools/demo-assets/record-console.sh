#!/usr/bin/env bash
# Record real controllers in a disposable application context without a listener.
set -euo pipefail
export TZ=Asia/Shanghai
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/novabot-console-record-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
python3 "$ROOT/tools/demo-assets/demo-seed/make-demo-seed.py" "$WORK"
python3 - "$ROOT" "$WORK" <<'PY'
import json, runpy, subprocess, sys
from datetime import datetime
from pathlib import Path
bot = runpy.run_path(str(Path(sys.argv[1]) / 'tools/demo-assets/fake-onebot.py'))
paths = ('get_version_info', 'get_login_info', 'get_status', 'get_group_list',
         'get_friend_list', 'get_group_member_list', 'get_group_member_info',
         'send_group_msg', 'send_private_msg')
Path(sys.argv[2], 'transport.json').write_text(json.dumps(
    {'/' + path: bot['data_of']('/' + path) for path in paths}, ensure_ascii=False))
now = datetime.now().astimezone()
Path(sys.argv[2], 'recording.json').write_text(json.dumps(dict(
    today=now.date().isoformat(), recordedAt=now.isoformat(), epochMs=int(now.timestamp() * 1000),
    timezone='Asia/Shanghai', commit=subprocess.check_output(
        ['git', '-C', sys.argv[1], 'rev-parse', 'HEAD'], text=True).strip())))
PY
cd "$ROOT"
mvn -B -Pconsole-demo-record -pl :nova-console -am test \
  -Dtest=ConsoleDemoRecorderTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dconsole.demo.record=true -Dconsole.demo.work="$WORK" -Dconsole.demo.root="$ROOT" \
  -DargLine="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9"
python3 "$ROOT/tools/demo-assets/check-console-privacy.py" "$WORK"/recorded-data/*
python3 - "$ROOT" "$WORK" <<'PY'
import shutil, sys
from pathlib import Path
dest = Path(sys.argv[1], 'tools/demo-assets/console-demo/data')
if dest.exists():
    shutil.rmtree(dest)
shutil.copytree(Path(sys.argv[2], 'recorded-data'), dest)
print('Published recorded responses: ' + str(dest))
PY
