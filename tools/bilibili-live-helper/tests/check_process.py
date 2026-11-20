"""Offline process checks for the published Bilibili room helper."""

import json
from pathlib import Path
import subprocess
import sys


def start_helper(command, config):
    process = subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8",
    )
    write(process, config)
    return process


def write(process, message):
    process.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
    process.stdin.flush()


def check_mock(command):
    with start_helper(command, {"mode": "mock"}) as helper:
        write(helper, {"type": "danmaku", "id": "first", "user": "听众", "content": "点歌 月亮"})
        write(helper, {"type": "danmaku", "id": "first", "user": "重复", "content": "重复"})
        write(helper, {"type": "danmaku", "id": "second", "user": "听众", "content": "点歌 星星"})
        helper.stdin.write('{"type": "danmaku", "content": "missing fields"}\n{\n')
        write(helper, {"type": "stop"})
        out, err = helper.communicate(timeout=5)
        assert helper.returncode == 0 and not err, (helper.returncode, err)
        assert [json.loads(line) for line in out.splitlines()] == [
            {"type": "status", "state": "connected", "reason": "mock"},
            {"type": "danmaku", "id": "first", "user": "听众", "content": "点歌 月亮"},
            {"type": "danmaku", "id": "second", "user": "听众", "content": "点歌 星星"},
            {"type": "status", "state": "disconnected", "reason": "stopped"},
        ]


def check_invalid_room(command):
    for room_id in ("", "0", "https://live.bilibili.com/123", "123abc", "99999999999999999999"):
        result = subprocess.run(
            command, input=json.dumps({"mode": "live", "roomId": room_id}) + "\n",
            text=True, encoding="utf-8", capture_output=True, timeout=5,
        )
        assert result.returncode == 1 and not result.stderr
        assert json.loads(result.stdout) == {"type": "status", "state": "failed", "reason": "invalid_room"}


if __name__ == "__main__":
    helper_command = sys.argv[1:]
    if not helper_command:
        helper_command = [str(Path(__file__).resolve().parent.parent / "bin" / "Release"
                              / "net8.0" / "win-x64" / "publish" / "BilibiliLiveHelper.exe")]
    check_mock(helper_command)
    check_invalid_room(helper_command)
    print("Published helper mock, duplicate suppression, shutdown and room validation passed.")
