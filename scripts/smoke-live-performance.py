#!/usr/bin/env python3
"""Reproducible collector-only and authorized Live UI performance smoke tests."""

from __future__ import annotations

import argparse
import ctypes
import errno as errno_codes
import http.client
import json
import math
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time
from typing import Any


PROTOCOL_VERSION = 3
WEB_UI_SCHEMA_VERSION = 2
MAX_FRAME_BYTES = 32 * 1024 * 1024
TOKEN_LENGTH = 64
DEFAULT_FAST_SAMPLES = 20
DEFAULT_TARGET_PROCESSES = 1_000
DEFAULT_IDLE_SECONDS = 10.0
DEFAULT_LIVE_SECONDS = 90.0
DEFAULT_LIVE_POLL_SECONDS = 1.0
DEFAULT_LIVE_IDLE_WAIT_SECONDS = 35.0
DEFAULT_LIVE_IDLE_OBSERVATION_SECONDS = 3.0
COLLECTOR_IDLE_CPU_TARGET_PERCENT = 1.0
FAST_P95_TARGET_MILLISECONDS = 500.0
LIVE_CPU_TARGET_PERCENT = 15.0
RUSAGE_INFO_V4 = 4
RUSAGE_UUID_BYTES = 16


class SmokeFailure(RuntimeError):
    pass


class ProcessCpuClock:
    def __init__(self) -> None:
        if sys.platform != "darwin":
            raise SmokeFailure("process CPU measurement requires macOS")
        self._libproc = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        self._libproc.proc_pid_rusage.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_void_p,
        ]
        self._libproc.proc_pid_rusage.restype = ctypes.c_int
        self._libsystem = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
        self._libsystem.mach_timebase_info.argtypes = [ctypes.c_void_p]
        self._libsystem.mach_timebase_info.restype = ctypes.c_int

        class Timebase(ctypes.Structure):
            _fields_ = [("numer", ctypes.c_uint32), ("denom", ctypes.c_uint32)]

        timebase = Timebase()
        if self._libsystem.mach_timebase_info(ctypes.byref(timebase)) != 0:
            raise SmokeFailure("unable to read the mach timebase")
        if timebase.denom == 0:
            raise SmokeFailure("mach timebase denominator is zero")
        self._nanoseconds_per_tick = timebase.numer / timebase.denom
        self._sources: dict[int, str] = {}

    def seconds(self, process_id: int) -> float:
        buffer = ctypes.create_string_buffer(1_024)
        if self._libproc.proc_pid_rusage(process_id, RUSAGE_INFO_V4, buffer) != 0:
            error_number = ctypes.get_errno()
            if error_number in {errno_codes.EACCES, errno_codes.EPERM}:
                self._sources[process_id] = "ps-cumulative-time"
                return process_cpu_seconds_from_ps(process_id)
            raise SmokeFailure(
                f"unable to read cumulative CPU for pid {process_id}: "
                f"{os.strerror(error_number)}"
            )
        user_ticks, system_ticks = struct.unpack_from(
            "=QQ",
            buffer.raw,
            RUSAGE_UUID_BYTES,
        )
        self._sources[process_id] = "proc_pid_rusage-v4"
        return (user_ticks + system_ticks) * self._nanoseconds_per_tick / 1_000_000_000

    def source(self, process_id: int) -> str:
        return self._sources.get(process_id, "unmeasured")


def process_cpu_seconds_from_ps(process_id: int) -> float:
    completed = subprocess.run(
        ["/bin/ps", "-p", str(process_id), "-o", "time="],
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        check=False,
    )
    value = completed.stdout.strip()
    if completed.returncode != 0 or not value:
        raise SmokeFailure(f"unable to read cumulative CPU for pid {process_id}")
    try:
        day_text, separator, clock_text = value.partition("-")
        days = int(day_text) if separator else 0
        fields = (clock_text if separator else day_text).split(":")
        if len(fields) == 2:
            hours = 0
            minutes = int(fields[0])
        elif len(fields) == 3:
            hours = int(fields[0])
            minutes = int(fields[1])
        else:
            raise ValueError("unexpected field count")
        seconds = float(fields[-1])
    except ValueError as failure:
        raise SmokeFailure(f"ps returned an invalid cumulative CPU value for pid {process_id}") from failure
    if days < 0 or hours < 0 or minutes < 0 or not 0.0 <= seconds < 60.0:
        raise SmokeFailure(f"ps returned an invalid cumulative CPU value for pid {process_id}")
    return (((days * 24) + hours) * 60 + minutes) * 60 + seconds


