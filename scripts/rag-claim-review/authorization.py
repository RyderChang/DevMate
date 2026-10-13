"""Independent one-use authorization ledger. Only explicit rehearsal grants work.

SQLite FULL transactions durably reserve UNKNOWN before dispatch. OS locking is
held for the round, including validation/review. There is no resume/top-up API.
This is local single-host governance, not a distributed authorization service.
"""
import json
import os
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path

from adapter import local_id
from execution import PREPARATION_SHA, frozen_preparation, require_live_ready
from plan import canonical
from review import ReviewError, digest, require, safe_receipt


@dataclass(frozen=True, repr=False)
class NewRoundAuthorization:
    authorization_id: str
    round_id: str
    scope_sha: str
    owner_plan_confirmation_sha: str
    owner_environment_confirmation_sha: str

    def validate(self):
        # Even a constructed confirmation object cannot authorize this blocked plan.
        require_live_ready()


@dataclass(frozen=True, repr=False)
class RehearsalAuthorization:
    authorization_id: str
    round_id: str
    scope_sha: str = PREPARATION_SHA
    mode: str = 'OFFLINE_LEDGER_STUB'

    def validate(self):
        local_id(self.authorization_id); local_id(self.round_id)
        require(self.mode == 'OFFLINE_LEDGER_STUB' and self.scope_sha == PREPARATION_SHA,
                'AUTHORIZATION_REJECTED')
        return frozen_preparation()


def sync_directory(directory):
    # Windows SQLite uses FlushFileBuffers for FULL commit; POSIX also syncs the
    # directory entry after first creation. Never claim physical hardware proof.
    if os.name != 'nt':
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)


