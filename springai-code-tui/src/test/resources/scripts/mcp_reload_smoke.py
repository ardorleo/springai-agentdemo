#!/usr/bin/env python3
"""PTY smoke test for MCP config reload (/reload + /mcp panel 'r' key).

Boots the real app with one real stdio MCP server, then:
1. /reload with unchanged mcp.json -> asserts "无变化" info line AND the
   server stays connected (no reconnect flap — the real-client half the
   unit tests cannot observe: McpSyncClient cannot be faked).
2. Adds a bogus server entry to mcp.json -> /reload -> asserts "新增 1"
   summary and the new row appears in the /mcp panel (failed state is fine:
   its command does not exist — the point is it was picked up live).
3. Removes the bogus entry -> presses 'r' INSIDE the panel -> asserts
   "移除 1" summary and the row disappears.
4. /exit terminates promptly; no orphaned child.

Covers both entry points (slash command + panel key) against the real
file-loading, diff, background-connect and render pipeline end to end.

Requires the module compiled and the classpath file present:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
Also requires `npx` on PATH (Node.js) and network/npm cache for the
filesystem server package.

Usage:
    /usr/bin/python3 scripts/mcp_reload_smoke.py
"""
import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    MAIN_CLASS,
    WELCOME_1,
)
from mcp_smoke import count_orphans  # noqa: E402

PANEL_TITLE = "MCP 服务器"
CONNECTED = "已连接"
BOGUS_NAME = "bogus"
MCP_TOOLS_NOTICE = "已发现"
EXIT_BUDGET_SEC = 10.0
SERVER_NAME = "fs"


def write_cfg(cfg_path, entries):
    with open(cfg_path, "w") as f:
        json.dump({"mcpServers": entries}, f)


def main():
    if shutil.which("npx") is None:
        die("npx not on PATH; MCP smoke requires Node.js/npx")

    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-mcpreload-")
    # Isolate the user layer (same reason as mcp_manage_smoke: the REAL
    # ~/.codetui/mcp.json would pollute the panel and row assumptions).
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    cfg_dir = os.path.join(tmpdir, ".codetui")
    os.makedirs(cfg_dir, exist_ok=True)
    cfg_path = os.path.join(cfg_dir, "mcp.json")
    fs_entry = {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", tmpdir],
    }
    write_cfg(cfg_path, {SERVER_NAME: fs_entry})

    baseline_orphans = count_orphans()

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=40)
        session.wait_for(MCP_TOOLS_NOTICE, timeout=15)
        print("Boot OK: MCP server connected, tools discovered.")

        # 1. /reload with no file change: 无变化 + no reconnect flap.
        session.write(b"/reload\r")
        session.wait_for("MCP 配置无变化", timeout=10)
        session.write(b"/mcp\r")
        session.wait_for(PANEL_TITLE, timeout=10)
        session.pump(0.5)
        if CONNECTED not in session.screen_text():
            die("unchanged reload flapped the connection (missing %r)" % CONNECTED,
                session.screen.display)
        print("No-change reload OK: summary line + connection preserved (no flap).")
        session.write(b"\x1b")
        session.pump(0.5)

        # 2. Add a bogus entry on disk -> /reload picks it up live.
        write_cfg(cfg_path, {
            SERVER_NAME: fs_entry,
            BOGUS_NAME: {"command": "definitely-not-a-real-binary-xyz"},
        })
        session.write(b"/reload\r")
        session.wait_for("已重载 MCP 配置：新增 1 · 移除 0 · 重连 0", timeout=10)
        session.write(b"/mcp\r")
        session.wait_for(PANEL_TITLE, timeout=10)
        deadline = time.time() + 15
        while time.time() < deadline:
            session.pump(0.3)
            if BOGUS_NAME in session.screen_text():
                break
        if BOGUS_NAME not in session.screen_text():
            die("bogus entry not listed in /mcp panel after reload",
                session.screen.display)
        if CONNECTED not in session.screen_text():
            die("existing server lost its connection across an additive reload",
                session.screen.display)
        print("Additive reload OK: bogus row visible (failed connect is fine), fs still connected.")

        # 3. Remove the bogus entry on disk -> press 'r' INSIDE the panel.
        write_cfg(cfg_path, {SERVER_NAME: fs_entry})
        session.write(b"r")
        session.wait_for("已重载 MCP 配置：新增 0 · 移除 1 · 重连 0", timeout=10)
        deadline = time.time() + 5
        while time.time() < deadline:
            session.pump(0.3)
            if BOGUS_NAME not in session.screen_text():
                break
        if BOGUS_NAME in session.screen_text():
            die("bogus row still listed after in-panel reload", session.screen.display)
        if CONNECTED not in session.screen_text():
            die("existing server lost its connection across a removal reload",
                session.screen.display)
        print("In-panel 'r' reload OK: bogus row gone, fs still connected.")
        session.write(b"\x1b")
        session.pump(0.5)

        # 4. /exit terminates promptly, no orphaned child.
        session.write(b"/exit\r")
        t0 = time.time()
        while time.time() - t0 < EXIT_BUDGET_SEC:
            if session.proc.poll() is not None:
                break
            session.pump(0.2)
        if session.proc.poll() is None:
            die("process did not exit within %.1fs after /exit" % EXIT_BUDGET_SEC,
                session.screen.display)
        print("/exit terminated promptly in %.2fs." % (time.time() - t0))

        time.sleep(1.0)
        if baseline_orphans >= 0:
            remaining = count_orphans()
            if remaining > baseline_orphans:
                die("orphaned server-filesystem process(es): baseline=%d now=%d"
                    % (baseline_orphans, remaining))
            print("No orphaned MCP child process (baseline=%d, now=%d)."
                  % (baseline_orphans, remaining))
        else:
            print("pgrep unavailable; skipped orphan-process assertion.")

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())
