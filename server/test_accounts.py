import concurrent.futures
import json
import re
import sqlite3
import tempfile
import time
import unittest
import threading
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch, MagicMock
import server as s
import mailer


class AccountsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = s.DB
        s.DB = Path(self.temp.name) / 'accounts.db'
        s.initialize()
        self.mail = patch.object(mailer, 'send').start()
        patch.object(mailer, 'settings', return_value=({}, 'fake')).start()
        with s.connect() as db:
            self.coordinator = s.create_user(db, dict(name='Coordinador', email='coord@example.test', password='OriginalClave123'), 'coordinator')
        self.token = s.dispatch('POST', '/login', dict(identifier='coord@example.test', password='OriginalClave123'), '')['token']

    def tearDown(self):
        patch.stopall()
        s.DB = self.previous
        self.temp.cleanup()

    def call(self, path, data, token=''):
        return s.dispatch('POST', path, data, token)

    def invite(self, email='tech@example.test'):
        return self.call('/technicians', dict(name='Técnico', email=email, password='CannotChoose123', role='coordinator'), self.token)

    def code(self):
        return re.search(r'es: (\d{6})', self.mail.call_args.args[2])[1]

    def activate(self, email='tech@example.test', username='tecnico', code=None):
        return self.call('/activate/confirm', dict(email=email, username=username, code=code or self.code(), password='NuevaClave123'))

    def assert_error(self, status, fn, *args):
        with self.assertRaises(s.ApiError) as error:
            fn(*args)
        self.assertEqual(status, error.exception.status)

    def test_invite_activation_login_and_replay(self):
        user = self.invite()
        code = self.code()
        self.assertEqual(0, user['active'])
        self.assertEqual('technician', user['role'])
        self.assertNotIn(code, json.dumps(user))
        self.assert_error(401, self.call, '/login', dict(identifier='tech@example.test',password='CannotChoose123'))
        with s.connect() as db:
            entry = db.execute('SELECT * FROM email_codes').fetchone()
            self.assertNotEqual(code, entry['digest'])
        self.activate()
        login = self.call('/login', dict(identifier='TECNICO', password='NuevaClave123'))
        self.assertEqual(user['id'], login['user']['id'])
        self.assertEqual(1, login['user']['email_verified'])
        self.assert_error(401, self.call, '/login', dict(identifier='tech@example.test', password='NuevaClave123'))
        self.assert_error(400, self.activate, 'tech@example.test', 'tecnico', code)

    def test_code_expires(self):
        self.invite()
        with s.connect() as db:
            db.execute('UPDATE email_codes SET expires=0')
        self.assert_error(400, self.activate)

    def test_attempts_persist_and_block_valid_code(self):
        self.invite()
        correct = self.code()
        wrong = '000000' if correct != '000000' else '999999'
        for _ in range(5):
            self.assert_error(400, self.activate, 'tech@example.test', 'tecnico', wrong)
        with s.connect() as db:
            self.assertEqual(5, db.execute('SELECT attempts FROM email_codes').fetchone()[0])
        self.assert_error(400, self.activate, 'tech@example.test', 'tecnico', correct)

    def test_resend_cooldown_and_old_code_invalid(self):
        self.invite()
        old = self.code()
        self.assert_error(429, self.call, '/activate/request', dict(email='tech@example.test'))
        with s.connect() as db:
            db.execute('UPDATE email_codes SET sent_at=0')
        with patch.object(s.secrets, 'randbelow', return_value=(int(old)+1) % 1000000):
            self.call('/activate/request', dict(email='tech@example.test'))
        self.assert_error(400, self.activate, 'tech@example.test', 'tecnico', old)
        self.activate()

    def test_hourly_limit(self):
        self.invite()
        with s.connect() as db:
            db.execute('UPDATE email_codes SET sent_at=0')
            db.execute('UPDATE code_limits SET count=10')
        self.assert_error(429, self.call, '/activate/request', dict(email='tech@example.test'))

    def test_duplicate_username_is_case_insensitive(self):
        self.invite(); self.activate(username='Tecnico')
        self.invite('second@example.test')
        self.assert_error(409, self.activate, 'second@example.test', 'TECNICO')
        self.activate('second@example.test', 'otro')

    def test_reset_revokes_sessions_and_reminds_username(self):
        self.invite(); self.activate()
        session = self.call('/login', dict(identifier='tecnico', password='NuevaClave123'))['token']
        self.call('/password/request', dict(email='tech@example.test'))
        code = self.code()
        data = dict(email='tech@example.test', code=code, password='Recuperada456')
        result = self.call('/password/confirm', data)
        self.assertEqual('tecnico', result['username'])
        self.assert_error(401, s.dispatch, 'GET', '/snapshot', {}, session)
        self.assert_error(400, self.call, '/password/confirm', data)
        self.assert_error(401, self.call, '/login', dict(identifier='tecnico',password='NuevaClave123'))
        self.assertIn('token', self.call('/login', dict(identifier='tecnico',password='Recuperada456')))

    def test_unknown_email_and_unverified_reset_are_generic(self):
        unknown = self.call('/password/request', dict(email='missing@example.test'))
        known = self.call('/password/request', dict(email='coord@example.test'))
        self.assertEqual(unknown, known)
        self.mail.assert_not_called()

    def test_codes_are_scoped_to_purpose(self):
        self.invite()
        self.assert_error(400, self.call, '/password/confirm', dict(email='tech@example.test', code=self.code(), password='NuevaClave123'))

    def test_smtp_failure_rolls_back_invitation(self):
        self.mail.side_effect = OSError('fake SMTP failure')
        self.assert_error(503, self.invite)
        with s.connect() as db:
            self.assertEqual(1, db.execute('SELECT count(*) FROM users').fetchone()[0])
            self.assertEqual(0, db.execute('SELECT count(*) FROM email_codes').fetchone()[0])

    def test_smtp_failure_on_resend_preserves_previous_code(self):
        self.invite()
        code = self.code()
        with s.connect() as db: db.execute('UPDATE email_codes SET sent_at=0')
        self.mail.side_effect = OSError()
        self.assert_error(503, self.call, '/activate/request', dict(email='tech@example.test'))
        self.activate(code=code)

    def test_notifications_queued_retry_and_sent(self):
        tech = self.invite(); self.activate()
        client = self.call('/clients', dict(name='Cliente',address='Direccion',latitude=0,longitude=0), self.token)['id']
        visit = self.call('/visits', dict(client_id=client,technician_id=tech['id'],scheduled_date='2026-11-01',task='Revisar equipo'),self.token)
        self.assertEqual('pending', visit['mail_status'])
        self.mail.side_effect = OSError()
        s.deliver_one_mail()
        with s.connect() as db:
            row = db.execute('SELECT * FROM mail_outbox').fetchone()
            self.assertEqual('pending', row['status'])
            db.execute('UPDATE mail_outbox SET next_try=0,attempts=4')
        s.deliver_one_mail()
        with s.connect() as db: self.assertEqual('failed', db.execute('SELECT status FROM mail_outbox').fetchone()[0])
        session = self.call('/login', dict(identifier='tecnico',password='NuevaClave123'))['token']
        self.assert_error(403,self.call,'/mail/retry',dict(visit_id=visit['id']),session)
        self.call('/mail/retry',dict(visit_id=visit['id']),self.token)
        self.mail.side_effect = None
        s.deliver_one_mail()
        with s.connect() as db:
            self.assertEqual('sent', db.execute('SELECT status FROM mail_outbox').fetchone()[0])
            self.assertEqual(1, db.execute('SELECT count(*) FROM visits').fetchone()[0])
        self.assertFalse(s.deliver_one_mail())

    def test_cannot_assign_pending_account(self):
        tech = self.invite()
        client = self.call('/clients',dict(name='Cliente',address='Direccion',latitude=0,longitude=0),self.token)['id']
        self.assert_error(400,self.call,'/visits',dict(client_id=client,technician_id=tech['id'],scheduled_date='2026-11-01',task='Trabajo'),self.token)

    def test_two_simultaneous_confirmations_only_one_succeeds(self):
        self.invite(); code = self.code()
        def run():
            try:
                self.activate(code=code)
                return 200
            except s.ApiError as e: return e.status
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            statuses = list(pool.map(lambda _: run(), range(2)))
        self.assertEqual([200,400], sorted(statuses))

    def test_legacy_coordinator_keeps_id_and_role_after_verification(self):
        self.call('/activate/request',dict(email='coord@example.test'))
        self.activate('coord@example.test','diego')
        result = self.call('/login',dict(identifier='diego',password='NuevaClave123'))
        self.assertEqual(self.coordinator['id'], result['user']['id'])
        self.assertEqual('coordinator', result['user']['role'])

    def test_http_invitation_activation_and_login(self):
        http = ThreadingHTTPServer(('127.0.0.1', 0), s.Handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        s.Handler.attempts = {}
        base = f'http://127.0.0.1:{http.server_port}'
        def post(path, payload, token=''):
            req = urllib.request.Request(base+path, data=json.dumps(payload).encode(), headers={
                'Content-Type':'application/json', 'Authorization':'Bearer '+token})
            with urllib.request.urlopen(req) as response: return json.load(response)
        try:
            invited = post('/technicians',dict(name='Técnico',email='http@example.test'),self.token)
            self.assertEqual(0, invited['active'])
            post('/activate/confirm',dict(email='http@example.test',username='http_user',code=self.code(),password='NuevaClave123'))
            self.assertIn('token',post('/login',dict(identifier='http_user',password='NuevaClave123')))
        finally:
            http.shutdown(); http.server_close(); thread.join()

    def test_missing_mail_configuration_fails_explicitly(self):
        with patch.object(mailer,'settings',side_effect=RuntimeError('not configured')):
            self.assert_error(503,self.call,'/activate/request',dict(email='coord@example.test'))

    def test_correct_legacy_email_preserves_account_and_revokes_session(self):
        import actualizar_correo
        with patch('builtins.input',side_effect=['coord@example.test','real@example.test']), patch.object(actualizar_correo.getpass,'getpass',return_value='OriginalClave123'), patch('builtins.print'):
            actualizar_correo.main()
        with s.connect() as db:
            row = db.execute('SELECT * FROM users WHERE id=?',(self.coordinator['id'],)).fetchone()
            self.assertEqual('real@example.test',row['email'])
            self.assertEqual('coordinator',row['role'])
        self.assert_error(401,s.dispatch,'GET','/snapshot',{},self.token)

    def test_cannot_correct_legacy_email_with_wrong_password(self):
        import actualizar_correo
        with patch('builtins.input',side_effect=['coord@example.test','real@example.test']), patch.object(actualizar_correo.getpass,'getpass',return_value='Incorrecta'), patch('builtins.print'):
            with self.assertRaises(SystemExit): actualizar_correo.main()
        self.assertIn('token',self.call('/login',dict(identifier='coord@example.test',password='OriginalClave123')))


class MigrationTests(unittest.TestCase):
    def test_migration_twice_preserves_old_data(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = s.DB
            try:
                s.DB = Path(directory) / 'old.db'
                with sqlite3.connect(s.DB) as db:
                    db.execute('CREATE TABLE users (id TEXT PRIMARY KEY,name TEXT,email TEXT UNIQUE,role TEXT,salt TEXT,password TEXT)')
                    salt = 'aa'*16
                    db.execute('INSERT INTO users VALUES (?,?,?,?,?,?)',('old','Diego','diego@example.test','coordinator',salt,s.password_hash('OriginalClave123',salt)))
                db.close()
                s.initialize(); s.initialize()
                user = s.dispatch('POST','/login',dict(identifier='diego@example.test',password='OriginalClave123'),'')['user']
                self.assertEqual('old', user['id']); self.assertEqual(0,user['email_verified'])
            finally: s.DB = previous


class SmtpTests(unittest.TestCase):
    def test_ssl_uses_certificate_validation_and_sender(self):
        config = dict(host='smtp.gmail.com',port=465,security='ssl',username='sender@example.test',**{'from':'sender@example.test'})
        with patch.object(mailer,'settings',return_value=(config,'app-password')), patch.object(mailer.smtplib,'SMTP_SSL') as smtp:
            smtp.return_value.__enter__.return_value.send_message.return_value = {}
            mailer.send('tech@example.test','Prueba','Mensaje')
            self.assertTrue(smtp.call_args.kwargs['context'].check_hostname)
            client = smtp.return_value.__enter__.return_value
            client.login.assert_called_once_with('sender@example.test','app-password')
            self.assertEqual('tech@example.test',client.send_message.call_args.args[0]['To'])


if __name__ == '__main__': unittest.main()
