# Imports the Authenticode certificate (WINDOWS_CERTIFICATE = base64 .pfx) into the runner's
# user store and writes a Tauri config override with its thumbprint, so the bundler signs
# FlowLens.exe and the installers. For Azure Trusted Signing, use bundle.windows.signCommand instead.
$ErrorActionPreference = 'Stop'
$pfx = Join-Path $env:RUNNER_TEMP 'cert.pfx'
[IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_CERTIFICATE))
$password = ConvertTo-SecureString -String $env:WINDOWS_CERTIFICATE_PASSWORD -Force -AsPlainText
$cert = Import-PfxCertificate -FilePath $pfx -CertStoreLocation Cert:\CurrentUser\My -Password $password
Remove-Item $pfx
$config = @{ bundle = @{ windows = @{
  certificateThumbprint = $cert.Thumbprint
  digestAlgorithm = 'sha256'
  timestampUrl = 'http://timestamp.digicert.com'
} } } | ConvertTo-Json -Depth 5
Set-Content -Path 'src-tauri/tauri.windows-signing.conf.json' -Value $config -Encoding utf8
Write-Host "Windows signing configured with certificate $($cert.Subject)"
