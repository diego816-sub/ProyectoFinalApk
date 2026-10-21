import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import server as s


class JourneyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        s.DB = Path(cls.temp.name)/'journey.db'
        s.initialize()
        cls.tokens = []
        cls.users = []
        for name, role in [('coord','coordinator'),('tech','technician'),('other','technician')]:
            with s.connect() as db:
                cls.users.append(s.create_user(db,dict(name=name,email=name+'@example.test',password='TestPassword2026'),role))
            cls.tokens.append(s.dispatch('POST','/login',dict(email=name+'@example.test',password='TestPassword2026'),'')['token'])
        cls.client=s.dispatch('POST','/clients',dict(name='Cliente',address='Calle de prueba 12',latitude=-12,longitude=-77),cls.tokens[0])['id']

    def setUp(self):
        self.visit=s.dispatch('POST','/visits',dict(client_id=self.client,technician_id=self.users[1]['id'],scheduled_date='2026-11-01',task='Revisar equipo'),self.tokens[0])['id']
        self.path=f'/visits/{self.visit}/journey'

    def start(self):
        return s.dispatch('POST',self.path,dict(command='start'),self.tokens[1])['run_id']

    def update(self, run, sequence=1, command='update'):
        return dict(command=command,run_id=run,sequence=sequence,points=[dict(lat=-12,lon=-77,segment=0)],
                    distance_m=10,duration_ms=15000,last_fix_at=time.time()*1000,accuracy=8)

    def test_permissions_and_assignment_email(self):
        with self.assertRaises(s.ApiError) as e:s.dispatch('GET',self.path,{},self.tokens[2])
        self.assertEqual(403,e.exception.status)
        with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,dict(command='start'),self.tokens[0])
        with s.connect() as db:
            message=s.assignment_message(db,self.visit)
        self.assertIn('Calle de prueba 12',message)
        self.assertIn('destination=-12',message)
        self.assertIn('Revisar equipo',message)

    def test_live_stale_and_stop(self):
        run=self.start()
        s.dispatch('POST',self.path,self.update(run),self.tokens[1])
        self.assertEqual('live',s.dispatch('GET',self.path,{},self.tokens[0])['state'])
        with patch('journeys.time.time',return_value=time.time()+100):
            self.assertEqual('stale',s.dispatch('GET',self.path,{},self.tokens[0])['state'])
        s.dispatch('POST',self.path,self.update(run,2,'stop'),self.tokens[1])
        self.assertEqual('stopped',s.dispatch('GET',self.path,{},self.tokens[0])['state'])
        with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,self.update(run,3),self.tokens[1])

    def test_duplicates_do_not_overwrite_newer_points(self):
        run=self.start()
        latest=self.update(run,2);latest['distance_m']=30
        s.dispatch('POST',self.path,latest,self.tokens[1])
        result=s.dispatch('POST',self.path,self.update(run,1),self.tokens[1])
        self.assertTrue(result['duplicate'])
        self.assertEqual(30,s.dispatch('GET',self.path,{},self.tokens[0])['distance_m'])

    def test_arrival_stops_display_and_rejects_more_locations(self):
        run=self.start()
        s.dispatch('POST',f'/visits/{self.visit}/arrive',dict(version=1,latitude=-12,longitude=-77,accuracy=8),self.tokens[1])
        self.assertEqual('stopped',s.dispatch('GET',self.path,{},self.tokens[0])['state'])
        with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,self.update(run),self.tokens[1])
        s.dispatch('POST',self.path,self.update(run,2,'stop'),self.tokens[1])

    def test_new_run_rejects_old_phone_and_active_restart(self):
        old=self.start()
        with self.assertRaises(s.ApiError):self.start()
        with patch('journeys.time.time',return_value=time.time()+121):new=self.start()
        self.assertNotEqual(new,old)
        with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,self.update(old),self.tokens[1])

    def test_no_fix_is_not_live_and_bad_coordinates_rejected(self):
        run=self.start()
        self.assertEqual('stale',s.dispatch('GET',self.path,{},self.tokens[0])['state'])
        for invalid in (float('nan'),91,True):
            data=self.update(run);data['points'][0]['lat']=invalid
            with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,data,self.tokens[1])
        self.assertEqual([],s.dispatch('GET',self.path,{},self.tokens[0])['points'])

    def test_sharing_does_not_change_visit_version(self):
        run=self.start()
        s.dispatch('POST',self.path,self.update(run),self.tokens[1])
        with s.connect() as db:
            self.assertEqual(1,db.execute('SELECT version FROM visits WHERE id=?',(self.visit,)).fetchone()[0])

    def test_revoked_account_cannot_upload(self):
        run=self.start()
        with s.connect() as db:db.execute('UPDATE users SET active=0 WHERE id=?',(self.users[1]['id'],))
        try:
            with self.assertRaises(s.ApiError):s.dispatch('POST',self.path,self.update(run),self.tokens[1])
        finally:
            with s.connect() as db:db.execute('UPDATE users SET active=1 WHERE id=?',(self.users[1]['id'],))


if __name__=='__main__':unittest.main()
