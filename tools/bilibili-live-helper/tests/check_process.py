"""Check the published helper and local HTTP lifecycle without opening a Bilibili session."""

import http.client
import http.server
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
from urllib.error import URLError


class FakeSigner(http.server.BaseHTTPRequestHandler):
    requests = queue.Queue()
    allow_start_reply = threading.Event()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.requests.put((self.path, body))
        if self.path == "/start":
            assert self.allow_start_reply.wait(5)
            payload = {
                "ok": True,
                "gameId": "fake-session",
                "authBody": '{"fixture":true}',
                "websocketLinks": ["wss://127.0.0.1:1/"],
            }
        else:
            payload = {"ok": True}
        content = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_args):
        pass


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


def check_stop_during_start(command):
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeSigner) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        endpoint = f"http://127.0.0.1:{server.server_port}/"
        try:
            with start_helper(command, {"mode": "live", "identityCode": "fixture-code", "endpoint": endpoint}) as helper:
                assert FakeSigner.requests.get(timeout=5) == ("/start", {"identityCode": "fixture-code"})
                write(helper, {"type": "stop"})
                FakeSigner.allow_start_reply.set()
                out, err = helper.communicate(timeout=15)
                assert FakeSigner.requests.get(timeout=5) == ("/end", {"gameId": "fake-session"})
                assert helper.returncode == 0 and not err, (helper.returncode, err)
                statuses = [json.loads(line) for line in out.splitlines()]
                assert statuses[0] == {"type": "status", "state": "connecting", "reason": "live"}
                assert statuses[-1] == {"type": "status", "state": "disconnected", "reason": "stopped"}
                assert "fixture" not in out and "fake-session" not in out
        finally:
            server.shutdown()
            worker.join(timeout=5)


def check_project_heartbeat(command):
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeSigner) as server:
        FakeSigner.allow_start_reply.set()
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            endpoint = f"http://127.0.0.1:{server.server_port}/"
            with start_helper(command, {"mode": "live", "identityCode": "fixture-code", "endpoint": endpoint}) as helper:
                assert FakeSigner.requests.get(timeout=5) == ("/start", {"identityCode": "fixture-code"})
                assert FakeSigner.requests.get(timeout=25) == ("/heartbeat", {"gameId": "fake-session"})
                helper.stdin.close()
                helper.stdin = None
                out, err = helper.communicate(timeout=5)
                assert FakeSigner.requests.get(timeout=5) == ("/end", {"gameId": "fake-session"})
                assert helper.returncode == 0 and not err, (helper.returncode, err)
                statuses = [json.loads(line) for line in out.splitlines()]
                assert any(status["state"] == "reconnecting" for status in statuses)
                assert statuses[-1] == {"type": "status", "state": "disconnected", "reason": "stopped"}
        finally:
            server.shutdown()
            worker.join(timeout=5)


def check_signer(command):
    env = {
        **os.environ,
        "BILIBILI_LIVE_APP_ID": "1234567890123",
        "BILIBILI_LIVE_ACCESS_KEY_ID": "fixture-key",
        "BILIBILI_LIVE_ACCESS_KEY_SECRET": "fixture-secret",
    }
    with subprocess.Popen(
        [*command, "--signer"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    ) as signer:
        try:
            deadline = time.monotonic() + 5
            while True:
                try:
                    status = request("GET", "/")
                    break
                except ConnectionRefusedError:
                    if signer.poll() is not None or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.1)

            assert signer.poll() is None and status == 404
            assert request("POST", "/start", "invalid JSON", {"Content-Type": "application/json"}) == 400
            assert request("POST", "/end", "{}", {"Content-Type": "application/json"}) == 400
            assert request("POST", "/start", "{}", {"Content-Type": "text/plain"}) == 400
            assert request("POST", "/start", "{}", {"Content-Type": "application/json", "Origin": "https://example.invalid"}) == 403
        finally:
            signer.terminate()
            out, err = signer.communicate(timeout=5)
            assert not out and "fixture-" not in err
            assert "listening on http://127.0.0.1:19754/" in err


def request(method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", 19754, timeout=2)
    try:
        connection.request(method, path, body, headers or {})
        response = connection.getresponse()
        response.read()
        return response.status
    finally:
        connection.close()


if __name__ == "__main__":
    helper_command = sys.argv[1:]
    if not helper_command:
        helper_command = [str(Path(__file__).resolve().parent.parent / "bin" / "Release"
                              / "net8.0" / "win-x64" / "publish" / "BilibiliLiveHelper.exe")]
    check_mock(helper_command)
    check_stop_during_start(helper_command)
    check_project_heartbeat(helper_command)
    check_signer(helper_command)
    print("Published helper mock, project heartbeat, stop cleanup and signer routing checks passed.")