class RoundLedger:
    def __init__(self, grant, directory):
        self.db = self.lock = None
        self.closed = self.stopped = False
        self.busy = threading.Lock()
        self.active = None
        if type(grant) is NewRoundAuthorization:
            grant.validate()
        require(type(grant) is RehearsalAuthorization, 'AUTHORIZATION_REJECTED')
        self.plan = grant.validate()
        self.grant = grant
        self.fingerprint = digest(canonical(vars(grant)))
        self.directory = Path(directory).resolve()
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            lock_path = self.directory / 'authorization.lock'
            db_path = self.directory / 'authorization.sqlite3'
            require(not lock_path.is_symlink() and not db_path.is_symlink(), 'LEDGER_UNAVAILABLE')
            self.lock = lock_path.open('a+b')
            if os.name == 'nt':
                import msvcrt
                if self.lock.seek(0, 2) == 0:
                    self.lock.write(b'0'); self.lock.flush(); os.fsync(self.lock.fileno())
                self.lock.seek(0)
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.db = sqlite3.connect(db_path, timeout=0, isolation_level=None)
            self.db.execute('PRAGMA journal_mode=DELETE')
            self.db.execute('PRAGMA synchronous=FULL')
            require(self.db.execute('PRAGMA synchronous').fetchone()[0] == 2, 'LEDGER_UNAVAILABLE')
            require(self.db.execute('PRAGMA integrity_check').fetchone() == ('ok',), 'LEDGER_UNAVAILABLE')
            self.db.executescript('''
                CREATE TABLE IF NOT EXISTS rounds (
                    authorization_id TEXT PRIMARY KEY, round_id TEXT UNIQUE NOT NULL,
                    grant_sha TEXT NOT NULL, scope_sha TEXT NOT NULL,
                    mode TEXT NOT NULL, approved_calls INTEGER NOT NULL,
                    rehearsal_cap INTEGER NOT NULL, stopped INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS attempts (
                    attempt_id TEXT PRIMARY KEY, round_id TEXT NOT NULL,
                    question INTEGER NOT NULL, arm TEXT NOT NULL, payload_sha TEXT NOT NULL,
                    sample_id TEXT UNIQUE NOT NULL, receipt TEXT NOT NULL, code TEXT NOT NULL,
                    review_status TEXT NOT NULL,
                    UNIQUE(round_id, question, arm));
            ''')
            self.db.execute('BEGIN IMMEDIATE')
            # Full frozen sample list and conservative reservation total are used
            # ONLY as simulated cap. Never replace the real ¥2 budget in the plan.
            self.db.execute('INSERT INTO rounds VALUES (?,?,?,?,?,?,?,0)',
                            (grant.authorization_id, grant.round_id, self.fingerprint, grant.scope_sha,
                             grant.mode, self.plan['chatCalls'], self.plan['totalReservedMicros']))
            self._commit()
            sync_directory(self.directory)
        except BaseException:
            self.close()
            raise ReviewError('LEDGER_UNAVAILABLE') from None

    def _commit(self):
        self.db.execute('COMMIT')

    def _check(self):
        require(not self.closed and not self.stopped and self.grant.validate() == self.plan
                and digest(canonical(vars(self.grant))) == self.fingerprint, 'ROUND_STOPPED')

    def reserve(self, request):
        from https_driver import validate_request
        self._check()
        require(self.active is None, 'ROUND_STOPPED')
        row = validate_request(request)
        attempt_id, sample_id = uuid.uuid4().hex, uuid.uuid4().hex
        receipt = dict(status='UNKNOWN', usage=None, reservedMicros=row['reservedMicros'])
        try:
            self.db.execute('BEGIN IMMEDIATE')
            count, total = self.db.execute(
                'SELECT COUNT(*), COALESCE(SUM(json_extract(receipt, "$.reservedMicros")),0) '
                'FROM attempts WHERE round_id=?', (self.grant.round_id,)).fetchone()
            require(count < self.plan['chatCalls']
                    and total + row['reservedMicros'] <= self.plan['totalReservedMicros'], 'QUOTA_EXHAUSTED')
            self.db.execute('INSERT INTO attempts VALUES (?,?,?,?,?,?,?,?,?)',
                            (attempt_id, self.grant.round_id, request.question, request.arm, request.payload_sha,
                             sample_id, canonical(receipt).decode(), 'HTTP_RESULT_UNKNOWN', 'UNKNOWN'))
            self._commit()
            # Dispatch capability is returned only after the durable transaction.
            self.active = (attempt_id, request, digest(request.body), row)
            return attempt_id
        except BaseException:
            self.stop()
            raise ReviewError('RESERVATION_FAILED') from None

    def paired(self, attempt_id, request):
        self._check()
        require(self.active is not None and self.active[0] == attempt_id
                and self.active[1] is request and self.active[2] == digest(request.body), 'HTTP_PAIRING')
        from https_driver import validate_request
        require(validate_request(request) == self.active[3], 'HTTP_PAIRING')

    def record(self, attempt_id, request, receipt, code, review_status='UNKNOWN'):
        self.paired(attempt_id, request)
        receipt = safe_receipt(receipt)
        from governed import CODES
        require(code in CODES and review_status == 'UNKNOWN'
                and receipt['reservedMicros'] == self.active[3]['reservedMicros'], 'LEDGER_RECORD')
        try:
            self.db.execute('BEGIN IMMEDIATE')
            self.db.execute('UPDATE attempts SET receipt=?, code=?, review_status=? WHERE attempt_id=?',
                            (canonical(receipt).decode(), code, review_status, attempt_id))
            self._commit()
        except BaseException:
            self.stop()
            raise ReviewError('SAVE_FAILED') from None

    def stop(self):
        self.stopped = True
        if self.db is not None:
            try:
                if self.db.in_transaction: self.db.execute('ROLLBACK')
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE rounds SET stopped=1 WHERE round_id=?', (self.grant.round_id,))
                self._commit()
            except BaseException:
                pass  # Earlier FULL UNKNOWN reservation persists; never send again.

    def close(self):
        self.stopped = self.closed = True
        self.active = None
        if self.db is not None:
            try: self.db.close()
            except BaseException: pass
            self.db = None
        if self.lock is not None:
            # Closing the handle releases either platform's lock, never unlink it.
            self.lock.close()
            self.lock = None

    def __enter__(self): return self

    def __exit__(self, *args): self.close()
