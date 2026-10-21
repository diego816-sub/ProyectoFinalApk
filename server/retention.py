"""Preview by default. Remove old precise locations/photos only with --apply."""
import evidence_store
import argparse
import datetime as dt
import json
import sqlite3
import server as s


def purge(db, cutoff, apply=False):
    rows=list(db.execute("SELECT id FROM visits WHERE finished_at IS NOT NULL AND finished_at<?",(cutoff,)))
    affected=[]
    for row in rows:
        uid=row['id']
        route=db.execute('SELECT 1 FROM routes WHERE visit_id=?',(uid,)).fetchone()
        coordinates=db.execute('SELECT arrival_lat FROM visits WHERE id=?',(uid,)).fetchone()[0]
        report=db.execute('SELECT payload FROM reports WHERE visit_id=?',(uid,)).fetchone()
        data=json.loads(report['payload']) if report else {}
        journey=db.execute('SELECT 1 FROM journeys WHERE visit_id=?',(uid,)).fetchone()
        if not route and not journey and coordinates is None and not data.get('photos'):
            continue
        affected.append(uid)
        if apply:
            db.execute('DELETE FROM routes WHERE visit_id=?',(uid,))
            db.execute('DELETE FROM journeys WHERE visit_id=?',(uid,))
            db.execute('UPDATE visits SET arrival_lat=NULL,arrival_lon=NULL,arrival_accuracy=NULL,version=version+1 WHERE id=?',(uid,))
            if data.get('photos'):
                evidence_store.remove_photos(data)
                data['photos']=[]
                data['photos_removed_at']=s.now()
                db.execute('UPDATE reports SET payload=? WHERE visit_id=?',(json.dumps(data,ensure_ascii=False),uid))
            db.execute('INSERT INTO audit(user_id,visit_id,action,at,details) VALUES(?,?,?,?,?)',
                       ('retention',uid,'sensitive_data_removed',s.now(),json.dumps(dict(cutoff=cutoff))))
    return affected


if __name__=='__main__':
    parser=argparse.ArgumentParser(description='Retención de ubicaciones y fotografías de visitas cerradas')
    parser.add_argument('--days',type=int,default=90)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    if args.days<1: parser.error('days debe ser mayor que cero')
    if s.database.is_mysql(): raise SystemExit('En MySQL utiliza exportación previa y una política de retención revisada. Este comando solo administra la copia SQLite local.')
    if not s.DB.is_file(): raise SystemExit('No se encontró la base existente. No se creó ninguna base.')
    cutoff=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(days=args.days)).isoformat(timespec='seconds')
    if args.apply:
        backup=s.DB.with_name('nexovisita_before_retention_'+dt.datetime.now().strftime('%Y%m%d_%H%M%S')+'.db')
        with sqlite3.connect(s.DB) as source, sqlite3.connect(backup) as target: source.backup(target)
        print('Copia previa creada. También contiene datos sensibles: aplica la política de borrado a las copias.')
    with s.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        affected=purge(db,cutoff,args.apply)
    print(f'{len(affected)} visitas: '+('datos sensibles retirados.' if args.apply else 'simulación; no se cambió ningún dato. Usa --apply tras revisar la política y las copias.'))
