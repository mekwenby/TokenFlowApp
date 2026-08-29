#!/usr/bin/env python3
import ctypes
import fcntl
import contextlib
import json
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

VERSION = 7
ROOT = pathlib.Path("~/.tokenflow/infinite-cloud").expanduser()
TASKS = ROOT / "tasks"
IMMEDIATE_LOG_LIMIT = 40000
TERMINAL_STATUSES = frozenset(("succeeded", "failed", "cancelled", "timed_out"))
ALLOWED_TRANSITIONS = {
    "unknown": frozenset(("queued", "running", "failed", "cancelled")),
    "queued": frozenset(("running", "failed", "cancelled")),
    "running": frozenset(("succeeded", "failed", "cancelled", "timed_out")),
}

class TaskRequestConflict(ValueError):
    pass

def emit(value):
    sys.stdout.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")))

def atomic_json(path, value):
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)

def task_dir(task_id):
    if not task_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in task_id):
        raise ValueError("invalid task id")
    return TASKS / task_id

@contextlib.contextmanager
def file_lock(path, blocking=True):
    lock = acquire_file_lock(path, blocking)
    if lock is None:
        raise BlockingIOError("lock is already held")
    try:
        yield
    finally:
        release_file_lock(lock)

def acquire_file_lock(path, blocking=True):
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.open("a+")
    try:
        flags = fcntl.LOCK_EX if blocking else fcntl.LOCK_EX | fcntl.LOCK_NB
        fcntl.flock(lock, flags)
        return lock
    except BlockingIOError:
        lock.close()
        return None

def release_file_lock(lock):
    try:
        fcntl.flock(lock, fcntl.LOCK_UN)
    finally:
        lock.close()

def task_lock_path(task_id):
    task_dir(task_id)
    return TASKS / ".locks" / (task_id + ".lock")

def read_state_unlocked(task_id):
    path = task_dir(task_id) / "state.json"
    if not path.exists():
        raise ValueError("task not found")
    return json.loads(path.read_text(encoding="utf-8"))

def state(task_id):
    with file_lock(task_lock_path(task_id)):
        return reconcile_state_unlocked(task_id, read_state_unlocked(task_id))

def write_state_unlocked(task_id, current):
    current["updated_at"] = int(time.time() * 1000)
    atomic_json(task_dir(task_id) / "state.json", current)
    return current

def transition_unlocked(task_id, current, next_status, **changes):
    previous = current.get("status", "unknown")
    if next_status != previous:
        if previous in TERMINAL_STATUSES:
            return current
        if next_status not in ALLOWED_TRANSITIONS.get(previous, frozenset()):
            raise ValueError("invalid task status transition: %s -> %s" % (previous, next_status))
    current.update(changes)
    current["status"] = next_status
    return write_state_unlocked(task_id, current)

def update(task_id, **changes):
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        next_status = changes.pop("status", None)
        if next_status is not None:
            return transition_unlocked(task_id, current, next_status, **changes)
        current.update(changes)
        return write_state_unlocked(task_id, current)

def expanded(path):
    return pathlib.Path(os.path.expandvars(os.path.expanduser(path))).resolve()

def command_for(request, directory):
    kind = request.get("kind", "shell")
    if kind == "shell":
        return ["/bin/sh", "-lc", request.get("command", "")]
    extension, runtime = ("py", "python3") if kind == "python" else ("js", "node")
    source = directory / ("script." + extension)
    source.write_text(request.get("code", ""), encoding="utf-8")
    return [runtime, str(source)] + [str(x) for x in request.get("arguments", [])]

def artifact_paths(request, workdir):
    artifacts = []
    for value in request.get("artifact_paths", []):
        path = pathlib.Path(os.path.expandvars(os.path.expanduser(str(value))))
        artifacts.append(str((path if path.is_absolute() else workdir / path).resolve()))
    return artifacts

def process_group_exists(process_group_id):
    try:
        os.killpg(process_group_id, 0)
        return True
    except ProcessLookupError:
        return False

def wait_for_process_group_exit(process_group_id, timeout, process=None):
    deadline = time.monotonic() + timeout
    while True:
        if process is not None:
            process.poll()
        if not process_group_exists(process_group_id):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)

def signal_process_group(process_group_id, signal_number):
    try:
        os.killpg(process_group_id, signal_number)
    except ProcessLookupError:
        pass

def linux_process_identity(process_id):
    process_id = int(process_id)
    if process_id <= 1:
        raise ValueError("invalid process id")
    process_directory = pathlib.Path("/proc") / str(process_id)
    raw_stat = (process_directory / "stat").read_text(encoding="utf-8")
    command_end = raw_stat.rfind(")")
    fields = raw_stat[command_end + 1:].split() if command_end >= 0 else []
    if len(fields) < 20:
        raise RuntimeError("invalid Linux process identity")
    return {
        "pid": process_id,
        "parent_pid": int(fields[1]),
        "process_group_id": int(fields[2]),
        "start_time": int(fields[19]),
        "uid": process_directory.stat().st_uid,
    }

