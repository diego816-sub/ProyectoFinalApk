import concurrent.futures
import json
import tempfile
import unittest
from pathlib import Path
import server as s


class FeatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        s.DB = Path(cls.temp.name) / 'features.db'
        s.initialize()
        with s.connect() as db:
            cls.c = s.create_user(db, dict(name='Coord',email='c@example.test',password='Password12345'),'coordinator')
            cls.t = s.create_user(db, dict(name='Tech',email='t@example.test',password='Password12345'))
            cls.b = s.create_user(db, dict(name='Other',email='b@example.test',password='Password12345'))
        cls.ct, cls.tt, cls.bt = [s.dispatch('POST','/login',dict(email=e+'@example.test',password='Password12345'),'')['token'] for e in ('c','t','b')]
        cls.client = s.dispatch('POST','/clients',dict(name='Client',address='Address',latitude=0,longitude=0),cls.ct)['id']

    def visit(self, **kwargs):
        data=dict(client_id=self.client,technician_id=self.t['id'],scheduled_date='2026-11-01',task='Test service')
        data.update(kwargs)
        return s.dispatch('POST','/visits',data,self.ct)['id']

    def call(self, uid, action, data, token=None):
        return s.dispatch('POST',f'/visits/{uid}/{action}',data,token or self.tt)

    def payload(self, **kwargs):
        data=dict(version=1,operation_id='unique-operation-123456',status='not_done',notes='Client was absent',checklist=[dict(title='Diagnóstico',done=True)],photos=[],confirmation='not_present',customer_name='')
        data.update(kwargs)
        return data

    def assert_error(self, code, action):
        with self.assertRaises(s.ApiError) as err: action()
        self.assertEqual(code,err.exception.status)

    def test_01_result_retry_is_exactly_once(self):
        uid=self.visit()
        payload=self.payload(operation_id='retry-operation-123456')
        first=self.call(uid,'result',payload)
        self.assertEqual(first,self.call(uid,'result',payload))
        with s.connect() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM reports WHERE visit_id=?',(uid,)).fetchone()[0])
            self.assertEqual(2,db.execute("SELECT count(*) FROM audit WHERE visit_id=? AND action<>'created'",(uid,)).fetchone()[0])
        self.assert_error(409,lambda:self.call(uid,'result',dict(payload,notes='Different result')))

    def test_02_concurrent_same_operation(self):
        uid=self.visit(); data=self.payload(operation_id='concurrent-operation-1234')
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results=list(pool.map(lambda _:self.call(uid,'result',data),range(2)))
        self.assertEqual(results[0],results[1])

    def test_03_stale_result_preserves_server(self):
        uid=self.visit()
        self.call(uid,'reschedule',dict(version=1,scheduled_date='2026-12-01',reason='Client requested change'),self.ct)
        self.assert_error(409,lambda:self.call(uid,'result',self.payload()))
        with s.connect() as db:
            self.assertEqual('pending',db.execute('SELECT status FROM visits WHERE id=?',(uid,)).fetchone()[0])
            self.assertIsNone(db.execute('SELECT * FROM reports WHERE visit_id=?',(uid,)).fetchone())

    def test_04_result_and_evidence_authorization(self):
        uid=self.visit()
        self.assert_error(403,lambda:self.call(uid,'result',self.payload(),self.ct))
        self.assert_error(403,lambda:self.call(uid,'result',self.payload(),self.bt))
        self.assert_error(403,lambda:s.dispatch('GET',f'/visits/{uid}/evidence',{},self.bt))

    def test_05_completion_requires_arrival_and_customer(self):
        uid=self.visit()
        self.assert_error(409,lambda:self.call(uid,'result',self.payload(status='completed')))
        self.call(uid,'arrive',dict(version=1,latitude=0,longitude=0,accuracy=5))
        self.assert_error(400,lambda:self.call(uid,'result',self.payload(version=2,status='completed',confirmation='accepted')))
        self.call(uid,'result',self.payload(version=2,status='completed',confirmation='accepted',customer_name='Ana',operation_id='complete-operation-1234'))
        v=next(v for v in s.dispatch('GET','/snapshot',{},self.ct)['visits'] if v['id']==uid)
        self.assertEqual('accepted',v['report']['confirmation'])
        self.assertEqual(['created','arrive','work_documented','service_closed'],[h['action'] for h in v['history']])
        self.assertEqual(self.t['id'],v['report']['actor_id'])

    def test_06_schedule_overlap_and_boundary(self):
        self.visit(scheduled_date='2027-01-10',scheduled_time='09:00',duration_minutes=60)
        self.assert_error(409,lambda:self.visit(scheduled_date='2027-01-10',scheduled_time='09:30',duration_minutes=60))
        self.visit(scheduled_date='2027-01-10',scheduled_time='10:00',duration_minutes=60)
        self.assert_error(400,lambda:self.visit(scheduled_time='25:00'))
        self.assert_error(400,lambda:self.visit(scheduled_time='23:30',duration_minutes=60))
        self.assert_error(400,lambda:self.visit(duration_minutes=True))

    def test_07_reschedule_history_and_restrictions(self):
        uid=self.visit()
        data=dict(version=1,scheduled_date='2027-02-10',scheduled_time='12:00',duration_minutes=30,priority='high',reason='Customer needs a later date')
        self.assert_error(403,lambda:self.call(uid,'reschedule',data))
        self.call(uid,'reschedule',data,self.ct)
        v=next(v for v in s.dispatch('GET','/snapshot',{},self.ct)['visits'] if v['id']==uid)
        details=json.loads(v['history'][-1]['details'])
        self.assertEqual('2026-11-01',details['before']['scheduled_date'])
        self.assertEqual('high',details['after']['priority'])
        self.call(uid,'arrive',dict(version=2,latitude=0,longitude=0,accuracy=5))
        self.assert_error(409,lambda:self.call(uid,'reschedule',dict(data,version=3),self.ct))

    def test_08_photo_validation_and_snapshot_excludes_bytes(self):
        import base64
        uid=self.visit()
        photo=dict(kind='before',label='Before',data='not-base64!')
        self.assert_error(400,lambda:self.call(uid,'result',self.payload(photos=[photo])))
        # Actual 1x1 JPEG fixture, independent of image libraries during test execution.
        photo['data']='/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+iiigD//2Q=='
        self.call(uid,'result',self.payload(photos=[photo],operation_id='photo-operation-12345'))
        evidence=s.dispatch('GET',f'/visits/{uid}/evidence',{},self.ct)
        self.assertEqual(photo,evidence['photos'][0])
        v=next(v for v in s.dispatch('GET','/snapshot',{},self.ct)['visits'] if v['id']==uid)
        self.assertNotIn('data',v['report']['photos'][0])

    def test_09_migration_is_repeatable(self):
        uid=self.visit()
        s.initialize();s.initialize()
        with s.connect() as db:
            self.assertIsNotNone(db.execute('SELECT id FROM visits WHERE id=?',(uid,)).fetchone())

    def test_10_revocation_invalidates_sessions(self):
        with s.connect() as db:
            user=s.create_user(db,dict(name='Revoked',email='revoked@example.test',password='Password12345'))
            db.execute('UPDATE users SET email_verified=1 WHERE id=?',(user['id'],))
        token=s.dispatch('POST','/login',dict(email='revoked@example.test',password='Password12345'),'')['token']
        s.dispatch('POST','/technicians/access',dict(user_id=user['id'],active=False),self.ct)
        self.assert_error(401,lambda:s.dispatch('GET','/snapshot',{},token))
        self.assert_error(403,lambda:s.dispatch('POST','/technicians/access',dict(user_id=self.t['id'],active=False),self.tt))
        s.dispatch('POST','/technicians/access',dict(user_id=user['id'],active=True),self.ct)
        self.assert_error(401,lambda:s.dispatch('GET','/snapshot',{},token))

    def test_11_retention_preview_and_apply_preserve_results(self):
        import retention
        uid=self.visit()
        self.call(uid,'arrive',dict(version=1,latitude=1,longitude=2,accuracy=3))
        self.call(uid,'result',self.payload(version=2,operation_id='retention-operation-123'))
        with s.connect() as db:
            db.execute("UPDATE visits SET finished_at='2020-01-01T00:00:00+00:00' WHERE id=?",(uid,))
            self.assertIn(uid,retention.purge(db,'2021-01-01',False))
            self.assertEqual(1,db.execute('SELECT arrival_lat FROM visits WHERE id=?',(uid,)).fetchone()[0])
            retention.purge(db,'2021-01-01',True)
            self.assertIsNone(db.execute('SELECT arrival_lat FROM visits WHERE id=?',(uid,)).fetchone()[0])
            self.assertEqual('Client was absent',db.execute('SELECT notes FROM visits WHERE id=?',(uid,)).fetchone()[0])
            self.assertEqual([],retention.purge(db,'2021-01-01',True))

    def test_12_wsgi_adapter_preserves_auth_and_limits(self):
        import io
        import cloud
        def request(method,path,raw=b'',token=''):
            response=[]
            body=cloud.application(dict(REQUEST_METHOD=method,PATH_INFO=path,CONTENT_LENGTH=str(len(raw)),
                HTTP_AUTHORIZATION='Bearer '+token,**{'wsgi.input':io.BytesIO(raw)}),lambda status,headers:response.append((status,headers)))
            return int(response[0][0].split()[0]),json.loads(b''.join(body))
        self.assertEqual(200,request('GET','/health')[0])
        self.assertEqual(401,request('GET','/snapshot')[0])
        self.assertEqual(200,request('GET','/snapshot',token=self.ct)[0])
        self.assertEqual(400,request('POST','/clients',b'[]',self.ct)[0])
        self.assertEqual(405,request('DELETE','/health')[0])

    def test_13_jpeg_rejects_oversized_dimensions(self):
        from visit_features import jpeg_dimensions
        raw=b'\xff\xd8\xff\xc0\x00\x08\x08\xff\xff\xff\xff\x01\xff\xd9'
        with self.assertRaises(ValueError):jpeg_dimensions(raw)


if __name__=='__main__': unittest.main()
