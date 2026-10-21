"""Visit evidence, scheduling and audit. Invoked inside the API's transaction."""
import evidence_store
import base64
import binascii
import datetime as dt
import hashlib
import json
import re


def jpeg_dimensions(raw):
    """Read bounded SOF dimensions before a mobile decoder sees untrusted bytes."""
    i=2
    while i+4<=len(raw):
        if raw[i]!=255: raise ValueError('marker')
        while i<len(raw) and raw[i]==255: i+=1
        if i>=len(raw): break
        marker=raw[i];i+=1
        if marker in (0xD9,0xDA): break
        if marker in range(0xD0,0xD8) or marker==1: continue
        length=int.from_bytes(raw[i:i+2],'big')
        if length<2 or i+length>len(raw): raise ValueError('length')
        if marker in (0xC0,0xC1,0xC2):
            if length<8: raise ValueError('frame')
            height=int.from_bytes(raw[i+3:i+5],'big');width=int.from_bytes(raw[i+5:i+7],'big')
            if not 1<=width<=2048 or not 1<=height<=2048 or width*height>2000000: raise ValueError('dimensions')
            return width,height
        i+=length
    raise ValueError('missing frame')


def migrate(db):
    columns = {r['name'] for r in db.execute('PRAGMA table_info(visits)')}
    for name, definition in [('scheduled_time', "TEXT NOT NULL DEFAULT ''"),
                             ('duration_minutes', 'INTEGER NOT NULL DEFAULT 60'),
                             ('priority', "TEXT NOT NULL DEFAULT 'normal'")]:
        if name not in columns:
            db.execute(f'ALTER TABLE visits ADD COLUMN {name} {definition}')
    if 'details' not in {r['name'] for r in db.execute('PRAGMA table_info(audit)')}:
        db.execute("ALTER TABLE audit ADD COLUMN details TEXT NOT NULL DEFAULT '{}'")
    db.executescript('''
      CREATE TABLE IF NOT EXISTS reports (
        visit_id TEXT PRIMARY KEY REFERENCES visits(id), payload TEXT NOT NULL,
        actor_id TEXT NOT NULL REFERENCES users(id), received_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS operations (
        user_id TEXT NOT NULL REFERENCES users(id), operation_id TEXT NOT NULL,
        digest TEXT NOT NULL, response TEXT NOT NULL, PRIMARY KEY(user_id,operation_id));
    ''')


def audit(s, db, user, visit, action, details):
    db.execute('INSERT INTO audit(user_id,visit_id,action,at,details) VALUES(?,?,?,?,?)',
               (user['id'], visit, action, s.now(), json.dumps(details, ensure_ascii=False)))