def terminate_process_group_id(process_group_id, process=None):
    if process_group_id <= 1:
        raise ValueError("invalid process group id")
    signal_process_group(process_group_id, signal.SIGTERM)
    if not wait_for_process_group_exit(process_group_id, 5, process):
        signal_process_group(process_group_id, signal.SIGKILL)
        if not wait_for_process_group_exit(process_group_id, 5, process):
            raise RuntimeError("process group did not exit after SIGKILL")
    if process is not None:
        process.wait()

def terminate_process_group(process):
    terminate_process_group_id(process.pid, process)

def configure_immediate_process():
    os.setsid()
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "unable to configure parent-death signal")
    if os.getppid() == 1:
        os.kill(os.getpid(), signal.SIGKILL)

def append_tail(target, value, limit):
    target.extend(value)
    overflow = len(target) - limit
    if overflow > 0:
        del target[:overflow]

def drain_output(source, target, limit):
    try:
        while True:
            value = source.read(65536)
            if not value:
                return
            append_tail(target, value, limit)
    finally:
        source.close()

def read_tail(path, limit):
    if not path.exists():
        return ""
    with path.open("rb") as source:
        source.seek(0, os.SEEK_END)
        size = source.tell()
        source.seek(max(0, size - limit), os.SEEK_SET)
        return source.read(limit).decode("utf-8", errors="replace")

def execute(request):
    ROOT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="run-", dir=ROOT) as raw_directory:
        directory = pathlib.Path(raw_directory)
        workdir = expanded(request.get("working_directory") or "~")
        workdir.mkdir(parents=True, exist_ok=True)
        command = command_for(request, directory)
        timeout = max(1, min(int(request.get("timeout_seconds", 120)), 120))
        timed_out = False
        process = None
        reader = None
        output_tail = bytearray()
        handled_signals = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
        previous_handlers = {value: signal.getsignal(value) for value in handled_signals}

        def interrupt(_signal_number, _frame):
            raise SystemExit("immediate execution interrupted")

        for value in handled_signals:
            signal.signal(value, interrupt)
        try:
            process = subprocess.Popen(
                command,
                cwd=workdir,
                env=os.environ.copy(),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                preexec_fn=configure_immediate_process,
            )
            reader = threading.Thread(
                target=drain_output,
                args=(process.stdout, output_tail, IMMEDIATE_LOG_LIMIT),
                daemon=True,
            )
            reader.start()
            try:
                exit_code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                terminate_process_group(process)
                exit_code = process.returncode
        finally:
            if process is not None and process_group_exists(process.pid):
                terminate_process_group(process)
            if reader is not None:
                reader.join(timeout=5)
            for value, handler in previous_handlers.items():
                signal.signal(value, handler)
        return {
            "exit_code": exit_code,
            "timed_out": timed_out,
            "output": bytes(output_tail).decode("utf-8", errors="replace"),
            "artifact_paths": artifact_paths(request, workdir),
        }

