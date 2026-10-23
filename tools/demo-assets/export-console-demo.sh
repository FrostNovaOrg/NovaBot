#!/usr/bin/env bash
# Export the repository's console files for any static HTTP server.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "$#" -ne 1 ]; then
    echo 'Usage: export-console-demo.sh <output-directory>' >&2
    exit 2
fi
python3 - "$ROOT" "$1" <<'PY'
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

# Resolve both sides, so a checkout reached through a symlink still compares paths alike.
root, dest = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
demo = root / 'tools/demo-assets/console-demo'
index = json.loads((demo / 'data/index.json').read_text())
# Check every response before touching the destination.
for row in index['responses']:
    file = (demo / row['file']).resolve()
    if not file.is_relative_to(demo / 'data') or hashlib.sha256(file.read_bytes()).hexdigest() != row['sha256']:
        raise SystemExit('Invalid recorded response: ' + row['file'])
if dest == root or root.is_relative_to(dest) or dest.is_relative_to(root):
    raise SystemExit('Choose an output directory outside the source repository.')
if dest.exists() and any(dest.iterdir()) and not (dest / '.console-demo-export').is_file():
    raise SystemExit('Output directory is not empty and was not created by this exporter.')
dest.mkdir(parents=True, exist_ok=True)
(dest / '.console-demo-export').write_text('Static console export\n')
for name in ('config', 'console-demo'):
    if (dest / name).exists():
        shutil.rmtree(dest / name)
assets = dest / 'config/assets'
assets.mkdir(parents=True)
sources = [root / 'core/nova-core/src/main/resources/config-ui']
sources += sorted((root / 'plugins').glob('*/src/main/resources/config-ui-pages'))
# HTML files are page templates; the backend renders them and never serves them as assets.
templates = {}
for source in sources:
    for file in sorted(source.iterdir()):
        if file.name.startswith('.'):
            continue
        if not file.is_file():
            raise SystemExit('Unexpected asset directory: ' + str(file))
        target = assets / file.name
        if target.exists() or file.name in templates:
            raise SystemExit('Duplicate console asset: ' + file.name)
        if file.suffix == '.html':
            templates[file.name] = file
            continue
        shutil.copyfile(file, target)
shutil.copytree(demo / 'data', dest / 'console-demo/data')
shutil.copyfile(demo / 'demo.js', dest / 'console-demo/demo.js')
(dest / 'console-demo/recording.js').write_text('window.NOVA_CONSOLE_DEMO = ' + json.dumps(index, ensure_ascii=False, indent=2) + ';\n')
injection = '<script src="/console-demo/recording.js"></script>\n<script src="/console-demo/demo.js"></script>\n'
# The backend splices these scripts and the shared stylesheet into the login page in place of the marker lines.
splices = {'//@@password-reveal@@': 'password-reveal.js', '//@@login-model@@': 'login-model.js',
           '/*@@product-ui@@*/': 'product-ui.css'}
def render(source):
    text = source.read_text()
    for marker, script in splices.items():
        text = text.replace(marker, (assets / script).read_text(), 1)
    # The static export has one fixed set of files, so asset URLs drop the version segment.
    text = (text.replace('{{nova-prefix}}', '').replace('{{nova-prefix:js}}', '')
            .replace('/{{nova-asset-version}}', ''))
    leftover = re.search(r'\{\{nova-[^}]*\}\}|//@@[^@\s]*@@|/\*@@[^@\s]*@@\*/', text)
    if leftover:
        raise SystemExit('Unhandled placeholder in ' + source.name + ': ' + leftover.group())
    return text.replace('</head>', injection + '</head>', 1)
page = render(templates['index.html'])
(dest / 'index.html').write_text(page)
(dest / 'config/index.html').write_text(page)
for name, source in [('login', templates['login.html']),
                     ('napcat-bootstrap', templates['napcat-bootstrap.html'])]:
    directory = dest / 'config' / name
    directory.mkdir()
    (directory / 'index.html').write_text(render(source))
shutil.copyfile(assets / 'napcat-resume.js', dest / 'config/napcat-bootstrap/napcat-resume.js')
shutil.copyfile(demo / 'README.md', dest / 'README.md')
files = sorted(p.relative_to(dest).as_posix() for p in dest.rglob('*') if p.is_file())
print('Exported console demo: ' + str(dest))
print('File tree (first 40 files):')
print('\n'.join(files[:40]))
print(f'Total: {len(files)} files, {sum((dest / p).stat().st_size for p in files)} bytes')
print('Serve this directory with python3 -m http.server 8000; open http://localhost:8000/config/')
PY
