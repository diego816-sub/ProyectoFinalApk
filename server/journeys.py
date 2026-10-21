"""Explicit visit-scoped sharing. GPS is supporting evidence, not proof of compliance."""
import json
import secrets
import time


def migrate(db):
    db.execute('''CREATE TABLE IF NOT EXISTS journeys (
        visit_id TEXT PRIMARY KEY REFERENCES visits(id), payload TEXT NOT NULL)''')


def dispatch(s, db, user, method, path, data):
    parts = path.strip('/').split('/')
    if len(parts) != 3 or parts[0] != 'visits' or parts[2] != 'journey':
        return None
    if method not in ('GET', 'POST'):
        raise s.ApiError(405, 'Método no permitido.')
    if method == 'POST':
        db.execute('BEGIN IMMEDIATE')
    visit = s.get_visit(db, parts[1], user)
    row = db.execute('SELECT payload FROM journeys WHERE visit_id=?', (visit['id'],)).fetchone()
    saved = json.loads(row['payload']) if row else None
    stamp = time.time()
    if method == 'GET':
        if saved is None:
            return dict(state='not_started', points=[], active=False)
        age = max(0, stamp - saved['received_at'])
        last_age = max(0, stamp - saved.get('last_fix_at', 0) / 1000)
        active = saved['active'] and visit['status'] == 'pending'
        return dict(saved, active=active, age_seconds=round(age),
                    state=('stopped' if not active else 'stale' if max(age, last_age) > 90 else 'live'))
    if user['role'] != 'technician' or visit['technician_id'] != user['id']:
        raise s.ApiError(403, 'Solo el técnico asignado puede compartir su traslado.')
    command = data.get('command')
    if command == 'start':
        if visit['status'] != 'pending':
            raise s.ApiError(409, 'El traslado solo se comparte antes de registrar la llegada.')
        if saved and saved['active'] and stamp - saved['received_at'] < 120:
            raise s.ApiError(409, 'Ya hay un traslado activo. Detén el anterior o espera dos minutos si se perdió la conexión.')
        saved = dict(run_id=secrets.token_hex(16), sequence=0, active=True, points=[],
                     distance_m=0, duration_ms=0, last_fix_at=0, accuracy=None, received_at=stamp)
        db.execute('INSERT INTO audit(user_id,visit_id,action,at) VALUES(?,?,?,?)',
                   (user['id'], visit['id'], 'journey_started', s.now()))
    else:
        if not saved or data.get('run_id') != saved['run_id']:
            raise s.ApiError(409, 'Este traslado ya no es el activo.')
        sequence = data.get('sequence')
        if type(sequence) is not int or sequence < 1:
            raise s.ApiError(400, 'Secuencia inválida.')
        if sequence <= saved['sequence']:
            return dict(ok=True, duplicate=True)
        if not saved['active']:
            raise s.ApiError(409, 'El traslado ya terminó.')
        if command not in ('update', 'stop'):
            raise s.ApiError(400, 'Acción de traslado inválida.')
        if command == 'update' and visit['status'] != 'pending':
            raise s.ApiError(409, 'La visita ya no está pendiente. Detén el seguimiento.')
        points = data.get('points')
        if not isinstance(points, list) or len(points) > 30000:
            raise s.ApiError(400, 'Recorrido demasiado extenso.')
        clean = []
        for p in points:
            if not isinstance(p, dict):
                raise s.ApiError(400, 'Punto inválido.')
            clean.append(dict(lat=s.number(p, 'lat', -90, 90), lon=s.number(p, 'lon', -180, 180),
                              segment=s.number(p, 'segment', 0, 100000)))
        saved.update(sequence=sequence, active=command != 'stop', points=clean,
                     distance_m=s.number(data, 'distance_m', 0, 10000000),
                     duration_ms=s.number(data, 'duration_ms', 0, 604800000),
                     last_fix_at=s.number(data, 'last_fix_at', 0, (stamp+60)*1000),
                     accuracy=s.number(data, 'accuracy', 0, 10000), received_at=stamp)
        if command == 'stop':
            db.execute('INSERT INTO audit(user_id,visit_id,action,at) VALUES(?,?,?,?)',
                       (user['id'], visit['id'], 'journey_stopped', s.now()))
    db.execute('INSERT INTO journeys VALUES(?,?) ON CONFLICT(visit_id) DO UPDATE SET payload=excluded.payload',
               (visit['id'], json.dumps(saved, allow_nan=False)))
    return dict(ok=True, run_id=saved['run_id'])