def schedule(s, data):
    day = s.text(data, 'scheduled_date', 10, 10)
    try:
        if dt.date.fromisoformat(day).isoformat() != day:
            raise ValueError()
    except ValueError:
        raise s.ApiError(400, 'Fecha inválida: usa AAAA-MM-DD.')
    clock = data.get('scheduled_time', '')
    if not isinstance(clock, str) or (clock and not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', clock)):
        raise s.ApiError(400, 'Hora inválida: usa HH:MM de 00:00 a 23:59.')
    duration = data.get('duration_minutes', 60)
    if isinstance(duration, bool) or not isinstance(duration, int) or not 15 <= duration <= 480:
        raise s.ApiError(400, 'La duración estimada debe estar entre 15 y 480 minutos.')
    priority = data.get('priority', 'normal')
    if priority not in ('low', 'normal', 'high'):
        raise s.ApiError(400, 'Selecciona una prioridad válida.')
    if clock and int(clock[:2])*60+int(clock[3:])+duration > 1440:
        raise s.ApiError(400, 'La visita debe terminar dentro del mismo día.')
    return day, clock, duration, priority


def check_conflicts(s, db, tech, values, excluding=''):
    day, clock, duration, _ = values
    if not clock:
        return  # Legacy appointments have an unknown time, never invent one.
    start = int(clock[:2])*60+int(clock[3:])
    for row in db.execute("SELECT * FROM visits WHERE technician_id=? AND scheduled_date=? AND id<>? AND status IN ('pending','in_progress') AND scheduled_time<>''", (tech, day, excluding)):
        other = int(row['scheduled_time'][:2])*60+int(row['scheduled_time'][3:])
        if start < other+row['duration_minutes'] and other < start+duration:
            raise s.ApiError(409, f"Conflicto de agenda: el técnico ya tiene una visita a las {row['scheduled_time']}. Elige otro horario.")


def enrich(db, visits):
    for v in visits:
        v['history'] = [dict(r) for r in db.execute('''SELECT a.action,a.at,a.details,COALESCE(u.name,'Mantenimiento del sistema') actor
            FROM audit a LEFT JOIN users u ON u.id=a.user_id WHERE visit_id=? ORDER BY a.id''', (v['id'],))]
        row = db.execute('SELECT payload,actor_id,received_at FROM reports WHERE visit_id=?', (v['id'],)).fetchone()
        if row:
            report = json.loads(row['payload'])
            report['photos'] = [dict(kind=p['kind'], label=p['label']) for p in report.get('photos', [])]
            report.update(actor_id=row['actor_id'], received_at=row['received_at'])
            v['report'] = report


def validate_report(s, data):
    tasks = data.get('checklist', [])
    if not isinstance(tasks, list) or len(tasks) > 20:
        raise s.ApiError(400, 'La lista admite hasta 20 tareas.')
    clean = []
    for t in tasks:
        if not isinstance(t, dict) or not isinstance(t.get('done'), bool):
            raise s.ApiError(400, 'Tarea inválida.')
        clean.append(dict(title=s.text(t, 'title', 100), done=t['done']))
    confirmation = data.get('confirmation', 'not_requested')
    if confirmation not in ('accepted', 'declined', 'not_present', 'not_requested'):
        raise s.ApiError(400, 'Confirmación inválida.')
    customer = s.text(data, 'customer_name', 100, 0) if 'customer_name' in data else ''
    if confirmation in ('accepted', 'declined') and not customer:
        raise s.ApiError(400, 'Indica el nombre de quien recibió el servicio.')
    photos = data.get('photos', [])
    if not isinstance(photos, list) or len(photos) > 4:
        raise s.ApiError(400, 'Adjunta hasta 4 fotografías.')
    checked = []
    for p in photos:
        if not isinstance(p, dict) or p.get('kind') not in ('before', 'after'):
            raise s.ApiError(400, 'Indica si la foto es de antes o después.')
        encoded = s.text(p, 'data', 550000)
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error):
            raise s.ApiError(400, 'Fotografía inválida.')
        if not 4 <= len(raw) <= 400000 or not raw.startswith(b'\xff\xd8\xff') or not raw.endswith(b'\xff\xd9'):
            raise s.ApiError(400, 'Usa fotografías JPEG de hasta 400 KB.')
        try:
            jpeg_dimensions(raw)
        except ValueError:
            raise s.ApiError(400, 'La foto no tiene un tamaño JPEG válido. Adjunta la imagen desde la aplicación.')
        checked.append(dict(kind=p['kind'], label=s.text(p, 'label', 100), data=encoded))
    return dict(checklist=clean, confirmation=confirmation, customer_name=customer, photos=checked)


