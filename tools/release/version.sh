#!/usr/bin/env bash
# 发布工具共用的版本格式与文件替换逻辑；在产品仓根目录调用。
release_name_is_version(){ [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-beta\.[1-9][0-9]*)?$ ]]; }

release_set_version(){
  local CURV="$1" VER="$2" message="$3" PAT p left
  PAT=">${CURV//./\\.}<"
  EXR=templates/nova-example-plugin/README.md
  [ -f "$EXR" ] || stop preflight "克隆里没有 ${EXR}"
  grep -q "$PAT" "$EXR" || stop preflight "${EXR} 里没有 ${CURV}（版本文件变了，人来看）"
  n=0
  while IFS= read -r p; do
    grep -q "$PAT" "$p" || continue
    sed -i '' "s#${PAT}#>${VER}<#g" "$p" || stop preflight "改不了 $p"
    say "${message}：${p}"
    n=$((n+1))
  done < <(git ls-files -- 'pom.xml' '*/pom.xml'; echo "$EXR")
  [ "$n" -gt 1 ] || stop preflight "一份 pom 也没改到"
  left=$(git ls-files -- 'pom.xml' '*/pom.xml' | while IFS= read -r p; do grep -l "$PAT" "$p"; done)
  [ -z "$left" ] || stop preflight "改完仍有 pom 带 ${CURV}：${left}"
  grep -q ">${VER//./\\.}<" "$EXR" && ! grep -q "$PAT" "$EXR" || stop preflight "改完 ${EXR} 不是 ${VER}"
}
