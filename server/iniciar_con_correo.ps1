$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$nexoSecret = Read-Host 'Contraseña de aplicación de Gmail (no la contraseña normal)' -AsSecureString
$nexoPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($nexoSecret)
try {
    $env:NEXOVISITA_SMTP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($nexoPointer).Replace(' ', '')
    py -3 server.py serve @args
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($nexoPointer)
    Remove-Item Env:NEXOVISITA_SMTP_PASSWORD -ErrorAction SilentlyContinue
}
