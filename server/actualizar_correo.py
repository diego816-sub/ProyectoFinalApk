"""Corrige el correo de una cuenta anterior sin verificar, desde la PC del responsable."""
import getpass
import hmac
import re
import sqlite3
import server as s


def main():
    if not s.DB.exists():
        raise SystemExit('No existe nexovisita.db en esta carpeta. Copia primero tu base anterior.')
    s.initialize()
    print('Detén el servidor antes de continuar. Conserva un respaldo de nexovisita.db.')
    old = input('Correo actual de la cuenta: ').strip().lower()
    password = getpass.getpass('Contraseña actual de NexoVisita: ').strip()
    new = input('Correo real que quieres verificar: ').strip().lower()
    if not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", new):
        raise SystemExit('Correo inválido.')
    with s.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        user = db.execute('SELECT * FROM users WHERE email=?', (old,)).fetchone()
        if not user or not hmac.compare_digest(user['password'], s.password_hash(password, user['salt'])):
            raise SystemExit('Correo o contraseña incorrectos.')
        if user['email_verified']:
            raise SystemExit('Esta herramienta solo corrige cuentas anteriores sin verificar.')
        try:
            db.execute('UPDATE users SET email=? WHERE id=?', (new, user['id']))
        except sqlite3.IntegrityError:
            raise SystemExit('El correo ya pertenece a otra cuenta.') from None
        db.execute('DELETE FROM sessions WHERE user_id=?', (user['id'],))
        db.execute('DELETE FROM email_codes WHERE user_id=?', (user['id'],))
        db.execute('DELETE FROM code_limits WHERE user_id=?', (user['id'],))
    print('Correo actualizado. Reinicia el servidor y usa Activar / verificar cuenta en la aplicación.')


if __name__ == '__main__': main()
