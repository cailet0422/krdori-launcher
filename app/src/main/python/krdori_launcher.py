"""Runs the krdori-v2 servers inside the Android app process.

Upstream main.py starts the Kakao and game servers with multiprocessing,
which is not available in an embedded interpreter, so each server runs in
its own daemon thread here instead. The servers are stopped by killing the
whole service process.
"""

import asyncio
import logging
import os
import re
import sys
import threading
import traceback

KAKAO_PORT = 8480
SESSION_PORT = 8481
GAME_PORT = 8482

# Truncate the log file once it grows past this size.
_MAX_LOG_BYTES = 4 * 1024 * 1024
# Werkzeug colors its banner; the in-app log viewer shows plain text.
_ANSI_ESCAPE = re.compile(r'\x1b\[[0-9;]*m')

_lock = threading.Lock()
_started = False
_errors = []


class _Tee:
    """Mirrors a stream (logcat under Chaquopy) into the log file."""

    def __init__(self, stream, log):
        self._stream = stream
        self._log = log

    def write(self, text):
        try:
            self._stream.write(text)
        except Exception:
            pass
        with _lock:
            try:
                if self._log.tell() > _MAX_LOG_BYTES:
                    self._log.seek(0)
                    self._log.truncate()
                self._log.write(_ANSI_ESCAPE.sub('', text))
                self._log.flush()
            except Exception:
                pass
        return len(text)

    def flush(self):
        try:
            self._stream.flush()
        except Exception:
            pass

    def isatty(self):
        return False

    def __getattr__(self, name):
        return getattr(self._stream, name)


def _setup_logging(log_path):
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    log = open(log_path, 'a', encoding='utf-8', errors='replace')
    sys.stdout = _Tee(sys.stdout, log)
    sys.stderr = _Tee(sys.stderr, log)


def _spawn(name, target, *args):
    def run():
        try:
            target(*args)
            raise RuntimeError('server exited unexpectedly')
        except BaseException as e:
            traceback.print_exc()
            with _lock:
                _errors.append(f'{name}: {e!r}')

    thread = threading.Thread(target=run, name=name, daemon=True)
    thread.start()
    return thread


def _run_session_server(session_server, port):
    asyncio.run(session_server.main(port))


def _preload(game_server):
    """Parse the 90 MB master JSON up front so the first login is fast."""
    load = getattr(game_server, '_load_suite_master', None)
    if load is None:
        return
    try:
        print('Preloading suite master...', flush=True)
        load()
        print('Suite master loaded.', flush=True)
    except Exception:
        traceback.print_exc()


def start(server_root, db_path, log_path):
    """Starts all three servers. Safe to call more than once."""
    global _started
    with _lock:
        if _started:
            return
        _started = True

    _setup_logging(log_path)
    print(f'--- krdori launcher: starting servers from {server_root}', flush=True)

    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    os.environ['KRDORI_DB_PATH'] = db_path
    if server_root not in sys.path:
        sys.path.insert(0, server_root)
    # Upstream code opens its data files with paths relative to the repo root.
    os.chdir(server_root)

    from server.servers import game_server, kakao_server, session_server

    # session_server enables DEBUG logging globally, which logs every frame.
    logging.getLogger('websockets').setLevel(logging.INFO)

    _spawn('kakao', kakao_server.start, KAKAO_PORT)
    _spawn('session', _run_session_server, session_server, SESSION_PORT)
    _spawn('game', game_server.start, GAME_PORT)
    threading.Thread(
        target=_preload, args=(game_server,), name='preload', daemon=True
    ).start()


def errors():
    """Returns the errors of servers that have stopped, joined by newlines."""
    with _lock:
        return '\n'.join(_errors)
