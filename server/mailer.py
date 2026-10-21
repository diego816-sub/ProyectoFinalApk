"""SMTP con TLS obligatorio. Credenciales solo en el servidor; nunca se registran."""
import json
import os
import smtplib
import ssl
from email.message import EmailMessage
from pathlib import Path

CONFIG = Path(__file__).with_name('mail_config.json')


def settings():
    mode = os.environ.get("NEXOVISITA_MAIL", "smtp")
    if mode == "gmail_api":
        import gmail_api
        return gmail_api.settings()
    if mode != "smtp":
        raise RuntimeError("Proveedor de correo inválido.")
    try:
        data = json.loads(CONFIG.read_text(encoding='utf-8')) if CONFIG.exists() else {
            'host': os.environ.get('NEXOVISITA_SMTP_HOST','smtp.gmail.com'),
            'port': os.environ.get('NEXOVISITA_SMTP_PORT','587'),
            'security': os.environ.get('NEXOVISITA_SMTP_SECURITY','starttls'),
            'username': os.environ.get('NEXOVISITA_SMTP_USER',''),
            'from': os.environ.get('NEXOVISITA_SMTP_FROM',os.environ.get('NEXOVISITA_SMTP_USER',''))
        }
        password = os.environ.get('NEXOVISITA_SMTP_PASSWORD', '')
        if not all(data.get(k) for k in ('host', 'username', 'from')) or not password:
            raise ValueError()
        if data.get('security') not in ('ssl', 'starttls'):
            raise ValueError()
        if not 1 <= int(data['port']) <= 65535:
            raise ValueError()
        return data, password
    except (OSError, ValueError, KeyError, TypeError):
        raise RuntimeError('Configura mail_config.json y la contraseña de aplicación del remitente. Consulta CORREO_Y_ACTUALIZACION.md.') from None


def send(recipient, subject, body):
    config, password = settings()
    msg = EmailMessage()
    msg['From'] = config['from']
    msg['To'] = recipient
    msg['Subject'] = subject
    msg.set_content(body)
    if os.environ.get("NEXOVISITA_MAIL") == "gmail_api":
        import gmail_api
        return gmail_api.send(msg)
    context = ssl.create_default_context()
    transport = smtplib.SMTP_SSL if config['security'] == 'ssl' else smtplib.SMTP
    kwargs = {'timeout': 8}
    if config['security'] == 'ssl':
        kwargs['context'] = context
    with transport(config['host'], int(config['port']), **kwargs) as smtp:
        if config['security'] == 'starttls':
            smtp.ehlo()
            smtp.starttls(context=context)
            smtp.ehlo()
        smtp.login(config['username'], password)
        refused = smtp.send_message(msg)
        if refused:
            raise RuntimeError('El proveedor no aceptó el destinatario.')