def dispatch(s, db, user, method, path, data):
    if path == '/technicians/access' and method == 'POST':
        s.coordinator(user)
        uid = s.text(data, 'user_id', 32)
        active = data.get('active')
        if not isinstance(active, bool):
            raise s.ApiError(400, 'Estado de acceso inválido.')
        db.execute('BEGIN IMMEDIATE')
        row = db.execute("SELECT * FROM users WHERE id=? AND role='technician'", (uid,)).fetchone()
        if not row or (active and not row['email_verified']):
            raise s.ApiError(400, 'Solo se reactivan técnicos con correo verificado.')
        db.execute('UPDATE users SET active=? WHERE id=?', (int(active), uid))
        db.execute('DELETE FROM sessions WHERE user_id=?', (uid,))
        db.execute('DELETE FROM email_codes WHERE user_id=?', (uid,))
        audit(s, db, user, '', 'access_changed', dict(user_id=uid, active=active))
        return {'ok': True}
    parts = path.strip('/').split('/')
    if len(parts) != 3 or parts[0] != 'visits' or parts[2] not in ('reschedule', 'result', 'evidence'):
        return None
    uid, action = parts[1:]
    visit = s.get_visit(db, uid, user)
    if action == 'evidence' and method == 'GET':
        row = db.execute('SELECT payload FROM reports WHERE visit_id=?', (uid,)).fetchone()
        try:
            return evidence_store.materialize(json.loads(row['payload'])) if row else {'photos': []}
        except Exception:
            raise s.ApiError(503, 'No se pudo descargar la evidencia. Reintenta cuando haya conexión.') from None
    if method != 'POST' or action == 'evidence':
        raise s.ApiError(405, 'Método no permitido.')
    db.execute('BEGIN IMMEDIATE')
    visit = s.get_visit(db, uid, user)
    if action == 'reschedule':
        s.coordinator(user)
        if visit['status'] != 'pending':
            raise s.ApiError(409, 'Solo se reprograman visitas pendientes de atención.')
        if data.get('version') != visit['version']:
            raise s.ApiError(409, 'La visita cambió. Actualiza antes de reprogramar.')
        values = schedule(s, data)
        reason = s.text(data, 'reason', 500, 5)
        check_conflicts(s, db, visit['technician_id'], values, uid)
        old = {k: visit[k] for k in ('scheduled_date', 'scheduled_time', 'duration_minutes', 'priority')}
        db.execute('UPDATE visits SET scheduled_date=?,scheduled_time=?,duration_minutes=?,priority=?,version=version+1 WHERE id=?', (*values, uid))
        recipient=db.execute('SELECT email,email_verified FROM users WHERE id=?',(visit['technician_id'],)).fetchone()
        if recipient['email_verified']:
            db.execute("INSERT INTO mail_outbox(visit_id,recipient,subject,body) VALUES(?,?,?,?) ON CONFLICT(visit_id) DO UPDATE SET subject=excluded.subject,body=excluded.body,status='pending',attempts=0,next_try=0",
                       (uid,recipient['email'],'NexoVisita: visita reprogramada',s.assignment_message(db, uid)))
        audit(s, db, user, uid, 'rescheduled', dict(before=old, after=dict(zip(old, values)), reason=reason))
        return {'ok': True}
    if user['role'] != 'technician' or visit['technician_id'] != user['id']:
        raise s.ApiError(403, 'Solo el técnico asignado puede registrar el resultado.')
    op = s.text(data, 'operation_id', 64, 16)
    digest = hashlib.sha256((uid+json.dumps(data, sort_keys=True, ensure_ascii=False)).encode()).hexdigest()
    previous = db.execute('SELECT * FROM operations WHERE user_id=? AND operation_id=?', (user['id'], op)).fetchone()
    if previous:
        if previous['digest'] != digest:
            raise s.ApiError(409, 'Este identificador de envío ya corresponde a otro contenido.')
        return json.loads(previous['response'])
    if data.get('version') != visit['version']:
        raise s.ApiError(409, 'La visita cambió en el servidor. El borrador se conserva: revisa la versión actual antes de volver a enviarlo.')
    state = data.get('status')
    if visit['status'] not in ('pending', 'in_progress') or state not in ('completed', 'not_done'):
        raise s.ApiError(409, 'La visita ya se cerró o el resultado no es válido.')
    if state == 'completed' and visit['status'] != 'in_progress':
        raise s.ApiError(409, 'Registra la llegada antes de completar la visita.')
    notes = s.text(data, 'notes', 2000, 5)
    report = validate_report(s, data)
    stamp = s.now()
    report.update(notes=notes, status=state)
    try:
        report = evidence_store.externalize(report, uid)
    except Exception:
        raise s.ApiError(503, 'No se pudo guardar la evidencia en Cloudinary. El borrador se conserva; vuelve a sincronizar.') from None
    db.execute('INSERT INTO reports VALUES(?,?,?,?)', (uid, json.dumps(report, ensure_ascii=False), user['id'], stamp))
    db.execute('UPDATE visits SET status=?,notes=?,finished_at=?,version=version+1 WHERE id=?', (state, notes, stamp, uid))
    audit(s, db, user, uid, 'work_documented', dict(tasks=report['checklist'], photos=len(report['photos'])))
    audit(s, db, user, uid, 'service_closed', dict(status=state, confirmation=report['confirmation'], customer_name=report['customer_name'], notes=notes))
    response = dict(ok=True, received_at=stamp, version=visit['version']+1)
    db.execute('INSERT INTO operations VALUES(?,?,?,?)', (user['id'], op, digest, json.dumps(response)))
    return response
