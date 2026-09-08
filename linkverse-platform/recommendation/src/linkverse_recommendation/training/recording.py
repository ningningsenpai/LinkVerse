"""将训练过程、资源采样和可恢复源码写入独立运行目录。"""

from __future__ import annotations

import ctypes
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from pathlib import Path


_recorder: ContextVar[RunRecorder | None] = ContextVar("run_recorder", default=None)
_scope: ContextVar[dict] = ContextVar("metric_scope", default={})


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str, allow_nan=False), encoding="utf-8")
    os.replace(temporary, path)


def record(kind: str, **values) -> None:
    active = _recorder.get()
    if active is not None:
        active.emit(kind, **{**_scope.get(), **values})


def run_identity() -> dict:
    active = _recorder.get()
    return dict(active.identity) if active else {"source_sha256": None}


@contextmanager
def metric_scope(**values):
    token = _scope.set({**_scope.get(), **values})
    try:
        yield
    finally:
        _scope.reset(token)


class RunRecorder:
    """每个运行独占目录，失败也保留日志，后台采样不触碰业务数据。"""

    def __init__(self, directory: Path, config: dict, source_root: Path | None = None):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        (self.directory / "raw").mkdir()
        self.config = config
        self.source_root = source_root
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._streams = {}
        self.started = time.perf_counter()

    def __enter__(self):
        self.token = _recorder.set(self)
        write_json(self.directory / "config.json", self.config)
        identity = {"python": sys.version, "platform": platform.platform(), "pid": os.getpid()}
        if self.source_root:
            identity.update(self._snapshot_source(self.source_root.resolve()))
        self.identity = {**identity, "run_id": self.directory.name}
        write_json(self.directory / "manifest.json", identity)
        self.emit("stages", stage="run", status="STARTED")
        self.worker = None
        if self.config.get("resource_monitor", True):
            self.worker = threading.Thread(target=self._monitor, daemon=True, name="recommendation-resources")
            self.worker.start()
        return self

    def __exit__(self, exception_type, exception, traceback):
        self._stop.set()
        if self.worker is not None:
            self.worker.join(timeout=15)
        status = "FAILED" if exception else "COMPLETED"
        self.emit("stages", stage="run", status=status, error_type=exception_type.__name__ if exception_type else None)
        with self._lock:
            for stream in self._streams.values():
                stream.close()
        write_json(self.directory / "status.json", {
            "status": status, "elapsed_seconds": time.perf_counter() - self.started,
            "error_type": exception_type.__name__ if exception_type else None,
        })
        _recorder.reset(self.token)

    def emit(self, kind: str, **values):
        payload = {"run_id": self.directory.name, "utc": datetime.now(timezone.utc).isoformat(), **values}
        line = json.dumps(payload, ensure_ascii=False, default=str, allow_nan=False)
        with self._lock:
            if kind not in self._streams:
                self._streams[kind] = (self.directory / "raw" / f"{kind}.jsonl").open("a", encoding="utf-8")
            self._streams[kind].write(line + "\n")

    def flush(self):
        with self._lock:
            for stream in self._streams.values():
                stream.flush()

    def _monitor(self):
        previous = (time.perf_counter(), sum(os.times()[:2]))
        previous_system = _system_times()
        while True:
            now = (time.perf_counter(), sum(os.times()[:2]))
            cpu = 100 * (now[1] - previous[1]) / max(now[0] - previous[0], 1e-6)
            previous = now
            sample = {"process_cpu_percent": cpu, "disk_free_bytes": shutil.disk_usage(self.directory).free}
            try:
                sample.update(_memory())
                current_system = _system_times()
                if previous_system and current_system:
                    idle = current_system[0] - previous_system[0]
                    total = sum(current_system[1:]) - sum(previous_system[1:])
                    sample["system_cpu_percent"] = 100 * (1 - idle / total) if total > 0 else None
                previous_system = current_system
                if os.name == "nt":
                    sample["java_processes"] = [{"name": process["name"], "pid": process["pid"], **_memory(process["pid"])} for process in self.config.get("java_processes", [])]
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu", "--format=csv,noheader,nounits"],
                    capture_output=True, text=True, timeout=4,
                    creationflags=0x08000000 if os.name == "nt" else 0,
                )
                if result.returncode == 0:
                    gpu, used, total, temperature = map(float, result.stdout.strip().splitlines()[0].split(","))
                    sample.update(gpu_utilization_percent=gpu, gpu_used_mib=used, gpu_total_mib=total, gpu_temperature_c=temperature)
                else:
                    sample["gpu_status"] = "UNAVAILABLE"
                if self.config.get("container_metrics"):
                    names = self.config.get("metric_containers", ["linkverse-mvp-mysql", "linkverse-mvp-redis", "linkverse-mvp-rabbitmq", "linkverse-mvp-recommendation"])
                    containers = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", *names], capture_output=True, text=True, timeout=4, creationflags=0x08000000 if os.name == "nt" else 0)
                    sample["containers"] = [json.loads(line) for line in containers.stdout.splitlines() if line.startswith("{")]
                    sample["container_sampling_status"] = "AVAILABLE" if containers.returncode == 0 and sample["containers"] else "UNAVAILABLE"
                    sample["container_sampling_exit_code"] = containers.returncode
            except (OSError, ValueError, subprocess.TimeoutExpired) as error:
                sample["sampling_error_type"] = type(error).__name__
            self.emit("resources", **sample)
            self.flush()
            if self._stop.wait(max(0.1, 5 - (time.perf_counter() - now[0]))):
                return

    def _snapshot_source(self, root: Path) -> dict:
        # 只归档版本化实现、测试和构建文件；本地环境、凭据与原始数据不进入源码快照。
        prefixes = [
            "linkverse-platform/recommendation/src", "linkverse-platform/recommendation/tests",
            "linkverse-platform/backend", "linkverse-platform/scripts", "linkverse-platform/contracts",
        ]
        result = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", *prefixes], cwd=root, capture_output=True, text=True, check=True)
        files = sorted(set(result.stdout.splitlines()))
        files += [f"linkverse-platform/recommendation/{name}" for name in ("pyproject.toml", "environment.yml", "conda-lock.yml", "Dockerfile")]
        digests = {}
        archive = self.directory / "raw" / "source.zip"
        with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as output:
            for name in sorted(set(files)):
                path = root / name
                if not path.is_file() or path.name.startswith(".env") or "secrets" in path.parts or path.name == "application-local.yml":
                    continue
                content = path.read_bytes()
                digests[name] = hashlib.sha256(content).hexdigest()
                output.writestr(name, content)
        write_json(self.directory / "source-files.json", digests)
        revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
        source_hash = hashlib.sha256(json.dumps(digests, sort_keys=True).encode()).hexdigest()
        return {"git_commit": revision, "source_sha256": source_hash, "source_archive": "raw/source.zip"}


