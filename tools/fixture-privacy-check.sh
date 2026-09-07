#!/usr/bin/env bash
# Scan each module's src/test tree (including resources) for two shapes:
# ① hdslb.com/bfs/face/ followed by a 40-hex hash — the hash must be listed
#    in tools/fixture-privacy-allow.txt
# ② mailbox-shaped strings — the domain must end with example.com,
#    example.org, example.net, .invalid, .test, or .example
#
# A sample that must fail both checks is fed to the same matchers first.
# If that sample is not flagged, this script exits 2 (it cannot tell red
# from green). Exit 0 = clean fixtures, 1 = hits printed as file:line.
# Not wired into the build.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

ALLOW="$REPO_ROOT/tools/fixture-privacy-allow.txt"
if [ ! -f "$ALLOW" ]; then
    echo "tools/fixture-privacy-allow.txt is missing" >&2
    exit 2
fi

FACE_RE='hdslb\.com/bfs/face/[0-9a-fA-F]{40}'
EMAIL_RE='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'

domain_reserved() {
    case "$1" in
        *example.com|*example.org|*example.net|*.invalid|*.test|*.example) return 0 ;;
        *) return 1 ;;
    esac
}

scan_faces_on_line() {
    local label="$1" lineno="$2" line="$3"
    local m hex
    printf '%s\n' "$line" | grep -oE "$FACE_RE" | while IFS= read -r m; do
        [ -z "$m" ] && continue
        hex="${m##*/}"
        if ! grep -qxF "$hex" "$ALLOW"; then
            printf '%s:%s: face hash %s is not in the allow list\n' "$label" "$lineno" "$hex"
        fi
    done || true
}

scan_mail_on_line() {
    local label="$1" lineno="$2" line="$3"
    local email domain
    printf '%s\n' "$line" | grep -oE "$EMAIL_RE" | while IFS= read -r email; do
        [ -z "$email" ] && continue
        domain="${email#*@}"
        if ! domain_reserved "$domain"; then
            printf '%s:%s: mailbox %s uses a non-reserved domain\n' "$label" "$lineno" "$email"
        fi
    done || true
}

# Split grep -nH "file:line:text" without assuming the text is colon-free.
split_grep() {
    rec="$1"
    file="${rec%%:*}"
    rest="${rec#*:}"
    lineno="${rest%%:*}"
    line="${rest#*:}"
}

scan_text_faces() {
    local label="$1" text="$2"
    local lineno=0 line
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        scan_faces_on_line "$label" "$lineno" "$line"
    done <<EOF
$text
EOF
}

scan_text_mail() {
    local label="$1" text="$2"
    local lineno=0 line
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        scan_mail_on_line "$label" "$lineno" "$line"
    done <<EOF
$text
EOF
}

# —— built-in sample that must be red ——
NEG_TEXT='https://i0.hdslb.com/bfs/face/ffffffffffffffffffffffffffffffffffffffff.jpg
user@not-reserved.com'
NEG_FACE="$(scan_text_faces "negative-control" "$NEG_TEXT")"
NEG_MAIL="$(scan_text_mail "negative-control" "$NEG_TEXT")"
if ! printf '%s\n' "$NEG_FACE" | grep -q 'face hash'; then
    echo "阴性对照 红：一段必红的头像哈希被判成了过，这一格量不动" >&2
    exit 2
fi
if ! printf '%s\n' "$NEG_MAIL" | grep -q 'mailbox'; then
    echo "阴性对照 红：一段必红的邮箱被判成了过，这一格量不动" >&2
    exit 2
fi
echo "阴性对照 绿（必红的样本确实被判红）"

HIT=0
while IFS= read -r testdir; do
    [ -d "$testdir" ] || continue
    while IFS= read -r rec; do
        [ -z "$rec" ] && continue
        split_grep "$rec"
        out="$(scan_faces_on_line "$file" "$lineno" "$line")"
        if [ -n "$out" ]; then
            printf '%s\n' "$out"
            HIT=$((HIT + 1))
        fi
    done <<EOF
$(grep -nH -E -I -r "$FACE_RE" "$testdir" || true)
EOF
    while IFS= read -r rec; do
        [ -z "$rec" ] && continue
        split_grep "$rec"
        out="$(scan_mail_on_line "$file" "$lineno" "$line")"
        if [ -n "$out" ]; then
            printf '%s\n' "$out"
            HIT=$((HIT + 1))
        fi
    done <<EOF
$(grep -nH -E -I -r "$EMAIL_RE" "$testdir" || true)
EOF
done <<EOF
$(find . -type d -path '*/src/test' ! -path '*/target/*')
EOF

if [ "$HIT" -ne 0 ]; then
    echo "汇总：红 ${HIT} 处，整尺退码 1"
    exit 1
fi
echo "汇总：绿，整尺退码 0"
exit 0
