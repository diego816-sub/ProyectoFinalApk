"""MySQL adapter for the existing API. SQLite remains available for local use.

One database-scoped advisory lock serializes API transactions for this small
academic deployment, preserving SQLite's write/conflict semantics across workers.
It deliberately favors correctness over throughput. Never silently falls back.
"""
import os
import re
import ssl
import sqlite3
import hashlib
from pathlib import Path
from contextlib import contextmanager


def is_mysql():
    driver = os.environ.get('NEXOVISITA_DB_DRIVER', 'sqlite')
    if driver not in ('sqlite', 'mysql'):
        raise RuntimeError('NEXOVISITA_DB_DRIVER debe ser sqlite o mysql.')
    return driver == 'mysql'


class Row(dict):
    def __getitem__(self, key):
        return list(self.values())[key] if isinstance(key, int) else super().__getitem__(key)


class Result:
    def __init__(self, cursor):
        self.rowcount = cursor.rowcount
        self.lastrowid = cursor.lastrowid
        self.rows = iter([Row(r) for r in cursor.fetchall()] if cursor.description else [])
    def fetchone(self): return next(self.rows, None)
    def fetchall(self): return list(self.rows)
    def __iter__(self): return self.rows


def translate(sql):
    sql = sql.replace(' COLLATE NOCASE', '')
    sql = sql.replace('INSERT OR REPLACE INTO', 'REPLACE INTO')
    sql = re.sub(r'ON CONFLICT\(\w+\) DO UPDATE SET', 'ON DUPLICATE KEY UPDATE', sql)
    sql = re.sub(r'excluded\.(\w+)', r'VALUES(\1)', sql)
    # All SQL is repository-owned; parameters remain bound, never interpolated.
    # Preserve literal question marks and escape SQL percent for PyMySQL binding.
    sql = sql.replace('%', '%%')
    parts = re.split("('(?:''|[^'])*')", sql)
    return ''.join(p.replace('?', '%s') if i % 2 == 0 else p for i, p in enumerate(parts))


class Connection:
    def __init__(self, raw): self.raw = raw
    def execute(self, sql, params=()):
        import pymysql
        if sql.strip().upper() == 'BEGIN IMMEDIATE':
            # Lock held from connection entry, including authorization reads.
            sql = 'SELECT 1'
        try:
            with self.raw.cursor() as c:
                c.execute(translate(sql), params)
                return Result(c)
        except pymysql.IntegrityError as e:
            raise sqlite3.IntegrityError('Restricción de integridad MySQL.') from e
    def commit(self): self.raw.commit()
    def rollback(self): self.raw.rollback()


@contextmanager
def connect():
    import pymysql
    required = ('MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_SSL_CA')
    if any(not os.environ.get(k) for k in required):
        raise RuntimeError('Faltan variables MySQL o el certificado CA de Aiven.')
    context = ssl.create_default_context(cafile=os.environ['MYSQL_SSL_CA'])
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    raw = pymysql.connect(host=os.environ['MYSQL_HOST'], port=int(os.environ['MYSQL_PORT']),
        user=os.environ['MYSQL_USER'], password=os.environ['MYSQL_PASSWORD'],
        database=os.environ['MYSQL_DATABASE'], ssl=context, charset='utf8mb4',
        cursorclass=pymysql.cursors.DictCursor, autocommit=False,
        connect_timeout=10, read_timeout=90, write_timeout=30)
    name = 'nexo_' + hashlib.sha256(os.environ['MYSQL_DATABASE'].encode()).hexdigest()[:40]
    try:
        with raw.cursor() as c:
            c.execute('SELECT GET_LOCK(%s, 60) AS acquired', (name,))
            if c.fetchone()['acquired'] != 1:
                raise RuntimeError('Servidor ocupado. Reintenta la operación.')
        raw.begin()
        yield Connection(raw)
        raw.commit()
    except BaseException:
        raw.rollback()
        raise
    finally:
        # Closing the session also releases its advisory lock, even on failure.
        raw.close()


def initialize():
    with connect() as db:
        for statement in Path(__file__).with_name('schema_mysql.sql').read_text().split(';'):
            if statement.strip(): db.execute(statement)