def _system_times():
    if os.name != "nt":
        return None
    values = [ctypes.c_ulonglong() for _ in range(3)]
    if not ctypes.windll.kernel32.GetSystemTimes(*(ctypes.byref(value) for value in values)):
        return None
    return tuple(value.value for value in values)


def _memory(pid=None) -> dict:
    if os.name != "nt":
        import resource
        return {"process_peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024}

    class ProcessMemory(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("faults", ctypes.c_ulong)] + [
            (name, ctypes.c_size_t) for name in ("peak_rss", "rss", "peak_pool", "pool", "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile", "private")
        ]

    class SystemMemory(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
            (name, ctypes.c_ulonglong) for name in ("total", "available", "total_page", "available_page", "total_virtual", "available_virtual", "extended")
        ]

    counters = ProcessMemory()
    counters.cb = ctypes.sizeof(counters)
    kernel = ctypes.windll.kernel32
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    kernel.OpenProcess.restype = ctypes.c_void_p
    handle = kernel.OpenProcess(0x0400 | 0x0010, False, int(pid)) if pid is not None else kernel.GetCurrentProcess()
    if not handle:
        return {"process_rss_bytes": None, "process_status": "UNAVAILABLE"}
    try:
        succeeded = ctypes.windll.psapi.GetProcessMemoryInfo(ctypes.c_void_p(handle), ctypes.byref(counters), counters.cb)
    finally:
        if pid is not None:
            kernel.CloseHandle(ctypes.c_void_p(handle))
    if not succeeded:
        return {"process_rss_bytes": None, "process_status": "UNAVAILABLE"}
    if pid is not None:
        return {"process_rss_bytes": counters.rss, "process_peak_rss_bytes": counters.peak_rss}
    memory = SystemMemory()
    memory.length = ctypes.sizeof(memory)
    kernel.GlobalMemoryStatusEx(ctypes.byref(memory))
    return {"process_rss_bytes": counters.rss, "process_peak_rss_bytes": counters.peak_rss, "system_available_bytes": memory.available}
