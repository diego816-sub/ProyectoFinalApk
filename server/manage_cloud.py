"""Local operator commands; credentials are read from a private JSON, never logged."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys


def load_config(path):
    data = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    if not isinstance(data, dict) or any(not isinstance(v, str) for v in data.values()):
        raise ValueError('La configuración debe contener nombres y valores de texto.')
    allowed = {'NEXOVISITA_DB_DRIVER','NEXOVISITA_MEDIA','NEXOVISITA_MAIL',
        'MYSQL_HOST','MYSQL_PORT','MYSQL_USER','MYSQL_PASSWORD','MYSQL_DATABASE','MYSQL_SSL_CA',
        'CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET','CLOUDINARY_PREFIX',
        'GMAIL_CLIENT_ID','GMAIL_CLIENT_SECRET','GMAIL_REFRESH_TOKEN','GMAIL_FROM'}
    if set(data)-allowed: raise ValueError('Variable no reconocida en cloud_config.json.')
    os.environ.update(data)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--config', default='cloud_config.json')
    p.add_argument('command', choices=['check','init','migrate','mail-test'])
    p.add_argument('--source', help='Ruta de la base SQLite original; no se modifica')
    p.add_argument('--apply', action='store_true')
    p.add_argument('--recipient')
    a=p.parse_args()
    load_config(a.config)
    import database, evidence_store, mailer
    if not database.is_mysql(): raise ValueError('Este asistente requiere MySQL.')
    if a.command=='check':
        with database.connect() as db:
            db.execute('SELECT 1').fetchone()
        print('MySQL: conexión TLS y bloqueo de transacciones correctos.')
        evidence_store.configure()
        import cloudinary.api
        cloudinary.api.ping()
        print('Cloudinary: credenciales aceptadas. Falta probar subida y descarga desde el celular.')
        mailer.settings()
        print('Correo: variables presentes. Ejecuta mail-test para comprobar el envío real.')
    elif a.command=='init':
        sys.argv=['server.py','init']
        runpy.run_path(str(Path(__file__).with_name('server.py')),run_name='__main__')
    elif a.command=='migrate':
        if not a.source: p.error('migrate requiere --source')
        from migrate_mysql import migrate
        migrate(Path(a.source), a.apply)
    else:
        if not a.recipient: p.error('mail-test requiere --recipient')
        mailer.send(a.recipient,'NexoVisita: prueba de correo','El servidor pudo enviar este mensaje mediante Gmail API.')
        print('Gmail aceptó el mensaje. Comprueba la bandeja de entrada y spam.')


if __name__=='__main__':
    try: main()
    except Exception as e:
        # Provider exceptions can include signed URLs, connection strings or tokens.
        print('Operación detenida ('+type(e).__name__+'). Revisa los campos, certificado y permisos. No publiques claves ni archivos privados.')
        sys.exit(1)
