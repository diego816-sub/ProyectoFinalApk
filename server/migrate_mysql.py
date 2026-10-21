"""Copy a SQLite snapshot to an EMPTY MySQL database; never edit the source."""
import json
import os
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path
import database
import evidence_store

TABLES = ('users','clients','visits','routes','audit','reports','operations','mail_outbox','journeys')
ALL_TABLES = TABLES + ('sessions','email_codes','code_limits')


def copy_records(source, target):
    for table in ALL_TABLES:
        if target.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]:
            raise ValueError('El destino contiene datos. Utiliza una base exclusiva y vacía.')
    counts={}
    for table in TABLES:
        rows=source.execute(f'SELECT * FROM {table}')
        columns=[x[0] for x in rows.description]
        sql=f"INSERT INTO {table} ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})"
        count=0
        for row in rows:
            data=dict(row)
            if table=='reports':
                data['payload']=json.dumps(evidence_store.externalize(json.loads(data['payload']),data['visit_id']),ensure_ascii=False)
            target.execute(sql,tuple(data[k] for k in columns))
            count+=1
        if target.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]!=count:
            raise RuntimeError('La cantidad de registros no coincide; se cancela la transacción.')
        counts[table]=count
    return counts


def migrate(path, apply=False):
    if not path.is_file(): raise ValueError('No existe la base de origen.')
    if not database.is_mysql() or not evidence_store.enabled():
        raise ValueError('La migración requiere MySQL y Cloudinary configurados.')
    with tempfile.TemporaryDirectory() as folder:
        snapshot=Path(folder)/'snapshot.db'
        with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)) as src:
            with closing(sqlite3.connect(snapshot)) as dst: src.backup(dst)
        import server
        old=server.DB
        try:
            server.DB=snapshot
            os.environ['NEXOVISITA_DB_DRIVER']='sqlite'
            server.initialize()  # Upgrade only the temporary snapshot, never the original.
        finally:
            server.DB=old
            os.environ['NEXOVISITA_DB_DRIVER']='mysql'
        with closing(sqlite3.connect(snapshot)) as source:
            source.row_factory=sqlite3.Row
            print('Origen: '+', '.join(f'{t}={source.execute("SELECT COUNT(*) FROM "+t).fetchone()[0]}' for t in TABLES))
            print('Las sesiones y códigos no se copian. Las cuentas conservan usuario, contraseña, verificación y rol.')
            if not apply:
                print('Simulación: no se cambió MySQL ni Cloudinary. Detén el servidor original antes de usar --apply.')
                return
            database.initialize()
            with database.connect() as target:
                counts=copy_records(source,target)
            print('Migración confirmada: '+', '.join(f'{t}={n}' for t,n in counts.items()))
