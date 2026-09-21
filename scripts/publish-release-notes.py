#!/usr/bin/env python3
"""把 docs/release-notes/*.md 发布到 GitHub / Gitee 的 Release 正文。

## 这个脚本存在的唯一理由

同一份发版说明活在两个渲染环境里，而它们对「相对链接」的解析基准不同：

| 渲染环境 | `(v1.7.0.md)` 解析成 | `(../../LICENSE)` 解析成 |
|---|---|---|
| 仓库文件视图 / 本地编辑器 / 任意托管站 | 同目录的兄弟文件 ✓ | 仓库根的 LICENSE ✓ |
| GitHub / Gitee 的 Release 页面 | 404 | 404 |

Release 页面不在 `docs/release-notes/` 这个目录下，它压根没有「当前目录」这个概念。

**解法不是把源文件改成绝对 URL。** 那样做等于为了一个渲染环境牺牲另外三个：本地点开
会跳浏览器而不是打开本地文件，仓库换到 Gitee / GitLab / 自建站之后所有链接仍指向
github.com 上那个旧地址。源文件必须保持相对路径——那是唯一在四个环境里都成立的写法。

所以转换放在**发布这一刻**：读源文件，把相对链接就地换成绝对 URL，把结果喂给
发布后端（GitHub 走 `gh`，Gitee 走开放 API）。仓库里的文件一个字都不动。

## 仓库地址从哪来

从 `--remote` 指定的 git remote 推导（默认 origin），**不写死**。仓库搬家或一仓多远端
（本仓库同时有 github / gitee 两个 remote）时换一个参数即可，不需要改这个脚本。

## 发到 Gitee 的前提

- tag 先推上去（Release 锚定在 tag 上）：`git push gitee --tags`；
- 环境变量 `GITEE_TOKEN`（gitee.com → 设置 → 私人令牌，勾 projects）。token 只走
  `Authorization: Bearer` 请求头，永远不进 URL——这样报错信息里才敢放心打印 URL。
  Gitee 已有的 Release 会用 PATCH 更新正文，没有的用 POST 新建，可重复执行。

## 用法

    python3 scripts/publish-release-notes.py --print v1.8.0                  # 只打印，先看一眼
    python3 scripts/publish-release-notes.py --remote github v1.8.0          # 发到 GitHub
    python3 scripts/publish-release-notes.py --remote gitee --all            # 发到 Gitee
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Optional
from urllib.parse import quote

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTES_DIR = "docs/release-notes"

# markdown 链接。第二组刻意不含空格与右括号——带标题的 [x](url "t") 本仓库没有，
# 真出现了会原样跳过而不是转坏。
LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")

SKIP_PREFIXES = ("http://", "https://", "#", "mailto:", "//")


def remote_info(remote: str) -> tuple[str, str]:
    """返回 (--remote 指向的仓库) 的 (host, owner/repo)。

    支持 SSH（git@host:owner/repo.git）与 HTTPS（https://host/owner/repo.git）两种写法。
    """
    r = subprocess.run(
        ["git", "-C", REPO_ROOT, "remote", "get-url", remote],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        sys.exit(f"没有这个 remote：{remote}（git 说：{r.stderr.strip()}）")
    url = r.stdout.strip()

    m = re.match(r"^(?:git@|ssh://git@)([^:/]+)[:/](.+?)(?:\.git)?$", url) \
        or re.match(r"^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\.git)?$", url)
    if not m:
        sys.exit(f"认不出 {remote} 的形状，没法推导仓库地址：{url}")
    return m.group(1), m.group(2)


def blob_base(host: str, path: str) -> str:
    """「浏览某个文件」的 URL 前缀，末尾带斜杠。

    GitLab 的路径形状与 GitHub/Gitee 不同（多一段 /-/），按 host 区分。
    """
    infix = "/-/blob/" if "gitlab" in host else "/blob/"
    # 固定用 main 而不是 tag：老版本的说明是按<b>今天</b>的目录布局写的
    # （v1.3.1 那份在它自己的 tag 上还叫 release-notes-v1.3.0.md），
    # 按 tag 取会 404。
    return f"https://{host}/{path}{infix}main/"


def absolutize(text: str, base: str) -> tuple[str, int]:
    """把指向仓库内真实文件的相对链接换成绝对 URL。返回（新文本, 替换条数）。"""
    n = 0

    def repl(m: re.Match) -> str:
        nonlocal n
        label, target = m.group(1), m.group(2)
        if target.startswith(SKIP_PREFIXES):
            return m.group(0)
        path, _, anchor = target.partition("#")
        real = os.path.normpath(os.path.join(NOTES_DIR, path))
        # 「解析得到的文件在仓库里真的存在」是唯一判据。正文里那个示意用的
        # `![](路径)` 因此会被原样留下，不需要为它写例外。
        if not path or not os.path.exists(os.path.join(REPO_ROOT, real)):
            return m.group(0)
        n += 1
        # 锚点<b>不做百分号编码</b>：GitHub 的标题锚点 id 就是原始 UTF-8
        # （如 `#️-安全声明本版必须重读的部分`——开头那个看不见的字符是变体选择符
        # U+FE0F，slug 算法去掉了 ⚠ 却留下它）。编码过一遍虽然多数浏览器也能跳，
        # 但肉眼再也核对不了，改坏了不会有人发现。
        url = base + quote(real) + (("#" + anchor) if anchor else "")
        return f"[{label}]({url})"

    return LINK.sub(repl, text), n


def notes_path(tag: str) -> str:
    p = os.path.join(REPO_ROOT, NOTES_DIR, f"{tag}.md")
    if not os.path.exists(p):
        sys.exit(f"没有这份发版说明：{p}")
    return p


def all_tags() -> list[str]:
    d = os.path.join(REPO_ROOT, NOTES_DIR)
    tags = [f[:-3] for f in os.listdir(d) if re.fullmatch(r"v\d+\.\d+\.\d+\.md", f)]
    return sorted(tags, key=lambda t: [int(x) for x in t[1:].split(".")])


def notes_title(tag: str) -> str:
    """取说明文件首行 H1 当 Release 标题；首行不是 H1 就退回 tag 本身。

    GitHub 那边的标题是发版时手写的，没有单一可靠来源；文件自己的 H1 是
    仓库内唯一「和正文永远在一起」的标题，Gitee 侧以它为准。
    """
    with open(notes_path(tag), encoding="utf-8") as f:
        first = f.readline().strip()
    return first[2:].strip() if first.startswith("# ") else tag


def gitee_request(method: str, url: str, token: str,
                  data: Optional[dict] = None) -> tuple[int, str]:
    """调一次 Gitee API，返回 (状态码, 响应正文)。

    token 只放 Authorization 头、绝不拼进 URL——本函数的调用方在报错信息里
    打印 URL 时才不必先想一遍脱敏。
    """
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def tag_commit(tag: str) -> str:
    """tag 指向的 commit SHA（附注 tag 用 ^{} 剥到 commit；轻量 tag 本身就是）。"""
    r = subprocess.run(["git", "-C", REPO_ROOT, "rev-parse", f"{tag}^{{}}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"解析 {tag} 指向的 commit 失败：{r.stderr.strip()}")
    return r.stdout.strip()


def gitee_publish(path: str, tag: str, name: str, body: str) -> str:
    """把正文发到 Gitee Release；返回给人看的结果短语，失败直接 sys.exit。"""
    token = os.environ.get("GITEE_TOKEN")
    if not token:
        sys.exit("发布到 Gitee 需要 GITEE_TOKEN（先 source ~/.secrets）")

    api = f"https://gitee.com/api/v5/repos/{path}/releases"
    code, text = gitee_request("GET", f"{api}/tags/{tag}", token)
    # Gitee 的怪癖：Release 不存在时不少端点回 200 + body "null" 而不是 404，
    # 所以「有没有」以解析出的 id 为准，不赌状态码。
    rid = None
    if code == 200:
        try:
            rid = (json.loads(text) or {}).get("id")
        except ValueError:
            rid = None
    elif code != 404:
        sys.exit(f"{tag} 查询 Gitee Release 失败：HTTP {code} {text}")

    if rid is None:
        code, text = gitee_request(
            "POST", api, token,
            {"tag_name": tag, "name": name, "body": body,
             "target_commitish": tag_commit(tag)})
        if code not in (200, 201):
            sys.exit(f"{tag} 新建 Gitee Release 失败：HTTP {code} {text}")
        return "ok（新建 Release）"

    code, text = gitee_request(
        "PATCH", f"{api}/{rid}", token,
        # Gitee 的 PATCH 也把 tag_name 当必填项（哪怕根本不改它）
        {"tag_name": tag, "name": name, "body": body})
    if code != 200:
        sys.exit(f"{tag} 更新 Gitee Release 失败：HTTP {code} {text}")
    return "ok（更新既有 Release）"


def main() -> None:
    ap = argparse.ArgumentParser(description="发布 release notes 到 GitHub/Gitee Release 正文")
    ap.add_argument("tags", nargs="*", help="如 v1.8.0；省略则须给 --all")
    ap.add_argument("--all", action="store_true", help="全部发版说明")
    ap.add_argument("--print", dest="dry", action="store_true",
                    help="只把转换后的正文打到 stdout，不碰 Release")
    ap.add_argument("--remote", default="origin", metavar="NAME",
                    help="用哪个 git remote（本仓库：github / gitee），默认 origin")
    args = ap.parse_args()

    tags = all_tags() if args.all else args.tags
    if not tags:
        ap.error("给个 tag，或者用 --all")

    host, path = remote_info(args.remote)
    base = blob_base(host, path)
    for tag in tags:
        body, n = absolutize(open(notes_path(tag), encoding="utf-8").read(), base)
        if args.dry:
            print(body)
            continue
        if "gitee" in host:
            result = gitee_publish(path, tag, notes_title(tag), body)
        elif "github" in host:
            r = subprocess.run(["gh", "release", "edit", tag, "--notes-file", "-"],
                               input=body, text=True, capture_output=True,
                               cwd=REPO_ROOT)
            if r.returncode != 0:
                result = "FAILED: " + r.stderr.strip()
                print(f"{tag}  {result}  （{n} 条链接改成绝对 URL）")
                sys.exit(1)
            result = "ok"
        else:
            sys.exit(f"没有 {host} 的发布后端：目前支持 github（gh）与 gitee（API）")
        print(f"{tag}  {result}  （{n} 条链接改成绝对 URL）")


if __name__ == "__main__":
    main()