def run_task(task_id, request):
    directory = task_dir(task_id)
    workdir = expanded(request.get("working_directory") or str(directory))
    log = directory / "output.log"
    timeout = max(60, min(int(request.get("timeout_seconds", 3600)), 86400))
    env = os.environ.copy()
    env.update({str(k): str(v) for k, v in request.get("environment", {}).items()})
    started = int(time.time() * 1000)
    output = None
    process = None
    artifacts = []
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        if current.get("status") != "queued":
            return current
        try:
            # Preparation is part of the queued -> running transition so a queued
            # cancellation cannot create the working directory or script afterward.
            workdir.mkdir(parents=True, exist_ok=True)
            artifacts = artifact_paths(request, workdir)
            command = command_for(request, directory)
            output = log.open("ab", buffering=0)
            process = subprocess.Popen(command, cwd=workdir, env=env, stdin=subprocess.DEVNULL,
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            worker_identity = linux_process_identity(os.getpid())
            process_identity = linux_process_identity(process.pid)
            if process_identity["parent_pid"] != worker_identity["pid"] or process_identity["process_group_id"] != process.pid:
                raise RuntimeError("task process identity is invalid")
            transition_unlocked(task_id, current, "running", started_at=started,
                                artifact_paths=artifacts, pid=process.pid,
                                pid_start_time=process_identity["start_time"], process_uid=process_identity["uid"],
                                worker_pid=worker_identity["pid"], worker_start_time=worker_identity["start_time"],
                                worker_uid=worker_identity["uid"])
        except Exception as error:
            if process is not None:
                try:
                    terminate_process_group(process)
                except Exception:
                    pass
            if output is not None:
                output.close()
            transition_unlocked(task_id, current, "failed", error=str(error),
                                artifact_paths=artifacts, finished_at=int(time.time() * 1000))
            raise
    try:
        timed_out = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            terminate_process_group(process)
            code = process.returncode
        except BaseException:
            terminate_process_group(process)
            raise
    finally:
        output.close()
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        if current.get("status") != "running":
            return current
        status = "timed_out" if timed_out else ("succeeded" if code == 0 else "failed")
        return transition_unlocked(task_id, current, status, exit_code=code,
                                   finished_at=int(time.time() * 1000))

def fail_task(task_id, error):
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        if current.get("status") in TERMINAL_STATUSES:
            return current
        return transition_unlocked(task_id, current, "failed", error=str(error),
                                   finished_at=int(time.time() * 1000))

def worker(task_id):
    directory = task_dir(task_id)
    worker_guard = acquire_file_lock(directory / "worker.lock", blocking=False)
    if worker_guard is None:
        return
    try:
        request = json.loads((directory / "request.json").read_text(encoding="utf-8"))
        slots = max(1, min(int(request.get("max_concurrent_tasks", 2)), 4))
        while True:
            with file_lock(task_lock_path(task_id)):
                if read_state_unlocked(task_id).get("status") != "queued":
                    return
            for index in range(slots):
                slot = acquire_file_lock(ROOT / ("slot-%d.lock" % index), blocking=False)
                if slot is None:
                    continue
                try:
                    run_task(task_id, request)
                    return
                finally:
                    release_file_lock(slot)
            time.sleep(1)
    finally:
        release_file_lock(worker_guard)

def spawn_worker(task_id):
    return subprocess.Popen([sys.executable, str(pathlib.Path(__file__).resolve()), "_worker", task_id],
                            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)

def worker_is_active(task_id):
    worker_guard = acquire_file_lock(task_dir(task_id) / "worker.lock", blocking=False)
    if worker_guard is None:
        return True
    release_file_lock(worker_guard)
    return False

def ensure_queued_worker_unlocked(task_id, current):
    if current.get("status") != "queued" or worker_is_active(task_id):
        return current
    try:
        spawn_worker(task_id)
        return current
    except Exception as error:
        return transition_unlocked(
            task_id, current, "failed", error="Failed to start task worker: %s" % error,
            finished_at=int(time.time() * 1000),
        )

def reconcile_state_unlocked(task_id, current):
    status = current.get("status", "unknown")
    if status == "queued":
        return ensure_queued_worker_unlocked(task_id, current)
    if status == "running" and not worker_is_active(task_id):
        return transition_unlocked(
            task_id, current, "failed", error="Task worker is no longer running",
            finished_at=int(time.time() * 1000),
        )
    return current

def submit(request):
    task_id = request["task_id"]
    directory = task_dir(task_id)
    with file_lock(task_lock_path(task_id)):
        if directory.exists():
            request_path = directory / "request.json"
            if not request_path.exists() or not (directory / "state.json").exists():
                raise ValueError("task directory is incomplete")
            existing_request = json.loads(request_path.read_text(encoding="utf-8"))
            if existing_request != request:
                raise TaskRequestConflict("task id already exists with a different request")
            current = read_state_unlocked(task_id)
        else:
            now = int(time.time() * 1000)
            staging = pathlib.Path(tempfile.mkdtemp(prefix=".%s-" % task_id, dir=TASKS))
            try:
                atomic_json(staging / "request.json", request)
                atomic_json(staging / "state.json", {"id": task_id, "status": "queued", "created_at": now,
                    "updated_at": now, "remote_directory": str(directory), "artifact_paths": request.get("artifact_paths", [])})
                os.replace(staging, directory)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
            current = read_state_unlocked(task_id)
        return reconcile_state_unlocked(task_id, current)

def cancel_task(task_id):
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        status = current.get("status", "unknown")
        if status in TERMINAL_STATUSES:
            return current
        if status == "running":
            ownership_error = task_process_ownership_error(task_id, current)
            if ownership_error is not None:
                return transition_unlocked(
                    task_id, current, "failed", error=ownership_error,
                    finished_at=int(time.time() * 1000),
                )
            terminate_process_group_id(int(current["pid"]))
        return transition_unlocked(task_id, current, "cancelled", finished_at=int(time.time() * 1000))

def task_process_ownership_error(task_id, current):
    required = ("pid", "pid_start_time", "process_uid", "worker_pid", "worker_start_time", "worker_uid")
    if any(current.get(name) is None for name in required):
        return "Task process identity is unavailable; no signal was sent"
    if not worker_is_active(task_id):
        return "Task worker is no longer running; no signal was sent"
    try:
        worker_identity = linux_process_identity(current["worker_pid"])
        process_identity = linux_process_identity(current["pid"])
    except (OSError, ValueError, RuntimeError):
        return "Task process identity no longer exists; no signal was sent"
    expected_uid = os.geteuid()
    if (
        worker_identity["start_time"] != int(current["worker_start_time"])
        or worker_identity["uid"] != int(current["worker_uid"])
        or worker_identity["uid"] != expected_uid
    ):
        return "Task worker identity changed; no signal was sent"
    if (
        process_identity["start_time"] != int(current["pid_start_time"])
        or process_identity["uid"] != int(current["process_uid"])
        or process_identity["uid"] != expected_uid
        or process_identity["parent_pid"] != worker_identity["pid"]
        or process_identity["process_group_id"] != process_identity["pid"]
    ):
        return "Task process identity changed; no signal was sent"
    return None

def register_artifact(task_id, path_value):
    path = expanded(path_value)
    with file_lock(task_lock_path(task_id)):
        current = read_state_unlocked(task_id)
        current["artifact_paths"] = list(dict.fromkeys(current.get("artifact_paths", []) + [str(path)]))
        return write_state_unlocked(task_id, current)

def handle(request):
    op = request.get("op")
    if op == "probe": return {"version": VERSION, "home": str(pathlib.Path.home()), "python": sys.version.split()[0], "node": shutil.which("node") is not None}
    if op == "execute": return execute(request)
    if op == "submit": return submit(request)
    if op == "status": return state(request["task_id"])
    if op == "log":
        path = task_dir(request["task_id"]) / "output.log"
        limit = max(1, min(int(request.get("limit", 40000)), 200000))
        return {"output": read_tail(path, limit)}
    if op == "cancel": return cancel_task(request["task_id"])
    if op == "register_artifact": return register_artifact(request["task_id"], request["path"])
    if op == "resolve":
        return {"path": str(expanded(request["path"]))}
    if op == "list":
        path = expanded(request["path"])
        return {"path": str(path), "entries": [{"name": p.name, "path": str(p), "directory": p.is_dir(), "size": p.stat().st_size, "modified_at": int(p.stat().st_mtime * 1000)} for p in sorted(path.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower()))]}
    if op == "read":
        path = expanded(request["path"]); limit = min(int(request.get("limit", 1048576)), 1048576)
        data = path.read_bytes()
        if len(data) > limit: raise ValueError("file is too large to preview")
        return {"path": str(path), "content": data.decode("utf-8")}
    if op == "write":
        path = expanded(request["path"]); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(request.get("content", ""), encoding="utf-8"); return {"path": str(path), "size": path.stat().st_size}
    if op == "mkdir":
        path = expanded(request["path"]); path.mkdir(parents=bool(request.get("parents", False)), exist_ok=False); return {"path": str(path)}
    if op == "move":
        source = expanded(request["source"]); target = expanded(request["target"]); source.rename(target); return {"path": str(target)}
    if op == "delete":
        path = expanded(request["path"])
        shutil.rmtree(path) if path.is_dir() else path.unlink()
        return {"deleted": str(path)}
    raise ValueError("unknown operation")

