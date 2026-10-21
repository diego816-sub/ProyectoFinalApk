"""NexoVisita: API académica, una empresa, SQLite/MySQL y sesiones revocables.

Ejecutar: python server.py init; python server.py serve --host 127.0.0.1
El servidor HTTP incluido es para demostración local, no para Internet público.
"""
import argparse
import os
import sys
import visit_features
import journeys
import datetime as dt
import getpass
import hashlib
import hmac
import json
import math
import re
import mailer
import secrets
import sqlite3
import database
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

DB = Path(os.environ['NEXOVISITA_DB']).expanduser().resolve() if os.environ.get('NEXOVISITA_DB') else Path(__file__).with_name('nexovisita.db')
STATES = {'pending', 'in_progress', 'completed', 'not_done'}


class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


@contextmanager
def connect():
    if database.is_mysql():
        with database.connect() as db:
            yield db
        return
    db = sqlite3.connect(DB, timeout=15)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    try:
        with db:
            yield db
    finally:
        db.close()


def initialize():
    if database.is_mysql():
        database.initialize()
        return
    with connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS users (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE NOT NULL,
          role TEXT NOT NULL CHECK(role IN ('coordinator','technician')),
          salt TEXT NOT NULL, password TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS sessions (
          digest TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS clients (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT NOT NULL,
          latitude REAL NOT NULL, longitude REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS visits (
          id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id),
          technician_id TEXT NOT NULL REFERENCES users(id), scheduled_date TEXT NOT NULL,
          task TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', notes TEXT NOT NULL DEFAULT '',
          arrival_at TEXT, arrival_lat REAL, arrival_lon REAL, arrival_accuracy REAL,
          finished_at TEXT, version INTEGER NOT NULL DEFAULT 1);
        CREATE TABLE IF NOT EXISTS routes (
          visit_id TEXT PRIMARY KEY REFERENCES visits(id), payload TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS audit (
          id INTEGER PRIMARY KEY, user_id TEXT NOT NULL, visit_id TEXT NOT NULL,
          action TEXT NOT NULL, at TEXT NOT NULL);
        ''')
        # Additive migration: old accounts and all visit/route identifiers are preserved.
        columns = {r['name'] for r in db.execute('PRAGMA table_info(users)')}
        for name, definition in [('username', 'TEXT'), ('email_verified', 'INTEGER NOT NULL DEFAULT 0'),
                                 ('active', 'INTEGER NOT NULL DEFAULT 1')]:
            if name not in columns:
                db.execute(f'ALTER TABLE users ADD COLUMN {name} {definition}')
        db.executescript('''
        CREATE UNIQUE INDEX IF NOT EXISTS unique_username ON users(username COLLATE NOCASE);
        CREATE TABLE IF NOT EXISTS email_codes (
          user_id TEXT NOT NULL REFERENCES users(id), purpose TEXT NOT NULL,
          salt TEXT NOT NULL, digest TEXT NOT NULL, expires REAL NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0, sent_at REAL NOT NULL,
          PRIMARY KEY(user_id,purpose));
        CREATE TABLE IF NOT EXISTS code_limits (
          user_id TEXT PRIMARY KEY REFERENCES users(id), started REAL NOT NULL, count INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS mail_outbox (
          id INTEGER PRIMARY KEY, visit_id TEXT NOT NULL UNIQUE REFERENCES visits(id),
          recipient TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
          next_try REAL NOT NULL DEFAULT 0);
        ''')

        visit_features.migrate(db)
        journeys.migrate(db)

def text(data, key, maximum=200, minimum=1):
    value = data.get(key)
    if not isinstance(value, str) or not minimum <= len(value.strip()) <= maximum:
        raise ApiError(400, f'Revisa el campo {key} (entre {minimum} y {maximum} caracteres).')
    return value.strip()


def number(data, key, low, high):
    value = data.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise ApiError(400, f'Valor inválido: {key}.')
    return value


def password_hash(password, salt):
    return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 600000).hex()


def create_user(db, data, role='technician'):
    name = text(data, 'name', 80)
    email = text(data, 'email', 160).lower()
    if not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", email):
        raise ApiError(400, 'Escribe un correo válido como identificador de acceso.')
    password = text(data, 'password', 128, 10)
    salt = secrets.token_hex(16)
    uid = secrets.token_hex(16)
    try:
        db.execute('INSERT INTO users (id,name,email,role,salt,password) VALUES (?,?,?,?,?,?)',
                   (uid, name, email, role, salt, password_hash(password, salt)))
    except sqlite3.IntegrityError:
        raise ApiError(409, 'Ese correo ya tiene una cuenta.')
    return dict(id=uid, name=name, email=email, role=role)


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')


def public_user(row):
    return {key: row[key] for key in ('id', 'name', 'email', 'role', 'username', 'email_verified', 'active')}


def authenticate(db, token):
    row = db.execute('''SELECT u.* FROM users u JOIN sessions s ON u.id=s.user_id
                         WHERE s.digest=? AND s.expires>? AND u.active=1''',
                     (hashlib.sha256(token.encode()).hexdigest(), time.time())).fetchone()
    if row is None:
        raise ApiError(401, 'La sesión expiró. Vuelve a ingresar.')
    return row


def coordinator(user):
    if user['role'] != 'coordinator':
        raise ApiError(403, 'Esta acción requiere una cuenta de coordinador.')


def get_visit(db, uid, user):
    row = db.execute('SELECT * FROM visits WHERE id=?', (uid,)).fetchone()
    if row is None:
        raise ApiError(404, 'La visita no existe.')
    if user['role'] != 'coordinator' and row['technician_id'] != user['id']:
        raise ApiError(403, 'La visita pertenece a otro técnico.')
    return row


def snapshot(db, user):
    clause = '' if user['role'] == 'coordinator' else ' WHERE v.technician_id=?'
    params = () if not clause else (user['id'],)
    visits = [dict(r) for r in db.execute('''SELECT v.*, c.name client_name, c.address,
        c.latitude, c.longitude, u.name technician_name,
        EXISTS(SELECT 1 FROM routes r WHERE r.visit_id=v.id) has_route,
        COALESCE((SELECT status FROM mail_outbox m WHERE m.visit_id=v.id), 'not_requested') mail_status
        FROM visits v JOIN clients c ON c.id=v.client_id JOIN users u ON u.id=v.technician_id'''
        + clause + ' ORDER BY v.scheduled_date DESC,v.id', params)]
    if user['role'] == 'coordinator':
        clients = [dict(r) for r in db.execute('SELECT * FROM clients ORDER BY name')]
        users = [public_user(r) for r in db.execute("SELECT * FROM users WHERE role='technician' ORDER BY name")]
    else:
        clients, users = [], []
    visit_features.enrich(db, visits)
    return dict(user=public_user(user), visits=visits, clients=clients, technicians=users, fetched_at=now())


def validate_route(data):
    number(data, 'distance_m', 0, 10000000)
    number(data, 'duration_ms', 0, 604800000)
    number(data, 'started_at', 1, 9999999999999)
    number(data, 'ended_at', data['started_at'], 9999999999999)
    points = data.get('points')
    if not isinstance(points, list) or not 2 <= len(points) <= 30000:
        raise ApiError(400, 'El recorrido debe contener entre 2 y 30000 puntos.')
    for p in points:
        if not isinstance(p, dict):
            raise ApiError(400, 'Punto inválido.')
        number(p, 'lat', -90, 90)
        number(p, 'lon', -180, 180)
        number(p, 'segment', 0, 100000)
    return {k: data[k] for k in ('distance_m', 'duration_ms', 'started_at', 'ended_at', 'points')}



def issue_code(db, user, purpose):
    stamp = time.time()
    previous = db.execute('SELECT sent_at FROM email_codes WHERE user_id=? AND purpose=?', (user['id'], purpose)).fetchone()
    limit = db.execute('SELECT * FROM code_limits WHERE user_id=?', (user['id'],)).fetchone()
    if previous and stamp - previous['sent_at'] < 60:
        raise ApiError(429, 'Espera 60 segundos antes de solicitar otro código.')
    if limit and stamp - limit['started'] < 3600 and limit['count'] >= 10:
        raise ApiError(429, 'Alcanzaste el límite de códigos. Inténtalo en una hora.')
    code = f'{secrets.randbelow(1000000):06d}'
    salt = secrets.token_hex(16)
    db.execute('INSERT OR REPLACE INTO email_codes VALUES (?,?,?,?,?,?,?)',
               (user['id'], purpose, salt, password_hash(code, salt), stamp + 600, 0, stamp))
    if limit and stamp - limit['started'] < 3600:
        db.execute('UPDATE code_limits SET count=count+1 WHERE user_id=?', (user['id'],))
    else:
        db.execute('INSERT OR REPLACE INTO code_limits VALUES (?,?,1)', (user['id'], stamp))
    task = 'activar / verificar tu cuenta' if purpose == 'activate' else 'recuperar tu contraseña'
    try:
        mailer.send(user['email'], 'NexoVisita: código de verificación',
                    f'Tu código para {task} es: {code}\n\nVence en 10 minutos y permite 5 intentos. '
                    'No compartas este código. Nunca te pediremos la contraseña de tu correo. '
                    'Si no solicitaste esta acción, puedes ignorar este mensaje.')
    except Exception:
        # Never report success or leave an unusable invitation when SMTP fails.
        raise ApiError(503, 'No se pudo enviar el código. El responsable debe revisar la configuración de correo y la conexión a Internet.') from None


def request_code(db, data, purpose):
    email = text(data, 'email', 160).lower()
    # Missing SMTP is a system error, independent of whether the email exists.
    try:
        mailer.settings()
    except RuntimeError:
        raise ApiError(503, 'El servidor todavía no tiene configurado el envío de correo.') from None
    db.execute('BEGIN IMMEDIATE')
    user = db.execute('SELECT * FROM users WHERE email=?', (email,)).fetchone()
    eligible = user and ((purpose == 'activate' and not user['email_verified']) or
                        (purpose == 'reset' and user['active'] and user['email_verified']))
    if eligible:
        issue_code(db, user, purpose)
    return {'message': 'Si el correo corresponde a una cuenta habilitada para este paso, recibirás un código. Revisa también spam. El código vence en 10 minutos.'}


def confirm_code(db, data, purpose):
    email = text(data, 'email', 160).lower()
    code = text(data, 'code', 6, 6)
    password = text(data, 'password', 128, 10)
    username = None
    if purpose == 'activate':
        username = text(data, 'username', 30, 3).lower()
        if not re.fullmatch(r'[a-z][a-z0-9_.]{2,29}', username):
            raise ApiError(400, 'Usuario: de 3 a 30 letras, números, punto o guion bajo; empieza con una letra.')
    db.execute('BEGIN IMMEDIATE')
    user = db.execute('SELECT * FROM users WHERE email=?', (email,)).fetchone()
    entry = db.execute('SELECT * FROM email_codes WHERE user_id=? AND purpose=?',
                       (user['id'], purpose)).fetchone() if user else None
    if not entry or entry['expires'] <= time.time() or entry['attempts'] >= 5:
        raise ApiError(400, 'Código inválido, vencido o agotado. Solicita uno nuevo.')
    if not hmac.compare_digest(entry['digest'], password_hash(code, entry['salt'])):
        db.execute('UPDATE email_codes SET attempts=attempts+1 WHERE user_id=? AND purpose=?', (user['id'], purpose))
        db.commit()  # Keep failed-attempt accounting even when the API raises an error.
        raise ApiError(400, 'Código incorrecto. Se permiten hasta 5 intentos por código.')
    if purpose == 'activate':
        if user['email_verified']:
            raise ApiError(409, 'Esta cuenta ya está verificada. Usa Recuperar contraseña.')
        if db.execute('SELECT id FROM users WHERE username=? COLLATE NOCASE AND id<>?', (username, user['id'])).fetchone():
            raise ApiError(409, 'Ese usuario ya está ocupado. Elige otro.')
        db.execute('UPDATE users SET username=?, email_verified=1, active=1 WHERE id=?', (username, user['id']))
    elif not user['email_verified'] or not user['active']:
        raise ApiError(400, 'Primero activa y verifica tu cuenta.')
    salt = secrets.token_hex(16)
    db.execute('UPDATE users SET salt=?,password=? WHERE id=?', (salt, password_hash(password, salt), user['id']))
    db.execute('DELETE FROM sessions WHERE user_id=?', (user['id'],))
    db.execute('DELETE FROM email_codes WHERE user_id=?', (user['id'],))
    return {'message': 'Cambio guardado. Inicia sesión con tu usuario y nueva contraseña.', 'username': username or user['username'] or user['email']}


def deliver_one_mail():
    # One worker per server. Durable queue: assignment stays saved if SMTP is unavailable.
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute("SELECT * FROM mail_outbox WHERE status='pending' AND next_try<=? ORDER BY id LIMIT 1", (time.time(),)).fetchone()
        if row is None:
            return False
        try:
            mailer.send(row['recipient'], row['subject'], row['body'])
            status = 'sent'
        except Exception:
            status = 'failed' if row['attempts'] + 1 >= 5 else 'pending'
        db.execute('UPDATE mail_outbox SET status=?,attempts=attempts+1,next_try=? WHERE id=?',
                   (status, time.time() + 60 * (2 ** row['attempts']), row['id']))
    return True


def mail_worker():
    while True:
        try:
            deliver_one_mail()
        except Exception:
            pass  # No payload, password or SMTP error is printed.
        time.sleep(5)


def assignment_message(db, uid):
    v = db.execute('SELECT v.*, c.name AS client_name, c.address, c.latitude, c.longitude FROM visits v JOIN clients c ON c.id=v.client_id WHERE v.id=?', (uid,)).fetchone()
    return (f"Visita asignada: {v['client_name']}\n"
            f"Fecha: {v['scheduled_date']} {v['scheduled_time']}\n"
            f"Dirección: {v['address']}\nTrabajo: {v['task']}\n"
            f"Cómo llegar: https://www.google.com/maps/dir/?api=1&destination={v['latitude']},{v['longitude']}\n"
            "Abre NexoVisita y pulsa Actualizar. Desde la visita puedes compartir tu traslado y adjuntar evidencias.")


def dispatch(method, path, data, token):
    # Liveness must not wait behind photo uploads or email delivery transactions.
    if method == 'GET' and path == '/health':
        return {'app': 'NexoVisita', 'api': 4, 'version': '1.10'}
    with connect() as db:
        if method == 'POST' and path in ('/activate/request', '/password/request'):
            return request_code(db, data, 'activate' if path.startswith('/activate') else 'reset')
        if method == 'POST' and path in ('/activate/confirm', '/password/confirm'):
            return confirm_code(db, data, 'activate' if path.startswith('/activate') else 'reset')
        if method == 'POST' and path == '/login':
            identifier = text(data, 'identifier' if 'identifier' in data else 'email', 160).lower()
            password = text(data, 'password', 128)
            user = db.execute('SELECT * FROM users WHERE username=? COLLATE NOCASE OR (username IS NULL AND email=?)', (identifier, identifier)).fetchone()
            salt = user['salt'] if user else '00' * 16
            candidate = password_hash(password, salt)
            if not user or not user['active'] or not hmac.compare_digest(user['password'], candidate):
                raise ApiError(401, 'Usuario o contraseña incorrectos. Si recibiste una invitación, activa primero tu cuenta.')
            session = secrets.token_urlsafe(32)
            db.execute('DELETE FROM sessions WHERE expires<?', (time.time(),))
            db.execute('INSERT INTO sessions VALUES (?,?,?)',
                       (hashlib.sha256(session.encode()).hexdigest(), user['id'], time.time() + 86400))
            return dict(token=session, user=public_user(user))
        user = authenticate(db, token)
        journey = journeys.dispatch(sys.modules[__name__], db, user, method, path, data)
        if journey is not None:
            return journey
        extension = visit_features.dispatch(sys.modules[__name__], db, user, method, path, data)
        if extension is not None:
            return extension
        if method == 'POST' and path == '/logout':
            db.execute('DELETE FROM sessions WHERE digest=?', (hashlib.sha256(token.encode()).hexdigest(),))
            return {'ok': True}
        if method == 'GET' and path == '/snapshot':
            return snapshot(db, user)
        if method == 'POST' and path == '/technicians':
            coordinator(user)
            # Caller cannot choose role, password or verified state for an invitation.
            db.execute('BEGIN IMMEDIATE')
            created = create_user(db, dict(name=data.get('name'), email=data.get('email'), password=secrets.token_urlsafe(32)))
            db.execute('UPDATE users SET active=0 WHERE id=?', (created['id'],))
            user_row = db.execute('SELECT * FROM users WHERE id=?', (created['id'],)).fetchone()
            issue_code(db, user_row, 'activate')
            return dict(public_user(user_row), message='Invitación aceptada por el servidor de correo. El técnico debe revisar su bandeja y spam.')
        if method == 'POST' and path == '/mail/retry':
            coordinator(user)
            uid = text(data, 'visit_id', 32)
            row = db.execute('SELECT status FROM mail_outbox WHERE visit_id=?', (uid,)).fetchone()
            if not row or row['status'] != 'failed':
                raise ApiError(409, 'Solo se reintentan avisos que agotaron sus intentos.')
            db.execute("UPDATE mail_outbox SET status='pending',attempts=0,next_try=0 WHERE visit_id=?", (uid,))
            return {'ok': True}
        if method == 'POST' and path == '/clients':
            coordinator(user)
            uid = secrets.token_hex(16)
            db.execute('INSERT INTO clients VALUES (?,?,?,?,?)', (uid, text(data, 'name', 100),
                text(data, 'address', 240), number(data, 'latitude', -90, 90), number(data, 'longitude', -180, 180)))
            return {'id': uid}
        if method == 'POST' and path == '/visits':
            coordinator(user)
            db.execute('BEGIN IMMEDIATE')
            cid, tid = text(data, 'client_id', 32), text(data, 'technician_id', 32)
            if not db.execute('SELECT id FROM clients WHERE id=?', (cid,)).fetchone():
                raise ApiError(400, 'Selecciona un cliente existente.')
            if not db.execute("SELECT id FROM users WHERE id=? AND role='technician' AND active=1", (tid,)).fetchone():
                raise ApiError(400, 'Selecciona un técnico existente.')
            date = text(data, 'scheduled_date', 10)
            try:
                if dt.date.fromisoformat(date).isoformat() != date:
                    raise ValueError()
            except ValueError:
                raise ApiError(400, 'Fecha inválida. Usa AAAA-MM-DD.')
            values = visit_features.schedule(sys.modules[__name__], data)
            visit_features.check_conflicts(sys.modules[__name__], db, tid, values)
            uid = secrets.token_hex(16)
            db.execute('INSERT INTO visits (id,client_id,technician_id,scheduled_date,task) VALUES (?,?,?,?,?)',
                       (uid, cid, tid, date, text(data, 'task', 1000)))
            db.execute('UPDATE visits SET scheduled_time=?,duration_minutes=?,priority=? WHERE id=?', (*values[1:], uid))
            db.execute('INSERT INTO audit (user_id,visit_id,action,at) VALUES (?,?,?,?)', (user['id'], uid, 'created', now()))
            recipient = db.execute('SELECT * FROM users WHERE id=?', (tid,)).fetchone()
            if recipient['email_verified']:
                db.execute('INSERT INTO mail_outbox (visit_id,recipient,subject,body) VALUES (?,?,?,?)',
                    (uid, recipient['email'], 'NexoVisita: nueva visita asignada',
                     assignment_message(db, uid)))
            return {'id': uid, 'mail_status': 'pending' if recipient['email_verified'] else 'not_requested'}
        parts = path.strip('/').split('/')
        if len(parts) == 3 and parts[0] == 'visits':
            visit = get_visit(db, parts[1], user)
            action = parts[2]
            if method == 'GET' and action == 'route':
                row = db.execute('SELECT payload FROM routes WHERE visit_id=?', (visit['id'],)).fetchone()
                if row is None:
                    raise ApiError(404, 'Todavía no se adjuntó un recorrido.')
                return json.loads(row['payload'])
            if method != 'POST':
                raise ApiError(405, 'Método no permitido.')
            if user['role'] != 'technician' or visit['technician_id'] != user['id']:
                raise ApiError(403, 'Solo el técnico asignado puede registrar la atención.')
            # Lock before reading the version again: concurrent updates cannot overwrite silently.
            db.execute('BEGIN IMMEDIATE')
            visit = get_visit(db, parts[1], user)
            if data.get('version') != visit['version']:
                raise ApiError(409, 'La visita cambió. Actualiza la lista y vuelve a intentarlo.')
            if action == 'arrive':
                if visit['status'] != 'pending':
                    raise ApiError(409, 'La visita ya fue iniciada o cerrada.')
                lat = number(data, 'latitude', -90, 90)
                lon = number(data, 'longitude', -180, 180)
                accuracy = number(data, 'accuracy', 0.01, 100)
                db.execute("""UPDATE visits SET status='in_progress',arrival_at=?,arrival_lat=?,arrival_lon=?,
                    arrival_accuracy=?,version=version+1 WHERE id=?""", (now(), lat, lon, accuracy, visit['id']))
            elif action == 'finish':
                status = data.get('status')
                if visit['status'] not in ('pending', 'in_progress') or status not in ('completed', 'not_done'):
                    raise ApiError(409, 'La visita ya fue cerrada o el resultado es inválido.')
                if status == 'completed' and visit['status'] != 'in_progress':
                    raise ApiError(409, 'Registra tu llegada antes de completar la visita.')
                notes = text(data, 'notes', 2000, 5)
                db.execute('UPDATE visits SET status=?,notes=?,finished_at=?,version=version+1 WHERE id=?',
                           (status, notes, now(), visit['id']))
            elif action == 'route':
                route = validate_route(data)
                db.execute('INSERT INTO routes VALUES (?,?) ON CONFLICT(visit_id) DO UPDATE SET payload=excluded.payload',
                           (visit['id'], json.dumps(route, allow_nan=False)))
                db.execute('UPDATE visits SET version=version+1 WHERE id=?', (visit['id'],))
            else:
                raise ApiError(404, 'Acción no disponible.')
            db.execute('INSERT INTO audit (user_id,visit_id,action,at) VALUES (?,?,?,?)',
                       (user['id'], visit['id'], action, now()))
            return {'ok': True}
        raise ApiError(404, 'Recurso no disponible.')


class Handler(BaseHTTPRequestHandler):
    attempts = {}
    attempts_lock = threading.Lock()

    def log_message(self, format, *args):
        # Never log passwords, tokens, coordinates or request bodies.
        pass

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def handle_api(self):
        self.connection.settimeout(20)
        try:
            path = urlsplit(self.path).path
            if path in ('/login', '/activate/request', '/activate/confirm', '/password/request', '/password/confirm'):
                with self.attempts_lock:
                    stamp = time.time()
                    self.attempts = {ip: [t for t in ts if stamp-t < 60] for ip, ts in self.attempts.items() if ts and stamp-ts[-1] < 60}
                    hits = self.attempts.setdefault(self.client_address[0], [])
                    if len(hits) >= 10:
                        raise ApiError(429, 'Demasiados intentos. Espera un minuto.')
                    hits.append(stamp)
                    Handler.attempts = self.attempts
            data = {}
            if self.command == 'POST':
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                except ValueError:
                    raise ApiError(400, 'Longitud inválida.')
                if not 0 < length <= 4000000:
                    raise ApiError(413, 'La solicitud está vacía o es demasiado grande.')
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ApiError(400, 'Se esperaba un objeto JSON.')
            auth = self.headers.get('Authorization', '')
            token = auth.removeprefix('Bearer ') if auth.startswith('Bearer ') else ''
            result = dispatch(self.command, path, data, token)
            self.reply(200, result)
        except ApiError as e:
            self.reply(e.status, {'error': e.message})
        except (ValueError, UnicodeError):
            self.reply(400, {'error': 'JSON o datos inválidos.'})
        except Exception:
            self.reply(500, {'error': 'No se pudo procesar la solicitud. Vuelve a intentarlo.'})

    def reply(self, status, data):
        payload = json.dumps(data, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(payload)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Servidor académico NexoVisita')
    parser.add_argument('command', choices=['init', 'serve'])
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    initialize()
    if args.command == 'init':
        with connect() as db:
            if db.execute("SELECT id FROM users WHERE role='coordinator'").fetchone():
                raise SystemExit('Ya existe un coordinador. La base de datos se conserva.')
            data = dict(name=input('Nombre del coordinador: '), email=input('Correo de acceso: '),
                        password=getpass.getpass('Contraseña (mínimo 10 caracteres): '))
            try:
                create_user(db, data, 'coordinator')
            except ApiError as e:
                raise SystemExit(e.message)
        print('Coordinador creado. Ingresa con tu correo y contraseña. Usa Activar / verificar cuenta para verificar el correo y elegir un usuario.')
    else:
        print(f'NexoVisita disponible en http://{args.host}:{args.port}. Uso académico en red local.')
        try:
            mailer.settings()
            print('Correo configurado. El envío real depende de Gmail y de la conexión a Internet.')
        except RuntimeError as e:
            print(f'Correo no configurado: {e}')
        threading.Thread(target=mail_worker, daemon=True).start()
        ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
