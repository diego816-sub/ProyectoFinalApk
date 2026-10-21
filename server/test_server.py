import concurrent.futures
import hashlib
import json
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer
import server as s


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        s.DB = Path(cls.temp.name) / 'test.db'
        s.initialize()
        with s.connect() as db:
            cls.coord = s.create_user(db, dict(name='Coordinador', email='coord@example.test', password='ClavePrueba2026'), 'coordinator')
            cls.tech = s.create_user(db, dict(name='Tecnico A', email='a@example.test', password='ClavePrueba2026'))
            cls.other = s.create_user(db, dict(name='Tecnico B', email='b@example.test', password='ClavePrueba2026'))
        cls.ct = s.dispatch('POST','/login',dict(email='coord@example.test',password='ClavePrueba2026'),'')['token']
        cls.at = s.dispatch('POST','/login',dict(email='a@example.test',password='ClavePrueba2026'),'')['token']
        cls.bt = s.dispatch('POST','/login',dict(email='b@example.test',password='ClavePrueba2026'),'')['token']
        cls.client = s.dispatch('POST','/clients',dict(name='Cliente de prueba',address='Dirección ficticia',latitude=-12.04,longitude=-77.04),cls.ct)['id']

    def visit(self):
        return s.dispatch('POST','/visits',dict(client_id=self.client,technician_id=self.tech['id'],scheduled_date='2026-10-20',task='Mantenimiento preventivo'),self.ct)['id']

    def call(self, uid, action, data, token=None):
        return s.dispatch('POST',f'/visits/{uid}/{action}',data,token or self.at)

    def error(self, status, f, *args):
        with self.assertRaises(s.ApiError) as cm: f(*args)
        self.assertEqual(status, cm.exception.status)

    def test_01_invalid_login(self):
        self.error(401,s.dispatch,'POST','/login',dict(email='a@example.test',password='incorrecta'),'')

    def test_02_requires_session(self):
        self.error(401,s.dispatch,'GET','/snapshot',{},'inventado')

    def test_03_passwords_and_tokens_not_stored_plain(self):
        with s.connect() as db:
            row=db.execute('SELECT password FROM users WHERE id=?',(self.tech['id'],)).fetchone()
            self.assertNotEqual('ClavePrueba2026', row[0])
            self.assertIsNone(db.execute('SELECT digest FROM sessions WHERE digest=?',(self.at,)).fetchone())
            self.assertIsNotNone(db.execute('SELECT digest FROM sessions WHERE digest=?',(hashlib.sha256(self.at.encode()).hexdigest(),)).fetchone())

    def test_04_cannot_escalate_role(self):
        self.error(403,s.dispatch,'POST','/technicians',dict(name='Admin',email='x@example.test',password='ClavePrueba2026',role='coordinator'),self.at)
        with patch.object(s.mailer, 'send'):
            user=s.dispatch('POST','/technicians',dict(name='Nuevo',email='nuevo@example.test',password='ClavePrueba2026',role='coordinator'),self.ct)
        self.assertEqual('technician',user['role'])

    def test_05_technician_cannot_create_clients(self):
        self.error(403,s.dispatch,'POST','/clients',{},self.at)

    def test_06_clients_require_valid_coordinates(self):
        for lat in (float('nan'),91,True):
            self.error(400,s.dispatch,'POST','/clients',dict(name='X',address='Y',latitude=lat,longitude=0),self.ct)

    def test_07_visits_are_private(self):
        uid=self.visit()
        visible=s.dispatch('GET','/snapshot',{},self.bt)
        self.assertNotIn(uid,[v['id'] for v in visible['visits']])
        self.assertEqual([],visible['clients'])
        self.error(403,self.call,uid,'finish',dict(version=1,status='not_done',notes='No pude ingresar'),self.bt)

    def test_08_complete_requires_arrival(self):
        uid=self.visit()
        self.error(409,self.call,uid,'finish',dict(version=1,status='completed',notes='Trabajo listo'))

    def test_09_full_workflow(self):
        uid=self.visit()
        self.call(uid,'arrive',dict(version=1,latitude=-12.04,longitude=-77.04,accuracy=8))
        self.call(uid,'finish',dict(version=2,status='completed',notes='Equipo reparado y probado'))
        v=next(v for v in s.dispatch('GET','/snapshot',{},self.ct)['visits'] if v['id']==uid)
        self.assertEqual('completed',v['status']); self.assertIsNotNone(v['finished_at']); self.assertEqual(3,v['version'])

    def test_10_rejects_stale_edits(self):
        uid=self.visit()
        self.call(uid,'arrive',dict(version=1,latitude=0,longitude=0,accuracy=5))
        self.error(409,self.call,uid,'finish',dict(version=1,status='completed',notes='Finalizado'))

    def test_11_no_double_close(self):
        uid=self.visit()
        self.call(uid,'finish',dict(version=1,status='not_done',notes='Cliente ausente'))
        self.error(409,self.call,uid,'finish',dict(version=2,status='not_done',notes='Otro resultado'))

    def test_12_notes_required(self):
        self.error(400,self.call,self.visit(),'finish',dict(version=1,status='not_done',notes=''))

    def test_13_coordinator_cannot_impersonate_technician(self):
        self.error(403,self.call,self.visit(),'arrive',dict(version=1,latitude=0,longitude=0,accuracy=5),self.ct)

    def test_14_route_shared_with_coordinator_only(self):
        uid=self.visit()
        route=dict(version=1,distance_m=100,duration_ms=60000,started_at=1000,ended_at=61000,
                   points=[dict(lat=0,lon=0,segment=0),dict(lat=0,lon=.001,segment=0)])
        self.call(uid,'route',route)
        actual=s.dispatch('GET',f'/visits/{uid}/route',{},self.ct)
        self.assertEqual(100,actual['distance_m'])
        self.error(403,s.dispatch,'GET',f'/visits/{uid}/route',{},self.bt)

    def test_15_invalid_route_rejected(self):
        self.error(400,self.call,self.visit(),'route',dict(version=1,distance_m=-5))

    def test_16_invalid_assignment(self):
        data=dict(client_id=self.client,technician_id=self.coord['id'],scheduled_date='2026-10-20',task='Tarea')
        self.error(400,s.dispatch,'POST','/visits',data,self.ct)
        data.update(technician_id=self.tech['id'],scheduled_date='2026-02-30')
        self.error(400,s.dispatch,'POST','/visits',data,self.ct)

    def test_17_session_revocation(self):
        token=s.dispatch('POST','/login',dict(email='a@example.test',password='ClavePrueba2026'),'')['token']
        s.dispatch('POST','/logout',{},token)
        self.error(401,s.dispatch,'GET','/snapshot',{},token)

    def test_18_concurrent_closure_has_one_winner(self):
        uid=self.visit()
        def close(_):
            try: self.call(uid,'finish',dict(version=1,status='not_done',notes='Cliente ausente')); return 200
            except s.ApiError as e: return e.status
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual([200,409],sorted(pool.map(close,range(2))))

    def test_19_real_http_contract(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),s.Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        base=f'http://127.0.0.1:{server.server_port}'
        try:
            with urllib.request.urlopen(base+'/health') as result: self.assertEqual('NexoVisita',json.load(result)['app'])
            req=urllib.request.Request(base+'/snapshot',headers={'Authorization':'Bearer '+self.ct})
            with urllib.request.urlopen(req) as result: self.assertEqual('coordinator',json.load(result)['user']['role'])
            with self.assertRaises(urllib.error.HTTPError) as cm: urllib.request.urlopen(base+'/snapshot')
            self.assertEqual(401,cm.exception.code)
            req=urllib.request.Request(base+'/login',data=b'[]',headers={'Content-Type':'application/json'})
            with self.assertRaises(urllib.error.HTTPError) as cm: urllib.request.urlopen(req)
            self.assertEqual(400,cm.exception.code)
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__=='__main__': unittest.main(verbosity=2)