def receive_exact(connection: socket.socket, size: int, deadline: float) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        timeout = deadline - time.monotonic()
        if timeout <= 0:
            raise SmokeFailure("collector frame deadline expired")
        connection.settimeout(timeout)
        chunk = connection.recv(remaining)
        if not chunk:
            raise SmokeFailure("collector closed a truncated frame")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def receive_frame(connection: socket.socket, timeout_seconds: float = 35.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    header = receive_exact(connection, 4, deadline)
    (length,) = struct.unpack("!I", header)
    if length == 0 or length > MAX_FRAME_BYTES:
        raise SmokeFailure(f"collector returned invalid frame length {length}")
    payload = receive_exact(connection, length, deadline)
    try:
        decoded = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise SmokeFailure("collector returned invalid JSON") from failure
    if not isinstance(decoded, dict):
        raise SmokeFailure("collector returned a non-object JSON frame")
    return decoded


def send_frame(connection: socket.socket, payload: dict[str, Any]) -> None:
    encoded = json.dumps(payload, separators=(",", ":")).encode()
    connection.sendall(struct.pack("!I", len(encoded)) + encoded)


def require_protocol_frame(frame: dict[str, Any], kind: str) -> None:
    if frame.get("protocolVersion") != PROTOCOL_VERSION or frame.get("kind") != kind:
        raise SmokeFailure(f"collector did not return protocol-v3 {kind}")


def collector_exchange(socket_path: str, request: dict[str, Any]) -> dict[str, Any]:
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        connection.settimeout(35.0)
        connection.connect(socket_path)
        require_protocol_frame(receive_frame(connection), "hello")
        send_frame(connection, request)
        return receive_frame(connection)
    finally:
        connection.close()


def collector_probe(socket_path: str) -> float:
    started = time.monotonic()
    response = collector_exchange(
        socket_path,
        {"protocolVersion": PROTOCOL_VERSION, "kind": "probe"},
    )
    require_protocol_frame(response, "ack")
    return time.monotonic() - started


def collector_capture(socket_path: str, profile: str) -> tuple[dict[str, Any], float]:
    started = time.monotonic()
    response = collector_exchange(
        socket_path,
        {
            "protocolVersion": PROTOCOL_VERSION,
            "kind": "capture",
            "profile": profile,
        },
    )
    elapsed = time.monotonic() - started
    require_protocol_frame(response, "snapshot")
    if response.get("appliedProfile") != profile:
        raise SmokeFailure(f"collector applied an unexpected profile for {profile}")
    snapshot = response.get("snapshot")
    if not isinstance(snapshot, dict):
        raise SmokeFailure("collector snapshot payload is missing")
    return snapshot, elapsed


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    if not values:
        raise SmokeFailure("cannot calculate a percentile without samples")
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[index]


def stop_process(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def stop_children(children: list[subprocess.Popen[Any]]) -> None:
    for child in children:
        if child.poll() is None:
            child.terminate()
    for child in children:
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=5)


def wait_for_socket(socket_path: str, collector: subprocess.Popen[Any]) -> None:
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if collector.poll() is not None:
            raise SmokeFailure("unprivileged collector exited during startup")
        if os.path.exists(socket_path):
            return
        time.sleep(0.02)
    raise SmokeFailure("unprivileged collector did not publish its socket")


def add_sleep_processes(
    socket_path: str,
    target_processes: int,
    children: list[subprocess.Popen[Any]],
) -> tuple[int, str | None]:
    snapshot, _ = collector_capture(socket_path, "LIVE_FAST")
    total = int(snapshot.get("totalProcessCount", -1))
    if total < 0:
        raise SmokeFailure("collector snapshot has no total process count")
    limit_detail: str | None = None
    while total < target_processes and len(children) < target_processes:
        batch_size = min(100, target_processes - total, target_processes - len(children))
        for _ in range(batch_size):
            try:
                children.append(
                    subprocess.Popen(
                        ["/bin/sleep", "300"],
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
                )
            except OSError as failure:
                limit_detail = f"{failure.__class__.__name__}: {failure.strerror or 'system limit'}"
                break
        snapshot, _ = collector_capture(socket_path, "LIVE_FAST")
        next_total = int(snapshot.get("totalProcessCount", -1))
        if next_total < 0:
            raise SmokeFailure("collector snapshot has no total process count")
        if limit_detail is not None or next_total <= total:
            total = next_total
            break
        total = next_total
    return total, limit_detail


def default_collector_binary() -> Path:
    project_directory = Path(__file__).resolve().parent.parent
    return (
        project_directory
        / "build/tasks/_harmon-collector_linkMacosArm64Release/harmon-collector.kexe"
    )


def collector_only(arguments: argparse.Namespace) -> dict[str, Any]:
    binary = Path(arguments.collector_binary).resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise SmokeFailure(
            f"release collector is not executable at {binary}; run ./kotlin build --variant release"
        )
    cpu_clock = ProcessCpuClock()
    children: list[subprocess.Popen[Any]] = []
    collector: subprocess.Popen[Any] | None = None
    with tempfile.TemporaryDirectory(prefix="harmon-live-perf-", dir="/tmp") as directory:
        socket_path = str(Path(directory) / "collector.sock")
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as collector_log:
            try:
                collector = subprocess.Popen(
                    [
                        str(binary),
                        "--socket",
                        socket_path,
                        "--allowed-uid",
                        str(os.getuid()),
                        "--allowed-gid",
                        str(os.getgid()),
                        "--allow-unprivileged",
                    ],
                    stdin=subprocess.DEVNULL,
                    stdout=collector_log,
                    stderr=subprocess.STDOUT,
                )
                wait_for_socket(socket_path, collector)
                collector_probe(socket_path)

                idle_started = time.monotonic()
                idle_cpu_started = cpu_clock.seconds(collector.pid)
                time.sleep(arguments.idle_seconds)
                idle_cpu_ended = cpu_clock.seconds(collector.pid)
                idle_wall_seconds = time.monotonic() - idle_started
                idle_cpu_seconds = max(0.0, idle_cpu_ended - idle_cpu_started)
                idle_cpu_percent = idle_cpu_seconds / idle_wall_seconds * 100.0

                observed_total, process_limit = add_sleep_processes(
                    socket_path,
                    arguments.target_processes,
                    children,
                )

                active_started = time.monotonic()
                active_cpu_started = cpu_clock.seconds(collector.pid)
                probe_seconds = collector_probe(socket_path)
                full_snapshot, full_seconds = collector_capture(socket_path, "FULL")
                fast_seconds: list[float] = []
                for _ in range(arguments.fast_samples):
                    _, elapsed = collector_capture(socket_path, "LIVE_FAST")
                    fast_seconds.append(elapsed)
                active_cpu_ended = cpu_clock.seconds(collector.pid)
                active_wall_seconds = time.monotonic() - active_started
                active_cpu_seconds = max(0.0, active_cpu_ended - active_cpu_started)
                fast_p95_milliseconds = percentile_nearest_rank(fast_seconds, 0.95) * 1_000.0

                return {
                    "ok": (
                        idle_cpu_percent <= COLLECTOR_IDLE_CPU_TARGET_PERCENT
                        and fast_p95_milliseconds <= FAST_P95_TARGET_MILLISECONDS
                    ),
                    "mode": "collector-only",
                    "protocolVersion": PROTOCOL_VERSION,
                    "profiles": ["FULL", "LIVE_FAST"],
                    "targetProcessCount": arguments.target_processes,
                    "observedProcessCount": int(
                        full_snapshot.get("totalProcessCount", observed_total)
                    ),
                    "spawnedSleepProcesses": len(children),
                    "processTargetReached": observed_total >= arguments.target_processes,
                    "processLimit": process_limit,
                    "probeMilliseconds": probe_seconds * 1_000.0,
                    "fullMilliseconds": full_seconds * 1_000.0,
                    "fastSamples": len(fast_seconds),
                    "fastP95Milliseconds": fast_p95_milliseconds,
                    "collectorIdleWallSeconds": idle_wall_seconds,
                    "collectorIdleCpuSeconds": idle_cpu_seconds,
                    "collectorIdleCpuPercent": idle_cpu_percent,
                    "collectorActiveWallSeconds": active_wall_seconds,
                    "collectorActiveCpuSeconds": active_cpu_seconds,
                    "collectorCpuSource": cpu_clock.source(collector.pid),
                    "targets": {
                        "collectorIdleCpuPercentAtMost": COLLECTOR_IDLE_CPU_TARGET_PERCENT,
                        "fastP95MillisecondsAtMost": FAST_P95_TARGET_MILLISECONDS,
                    },
                    "evidenceScope": "unprivileged collector lower bound; not end-to-end Live CPU",
                }
            except SmokeFailure as failure:
                if collector is not None:
                    stop_process(collector)
                collector_log.seek(0)
                lines = [line.strip() for line in collector_log if line.strip()]
                diagnostic = lines[-1].replace(directory, "<temp>") if lines else None
                detail = f"; collector diagnostic: {diagnostic}" if diagnostic else ""
                raise SmokeFailure(f"{failure}{detail}") from failure
            finally:
                stop_children(children)
                if collector is not None:
                    stop_process(collector)


def parse_endpoint(endpoint_file: str) -> tuple[int, str]:
    try:
        lines = Path(endpoint_file).read_text(encoding="utf-8").splitlines()
    except OSError as failure:
        raise SmokeFailure("unable to read the explicit Live UI endpoint file") from failure
    fields: dict[str, str] = {}
    for line in lines:
        if not line.strip():
            continue
        key, separator, value = line.partition("=")
        if not separator or key in fields:
            raise SmokeFailure("Live UI endpoint file is invalid")
        fields[key] = value
    if set(fields) != {"port", "token"}:
        raise SmokeFailure("Live UI endpoint file is invalid")
    try:
        port = int(fields["port"])
    except ValueError as failure:
        raise SmokeFailure("Live UI endpoint port is invalid") from failure
    token = fields["token"]
    if not 1 <= port <= 65_535:
        raise SmokeFailure("Live UI endpoint port is invalid")
    if len(token) != TOKEN_LENGTH or any(character not in "0123456789abcdef" for character in token):
        raise SmokeFailure("Live UI endpoint token is invalid")
    return port, token


def live_request(port: int, token: str, watch: bool) -> tuple[dict[str, Any], bytes]:
    target = "/api/live?watch=1" if watch else "/api/live"
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5.0)
    try:
        connection.request(
            "GET",
            target,
            headers={
                "Host": f"127.0.0.1:{port}",
                "Authorization": f"Bearer {token}",
            },
        )
        response = connection.getresponse()
        body = response.read()
    except (OSError, http.client.HTTPException) as failure:
        raise SmokeFailure("authorized Live UI request failed") from failure
    finally:
        connection.close()
    if response.status != 200:
        raise SmokeFailure(f"authorized Live UI request returned HTTP {response.status}")
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise SmokeFailure("Live UI returned invalid JSON") from failure
    if not isinstance(payload, dict):
        raise SmokeFailure("Live UI returned a non-object payload")
    if payload.get("schemaVersion") != WEB_UI_SCHEMA_VERSION:
        raise SmokeFailure(f"Live UI did not return schema v{WEB_UI_SCHEMA_VERSION}")
    return payload, body


def process_cpu_snapshot(cpu_clock: ProcessCpuClock, process_ids: list[int]) -> dict[int, float]:
    return {process_id: cpu_clock.seconds(process_id) for process_id in process_ids}


def process_cpu_delta(
    before: dict[int, float],
    after: dict[int, float],
) -> dict[int, float]:
    return {
        process_id: max(0.0, after[process_id] - seconds)
        for process_id, seconds in before.items()
    }


def live_end_to_end(arguments: argparse.Namespace) -> dict[str, Any]:
    port, token = parse_endpoint(arguments.endpoint_file)
    process_ids = [arguments.agent_pid, arguments.collector_pid]
    if len(set(process_ids)) != 2 or any(process_id <= 0 for process_id in process_ids):
        raise SmokeFailure("agent and collector PIDs must be distinct positive integers")
    cpu_clock = ProcessCpuClock()
    observed_profiles: set[str] = set()
    statuses: set[str] = set()
    started = time.monotonic()
    active_cpu_started = process_cpu_snapshot(cpu_clock, process_ids)
    deadline = started + arguments.duration_seconds
    request_count = 0
    while True:
        payload, _ = live_request(port, token, watch=True)
        request_count += 1
        status = payload.get("status")
        if isinstance(status, str):
            statuses.add(status)
        profile = payload.get("appliedProfile")
        if profile is not None:
            if profile not in {"FULL", "LIVE_FAST"}:
                raise SmokeFailure("Live UI reported an unknown collection profile")
            observed_profiles.add(profile)
        now = time.monotonic()
        if now >= deadline:
            break
        time.sleep(min(arguments.poll_seconds, deadline - now))

    active_cpu_ended = process_cpu_snapshot(cpu_clock, process_ids)
    active_wall_seconds = time.monotonic() - started
    active_cpu_delta = process_cpu_delta(active_cpu_started, active_cpu_ended)
    active_cpu_seconds = sum(active_cpu_delta.values())
    active_cpu_percent = active_cpu_seconds / active_wall_seconds * 100.0

    time.sleep(arguments.idle_wait_seconds)
    _, idle_anchor = live_request(port, token, watch=False)
    idle_started = time.monotonic()
    idle_cpu_started = process_cpu_snapshot(cpu_clock, process_ids)
    time.sleep(arguments.idle_observation_seconds)
    _, idle_after = live_request(port, token, watch=False)
    idle_cpu_ended = process_cpu_snapshot(cpu_clock, process_ids)
    idle_wall_seconds = time.monotonic() - idle_started
    idle_cpu_delta = process_cpu_delta(idle_cpu_started, idle_cpu_ended)
    idle_cpu_seconds = sum(idle_cpu_delta.values())
    idle_cpu_percent = idle_cpu_seconds / idle_wall_seconds * 100.0
    returned_to_idle = idle_anchor == idle_after
    profiles_complete = observed_profiles == {"FULL", "LIVE_FAST"}

    return {
        "ok": (
            active_cpu_percent <= LIVE_CPU_TARGET_PERCENT
            and profiles_complete
            and returned_to_idle
        ),
        "mode": "live-end-to-end",
        "protocolVersion": PROTOCOL_VERSION,
        "webUiSchemaVersion": WEB_UI_SCHEMA_VERSION,
        "requestCount": request_count,
        "observedProfiles": sorted(observed_profiles),
        "observedStatuses": sorted(statuses),
        "activeWallSeconds": active_wall_seconds,
        "activeCpuSeconds": active_cpu_seconds,
        "activeCpuPercent": active_cpu_percent,
        "agentCpuSeconds": active_cpu_delta[arguments.agent_pid],
        "collectorCpuSeconds": active_cpu_delta[arguments.collector_pid],
        "agentCpuSource": cpu_clock.source(arguments.agent_pid),
        "collectorCpuSource": cpu_clock.source(arguments.collector_pid),
        "idleObservationWallSeconds": idle_wall_seconds,
        "idleCpuSeconds": idle_cpu_seconds,
        "idleCpuPercent": idle_cpu_percent,
        "returnedToIdle": returned_to_idle,
        "fastP95Milliseconds": None,
        "fastP95Available": False,
        "targets": {
            "activeAgentAndCollectorCpuPercentAtMost": LIVE_CPU_TARGET_PERCENT,
            "fastP95MillisecondsAtMostWhenAvailable": FAST_P95_TARGET_MILLISECONDS,
        },
        "evidenceScope": "authorized end-to-end Live UI",
    }


def positive_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive finite number")
    return parsed


def bounded_positive_int(maximum: int):
    def parse(value: str) -> int:
        parsed = int(value)
        if not 1 <= parsed <= maximum:
            raise argparse.ArgumentTypeError(f"must be between 1 and {maximum}")
        return parsed

    return parse


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    modes = result.add_subparsers(dest="mode", required=True)

    collector = modes.add_parser(
        "collector-only",
        help="measure the unprivileged release collector without installed services",
    )
    collector.add_argument(
        "--collector-binary",
        default=str(default_collector_binary()),
        help="release harmon-collector binary",
    )
    collector.add_argument(
        "--target-processes",
        type=bounded_positive_int(5_000),
        default=DEFAULT_TARGET_PROCESSES,
    )
    collector.add_argument(
        "--fast-samples",
        type=bounded_positive_int(1_000),
        default=DEFAULT_FAST_SAMPLES,
    )
    collector.add_argument(
        "--idle-seconds",
        type=positive_float,
        default=DEFAULT_IDLE_SECONDS,
    )
    collector.set_defaults(run=collector_only)

    live = modes.add_parser(
        "live-end-to-end",
        help="measure an already-authorized running agent and collector",
    )
    live.add_argument("--endpoint-file", required=True)
    live.add_argument("--agent-pid", required=True, type=int)
    live.add_argument("--collector-pid", required=True, type=int)
    live.add_argument(
        "--duration-seconds",
        type=positive_float,
        default=DEFAULT_LIVE_SECONDS,
    )
    live.add_argument(
        "--poll-seconds",
        type=positive_float,
        default=DEFAULT_LIVE_POLL_SECONDS,
    )
    live.add_argument(
        "--idle-wait-seconds",
        type=positive_float,
        default=DEFAULT_LIVE_IDLE_WAIT_SECONDS,
        help="wait longer than the maximum configured lease before checking idle",
    )
    live.add_argument(
        "--idle-observation-seconds",
        type=positive_float,
        default=DEFAULT_LIVE_IDLE_OBSERVATION_SECONDS,
    )
    live.set_defaults(run=live_end_to_end)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        summary = arguments.run(arguments)
    except (SmokeFailure, KeyboardInterrupt) as failure:
        summary = {
            "ok": False,
            "mode": arguments.mode,
            "error": "interrupted" if isinstance(failure, KeyboardInterrupt) else str(failure),
        }
    except Exception as failure:
        summary = {
            "ok": False,
            "mode": arguments.mode,
            "error": f"unexpected {failure.__class__.__name__}",
        }
    print(json.dumps(summary, sort_keys=True, separators=(",", ":")))
    return 0 if summary.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