def read_mcp_bootstrap():
    encoded = bytearray()
    while True:
        value = os.read(sys.stdin.fileno(), 1)
        if not value:
            raise ValueError("missing MCP bootstrap line")
        if value == b"\n":
            break
        encoded.extend(value)
        if len(encoded) > 1048576:
            raise ValueError("MCP bootstrap line is too large")
    config = json.loads(bytes(encoded).rstrip(b"\r").decode("utf-8"))
    if not isinstance(config, dict) or not isinstance(config.get("command"), str) or not config["command"]:
        raise ValueError("invalid MCP command")
    if not isinstance(config.get("arguments", []), list) or not isinstance(config.get("environment", {}), dict):
        raise ValueError("invalid MCP bootstrap")
    return config

def run_mcp_stdio():
    config = read_mcp_bootstrap()
    environment = os.environ.copy()
    environment.update({str(key): str(value) for key, value in config.get("environment", {}).items()})
    working_directory = expanded(config.get("working_directory") or "~")
    os.chdir(working_directory)
    command = config["command"]
    arguments = [str(value) for value in config.get("arguments", [])]
    os.execvpe(command, [command] + arguments, environment)

if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True); TASKS.mkdir(parents=True, exist_ok=True)
    if len(sys.argv) == 2 and sys.argv[1] == "_mcp_stdio":
        run_mcp_stdio()
    elif len(sys.argv) == 3 and sys.argv[1] == "_worker":
        try: worker(sys.argv[2])
        except Exception as error:
            try: fail_task(sys.argv[2], error)
            except Exception: pass
    else:
        try: emit({"ok": True, "result": handle(json.loads(sys.stdin.read()))})
        except Exception as error:
            code = "task_request_conflict" if isinstance(error, TaskRequestConflict) else "helper_error"
            emit({"ok": False, "error": str(error), "code": code})
