import base64
import copy
import io
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from contextlib import closing
from unittest.mock import patch, MagicMock

import database
import evidence_store
import gmail_api
import mailer
import migrate_mysql
import server as s

MEDIA = dict(NEXOVISITA_MEDIA='cloudinary', CLOUDINARY_CLOUD_NAME='test', CLOUDINARY_API_KEY='fake',
             CLOUDINARY_API_SECRET='fake', CLOUDINARY_PREFIX='nexo-test')
MAIL = dict(NEXOVISITA_MAIL='gmail_api', GMAIL_CLIENT_ID='client', GMAIL_CLIENT_SECRET='secret',
            GMAIL_REFRESH_TOKEN='refresh', GMAIL_FROM='project@example.test')


class CloudTests(unittest.TestCase):
    def test_health_does_not_wait_for_photo_transaction(self):
        with patch.object(s,'connect') as connect:
            self.assertEqual('1.10',s.dispatch('GET','/health',{},'')['version'])
        connect.assert_not_called()

    def test_sql_binding_preserves_literals_and_upserts(self):
        sql="INSERT INTO routes VALUES (?,?) ON CONFLICT(visit_id) DO UPDATE SET payload=excluded.payload"
        self.assertEqual(database.translate(sql), 'INSERT INTO routes VALUES (%s,%s) ON DUPLICATE KEY UPDATE payload=VALUES(payload)')
        self.assertEqual(database.translate("SELECT '?' WHERE username=? COLLATE NOCASE"),"SELECT '?' WHERE username=%s")
        self.assertEqual(database.translate('INSERT OR REPLACE INTO code_limits VALUES (?,?,1)'), 'REPLACE INTO code_limits VALUES (%s,%s,1)')

    def test_mysql_commit_and_rollback_release_connection(self):
        env=dict(NEXOVISITA_DB_DRIVER='mysql', MYSQL_HOST='test',MYSQL_PORT='3306',MYSQL_USER='u',
                 MYSQL_PASSWORD='p',MYSQL_DATABASE='nexo',MYSQL_SSL_CA='ca.pem')
        for fail in (False,True):
            raw=MagicMock(); raw.cursor.return_value.__enter__.return_value.fetchone.return_value={'acquired':1}
            with patch.dict(os.environ,env), patch('database.ssl.create_default_context') as tls, patch('pymysql.connect',return_value=raw) as connect:
                try:
                    with database.connect():
                        if fail: raise ValueError('rollback')
                except ValueError: pass
                raw.close.assert_called_once()
                (raw.rollback if fail else raw.commit).assert_called_once()
                self.assertIs(connect.call_args.kwargs['ssl'],tls.return_value)
                self.assertFalse(connect.call_args.kwargs['autocommit'])

    def test_mysql_lock_timeout_does_not_enter_transaction(self):
        env=dict(MYSQL_HOST='test',MYSQL_PORT='3306',MYSQL_USER='u',MYSQL_PASSWORD='p',MYSQL_DATABASE='nexo',MYSQL_SSL_CA='ca.pem')
        raw=MagicMock();raw.cursor.return_value.__enter__.return_value.fetchone.return_value={'acquired':0}
        with patch.dict(os.environ,env),patch('database.ssl.create_default_context'),patch('pymysql.connect',return_value=raw):
            with self.assertRaises(RuntimeError):
                with database.connect(): self.fail('must not enter')
        raw.close.assert_called_once()
        raw.commit.assert_not_called()

    def test_photo_private_roundtrip_and_stable_id(self):
        raw=b'fake-bytes-for-transport-test'
        report={'photos':[dict(kind='before',label='Antes',data=base64.b64encode(raw).decode())]}
        before=copy.deepcopy(report)
        with patch.dict(os.environ, MEDIA), patch('cloudinary.uploader.upload') as upload:
            upload.side_effect=lambda *a,**k:dict(public_id=k['public_id'],type=k['type'])
            saved=evidence_store.externalize(report,'visit123')
            self.assertEqual(saved,evidence_store.externalize(report,'visit123'))
            self.assertEqual('authenticated',upload.call_args.kwargs['type'])
        self.assertEqual(before,report)
        self.assertNotIn('data',saved['photos'][0])
        with patch.dict(os.environ,MEDIA),patch('evidence_store.urlopen',return_value=io.BytesIO(raw)):
            self.assertEqual(report,evidence_store.materialize(saved))
        with patch.dict(os.environ,MEDIA),patch('evidence_store.urlopen',return_value=io.BytesIO(b'tampered')):
            with self.assertRaises(RuntimeError): evidence_store.materialize(saved)

    def test_public_upload_is_rejected(self):
        report={'photos':[dict(kind='after',label='Después',data='YWJj')]}
        with patch.dict(os.environ,MEDIA),patch('cloudinary.uploader.upload',return_value={'public_id':'x','type':'upload'}):
            with self.assertRaises(RuntimeError):evidence_store.externalize(report,'visit')

    def test_gmail_https_uses_raw_message_and_never_smtp(self):
        with patch.dict(os.environ,MAIL),patch('gmail_api.post',side_effect=[{'access_token':'access'},{'id':'sent'}]) as post,patch('smtplib.SMTP') as smtp:
            mailer.send('tech@example.test','Código','123456')
        smtp.assert_not_called()
        self.assertEqual('https://oauth2.googleapis.com/token',post.call_args_list[0].args[0])
        body=json.loads(post.call_args_list[1].args[1])
        self.assertIn(b'123456',base64.urlsafe_b64decode(body['raw']))
        self.assertEqual('Bearer access',post.call_args_list[1].args[2]['Authorization'])

    def test_missing_gmail_token_is_not_silently_smtp(self):
        with patch.dict(os.environ,dict(MAIL,GMAIL_REFRESH_TOKEN='')),patch('smtplib.SMTP') as smtp:
            with self.assertRaises(RuntimeError):mailer.send('x@example.test','s','b')
        smtp.assert_not_called()

    def test_copy_refuses_nonempty_destination_before_upload(self):
        source=MagicMock(); target=MagicMock()
        target.execute.return_value.fetchone.return_value=[1]
        with patch('evidence_store.externalize') as upload:
            with self.assertRaises(ValueError):migrate_mysql.copy_records(source,target)
        source.execute.assert_not_called();upload.assert_not_called()


class CloudApiTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.old=s.DB
        s.DB=Path(self.temp.name)/'api.db';s.initialize()
        with s.connect() as db:
            self.c=s.create_user(db,dict(name='Coord',email='coord@example.test',password='Password12345'),'coordinator')
            self.t=s.create_user(db,dict(name='Tech',email='tech@example.test',password='Password12345'))
            self.b=s.create_user(db,dict(name='Other',email='other@example.test',password='Password12345'))
        self.ct,self.tt,self.bt=[s.dispatch('POST','/login',dict(email=e+'@example.test',password='Password12345'),'')['token'] for e in ('coord','tech','other')]
        cid=s.dispatch('POST','/clients',dict(name='Client',address='Address',latitude=0,longitude=0),self.ct)['id']
        self.uid=s.dispatch('POST','/visits',dict(client_id=cid,technician_id=self.t['id'],scheduled_date='2026-12-01',task='Service'),self.ct)['id']
        self.payload=dict(version=1,operation_id='cloud-operation-123456',status='not_done',notes='Client not available',photos=[])
    def tearDown(self):
        s.DB=self.old;self.temp.cleanup()

    def test_cloud_upload_failure_rolls_back_and_retry_is_idempotent(self):
        path=f'/visits/{self.uid}/result'
        with patch('evidence_store.externalize',side_effect=RuntimeError('provider')):
            with self.assertRaises(s.ApiError) as e:s.dispatch('POST',path,self.payload,self.tt)
            self.assertEqual(503,e.exception.status)
        with s.connect() as db:
            self.assertEqual('pending',db.execute('SELECT status FROM visits WHERE id=?',(self.uid,)).fetchone()[0])
            self.assertIsNone(db.execute('SELECT * FROM reports').fetchone())
            self.assertIsNone(db.execute('SELECT * FROM operations').fetchone())
        with patch('evidence_store.externalize',side_effect=lambda r,v:r) as upload:
            first=s.dispatch('POST',path,self.payload,self.tt)
            self.assertEqual(first,s.dispatch('POST',path,self.payload,self.tt))
            upload.assert_called_once()

    def test_authorization_precedes_cloud_download(self):
        s.dispatch('POST',f'/visits/{self.uid}/result',self.payload,self.tt)
        with patch('evidence_store.materialize') as download:
            with self.assertRaises(s.ApiError) as e:s.dispatch('GET',f'/visits/{self.uid}/evidence',{},self.bt)
            self.assertEqual(403,e.exception.status);download.assert_not_called()
        with patch('evidence_store.materialize',side_effect=RuntimeError('provider')):
            with self.assertRaises(s.ApiError) as e:s.dispatch('GET',f'/visits/{self.uid}/evidence',{},self.ct)
            self.assertEqual(503,e.exception.status)

    def test_copy_keeps_password_hash_and_skips_sessions(self):
        dest=sqlite3.connect(':memory:');dest.row_factory=sqlite3.Row
        with closing(sqlite3.connect(s.DB)) as source:
            source.row_factory=sqlite3.Row
            for row in source.execute("SELECT sql FROM sqlite_master WHERE type='table'"):
                dest.execute(row[0])
            counts=migrate_mysql.copy_records(source,dest)
            self.assertEqual(3,counts['users'])
            self.assertEqual(0,dest.execute('SELECT count(*) FROM sessions').fetchone()[0])
            self.assertEqual(tuple(source.execute('SELECT salt,password FROM users ORDER BY id').fetchall()[0]),tuple(dest.execute('SELECT salt,password FROM users ORDER BY id').fetchall()[0]))
        dest.close()

    def test_migration_preview_leaves_source_and_cloud_untouched(self):
        import hashlib
        before=hashlib.sha256(s.DB.read_bytes()).hexdigest()
        with patch.dict(os.environ,dict(MEDIA,NEXOVISITA_DB_DRIVER='mysql')),patch('database.connect') as remote,patch('evidence_store.externalize') as upload,patch('sys.stdout',new_callable=io.StringIO):
            migrate_mysql.migrate(s.DB,False)
        self.assertEqual(before,hashlib.sha256(s.DB.read_bytes()).hexdigest())
        remote.assert_not_called();upload.assert_not_called()


if __name__=='__main__':unittest.main()
