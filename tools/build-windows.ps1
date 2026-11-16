<#
.SYNOPSIS
Taen: one-command build for Windows (PowerShell 5.1 or 7, no administrator rights).

.DESCRIPTION
Downloads a pinned Temurin JDK 21 and the Android command-line tools into ONE folder (default C:\taen-build), installs the pinned
SDK packages, builds the unsigned release APK with gradlew.bat, prints its SHA-256 and cleans up. Your own JDK, Android SDK,
environment variables and PATH are never changed (the variables are set for the build processes only).
Run from a PowerShell prompt, in the source folder (short path such as C:\taen recommended):
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1 -DryRun
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1
Documentation: BUILDING.md, section "One-command build". Linux: tools/build-linux.sh (tests: tools/test-build-scripts.sh).

.PARAMETER Prefix           Where the toolchain goes (default C:\taen-build, about 6 GB, 10 GB free disk needed; no spaces).
.PARAMETER DryRun           Print the plan and the checks; download and change nothing.
.PARAMETER AcceptLicenses   Accept the Android SDK licences without asking (otherwise you read and answer them).
.PARAMETER WithAdb          Also install platform-tools (adb) into the folder.
.PARAMETER Keygen           After the build create your signing key (keytool, outside the repository; you type the password).
.PARAMETER Sign             After the build sign the APK (apksigner; you type the password).
.PARAMETER NoBuild          Do not build; only install what keygen/sign need and run them on the existing dist APK.
.PARAMETER RemoveToolchain  At the end delete the whole prefix (JDK, SDK, Gradle cache).
.PARAMETER Help             Show this help.
#>
[CmdletBinding()]
param(
    [string]$Prefix = '',
    [switch]$DryRun,
    [switch]$AcceptLicenses,
    [switch]$WithAdb,
    [switch]$Keygen,
    [switch]$Sign,
    [switch]$NoBuild,
    [switch]$RemoveToolchain,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow in Windows PowerShell 5.1 with a progress bar

if ($Help) { Get-Help $MyInvocation.MyCommand.Path -Detailed; exit 0 }

# ---- Pins. Every archive is checked against its SHA-256 before it is unpacked. Re-pin only from the official source. ----
# Temurin JDK 21 (Eclipse Adoptium). SHA-256 from https://api.adoptium.net/v3/assets/latest/21/hotspot (package.checksum), equal to the
# <archive>.sha256.txt next to the file on https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9 (checked 2026-11-14).
$JdkVersion   = '21.0.13+9'
$JdkUrl       = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip'
$JdkSha256    = 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9'
# Android command-line tools 19.0 (build 13114758). Google publishes only a SHA-1 (54a582f3bf73e04253602f2d1c80bd5868aac115 in
# https://dl.google.com/android/repository/repository2-3.xml, equal to the downloaded file); the SHA-256 is of the same file.
# NOT the newest 23.0: its sdkmanager downloads the "Android CLI" (usage metrics on unless --no-metrics) and accepts the SDK licences
# without asking (observed on the Linux build 2026-11-14), which breaks "no telemetry".
$CmdlineBuild  = '13114758'
$CmdlineUrl    = 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
$CmdlineSha256 = '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003'
# SDK packages installed by sdkmanager: the versions the build files pin (compileSdk 37, buildToolsVersion, ndkVersion).
$SdkPlatform     = 'platforms;android-37.0'
$SdkBuildTools   = 'build-tools;36.0.0'
$SdkNdk          = 'ndk;28.0.13004108'
$SdkPlatformTools = 'platform-tools'   # adb only, -WithAdb; not part of the APK, version not pinned
$MinDiskBytes    = 10GB

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$IsWin = ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT)
$CurrentStep = 'start'
$CreatedLocalProperties = $false
$SignedApk = ''

function Write-Step([string]$Text) { $script:CurrentStep = $Text; Write-Host ''; Write-Host "==> $Text" }
function Write-Info([string]$Text) { Write-Host $Text }
function Write-Warn([string]$Text) { Write-Warning $Text }
function Stop-Build([string]$Text) { throw $Text }

function Get-Sha256([string]$Path) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }

function Test-Sha256([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path)) { Stop-Build "$Path not found" }
    $got = Get-Sha256 $Path
    if ($got -eq $Expected) { Write-Info "sha256 OK: $(Split-Path -Leaf $Path)"; return }
    Remove-Item -LiteralPath $Path -Force
    Stop-Build "SHA-256 mismatch for $(Split-Path -Leaf $Path): expected $Expected, got $got. The file was deleted."
}

function Get-VersionName {
    $line = Select-String -LiteralPath (Join-Path $Root 'app\build.gradle.kts') -Pattern 'versionName = "(.*)"' | Select-Object -First 1
    if (-not $line) { Stop-Build 'cannot read versionName from app\build.gradle.kts' }
    return $line.Matches[0].Groups[1].Value
}

# A prefix may be deleted by -RemoveToolchain: absolute, no spaces, outside the repository and not containing it, not a drive root or
# the user profile, and new, empty or marked by an earlier run of this script.
function Test-PrefixSafe([string]$P) {
    if (-not [System.IO.Path]::IsPathRooted($P)) { Stop-Build "the prefix must be an absolute path: $P" }
    if ($P -match '\s') { Stop-Build "spaces in the prefix are not supported: $P (use for example C:\taen-build)" }
    $full = [System.IO.Path]::GetFullPath($P).TrimEnd('\', '/')
    $repo = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $userHome = [System.IO.Path]::GetFullPath($HOME).TrimEnd('\', '/')
    $sep = [string][System.IO.Path]::DirectorySeparatorChar
    if ($full -eq [System.IO.Path]::GetPathRoot($full).TrimEnd('\', '/') -or $full -eq $userHome -or $full.Length -lt 4) { Stop-Build "refusing $P as a toolchain folder" }
    if (($repo + $sep).StartsWith($full + $sep, [StringComparison]::OrdinalIgnoreCase)) { Stop-Build 'the prefix must not be or contain the source folder' }
    if (($full + $sep).StartsWith($repo + $sep, [StringComparison]::OrdinalIgnoreCase)) { Stop-Build 'the prefix must not be inside the source folder' }
    if (Test-Path -LiteralPath $full -PathType Leaf) { Stop-Build "$P exists and is not a folder" }
    if ((Test-Path -LiteralPath $full -PathType Container) -and -not (Test-Path -LiteralPath (Join-Path $full '.taen-build-prefix'))) {
        if (@(Get-ChildItem -LiteralPath $full -Force).Count -gt 0) { Stop-Build "$P is not empty and was not created by this script; choose another -Prefix" }
    }
    return $full
}

function Get-FreeBytes([string]$P) {
    try {
        $drive = New-Object System.IO.DriveInfo ([System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($P)))
        return [int64]$drive.AvailableFreeSpace
    } catch { return $null }
}

function Remove-Tree([string]$P) {
    if (-not (Test-Path -LiteralPath $P)) { return }
    try { Remove-Item -LiteralPath $P -Recurse -Force }
    catch {
        if ($IsWin) { & cmd.exe /c rd /s /q "$P"; if (Test-Path -LiteralPath $P) { throw } } else { throw }
    }
}

function Invoke-Cleanup([string]$P, [bool]$Remove) {
    if (-not (Test-Path -LiteralPath $P)) { return }
    if (-not (Test-Path -LiteralPath (Join-Path $P '.taen-build-prefix'))) { Stop-Build "$P was not created by this script, not deleting anything there" }
    Remove-Tree (Join-Path $P 'downloads'); Remove-Tree (Join-Path $P 'tmp')
    if ($Remove) {
        [void](Test-PrefixSafe $P)
        Remove-Tree $P
        Write-Info "removed the whole toolchain folder $P"
    } else {
        Write-Info "removed downloaded archives and temp files (the toolchain stays in $P for the next build)"
    }
}

# ---- Environment for child processes only: saved here, restored in the finally block; the system and user environment are never written. ----
$SavedEnv = @{}
$EnvNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'PATH')
function Enter-BuildEnv([string]$P) {
    foreach ($n in $EnvNames) { $script:SavedEnv[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }
    foreach ($n in @('ANDROID_SDK_ROOT', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) { [Environment]::SetEnvironmentVariable($n, $null, 'Process') }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', (Join-Path $P 'jdk'), 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', (Join-Path $P 'android-sdk'), 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_USER_HOME', (Join-Path $P 'android-user'), 'Process')
    [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', (Join-Path $P 'gradle-home'), 'Process')
    [Environment]::SetEnvironmentVariable('PATH', ((Join-Path $P 'jdk\bin') + [System.IO.Path]::PathSeparator + $SavedEnv['PATH']), 'Process')
}
function Exit-BuildEnv {
    foreach ($n in $SavedEnv.Keys) { [Environment]::SetEnvironmentVariable($n, $SavedEnv[$n], 'Process') }
}

# Native commands do not stop on a non-zero exit under $ErrorActionPreference = 'Stop': check the exit code. Windows PowerShell 5.1 also turns
# stderr text of a redirected native command into errors, so the preference is relaxed for the call itself.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $Exe @Arguments } finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) { Stop-Build "$Exe failed with exit code $LASTEXITCODE" }
}

# The Android CLI that sdkmanager 23.0 installs (usage metrics) leaves analytics.settings or a cli folder in ANDROID_USER_HOME: fail closed
# (the same rule as taen_assert_no_android_cli in tools/build-linux.sh).
function Assert-NoAndroidCli([string]$UserHome) {
    if ((Test-Path -LiteralPath (Join-Path $UserHome 'analytics.settings')) -or (Test-Path -LiteralPath (Join-Path $UserHome 'cli'))) {
        Stop-Build "sdkmanager installed the Android CLI (usage metrics) in $UserHome. Re-pin the command-line tools to a version whose sdkmanager does not (BUILDING.md section 3.1)."
    }
}

function Install-SdkPackages([string]$Sdkmanager, [string]$SdkRoot, [string[]]$Packages, [string]$UserHome) {
    Invoke-Native $Sdkmanager (@("--sdk_root=$SdkRoot") + $Packages)
    Assert-NoAndroidCli $UserHome
}

function Save-Download([string]$Url, [string]$Dest, [string]$Sha256) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dest) | Out-Null
    Write-Info "downloading $Url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile "$Dest.part"
    Move-Item -LiteralPath "$Dest.part" -Destination $Dest -Force
    Test-Sha256 $Dest $Sha256
}

function Expand-Zip([string]$Zip, [string]$Dest) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dest)
}

function Show-Plan([string]$P, [string]$Version, [string]$AdbNote) {
    $n = 1
    $pk = "$SdkPlatform, $SdkBuildTools, $SdkNdk"
    if ($WithAdb) { $pk = "$pk, $SdkPlatformTools" }
    Write-Step 'Plan'
    Write-Info "Taen $Version, source: $Root"
    Write-Info "Everything is downloaded into $P. Your JDK, Android SDK, environment variables and PATH are not touched."
    Write-Info "$n. Download Temurin JDK ${JdkVersion}: $JdkUrl"; Write-Info "   expected SHA-256 $JdkSha256"; $n++
    Write-Info "$n. Download Android command-line tools ${CmdlineBuild}: $CmdlineUrl"; Write-Info "   expected SHA-256 $CmdlineSha256"; $n++
    if ($AcceptLicenses) { Write-Info "$n. Android SDK licences: accepted because of -AcceptLicenses" } else { Write-Info "$n. Android SDK licences: shown to you, you answer y or n" }; $n++
    Write-Info "$n. sdkmanager.bat installs exactly: $pk"; $n++
    if (-not $NoBuild) {
        Write-Info "$n. gradlew.bat --no-daemon --console=plain :app:assembleRelease with JAVA_HOME=$P\jdk ANDROID_HOME=$P\android-sdk GRADLE_USER_HOME=$P\gradle-home (this process only)"
        Write-Info '   (Gradle downloads its pinned distribution and the checksum-verified dependencies from services.gradle.org, Maven Central, Google Maven, jitpack.io)'; $n++
        Write-Info "$n. Copy the APK to dist\taen-$Version-unsigned.apk, write dist\SHA256SUMS, print the SHA-256"; $n++
    }
    if ($Keygen) { Write-Info "$n. keytool: your signing key outside the repository (you type the password)"; $n++ }
    if ($Sign) { Write-Info "$n. apksigner: dist\taen-$Version-signed.apk (you type the password)"; $n++ }
    Write-Info "$n. Cleanup: stop Gradle, remove downloaded archives and temp files"
    if ($RemoveToolchain) { Write-Info "   -RemoveToolchain: also delete the whole prefix $P" }
    Write-Info 'No telemetry. No download other than those listed.'
}

function Show-Summary([string]$Apk, [string]$Sha, [string]$Version, [string]$P) {
    $prefixArg = ''
    if ($P -ne (Join-Path $env:SystemDrive 'taen-build')) { $prefixArg = " -Prefix $P" }
    $target = $SignedApk; if (-not $target) { $target = '<your signed apk>' }
    $adb = 'adb'; $adbPath = Join-Path $P 'android-sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $adbPath) { $adb = "& '$adbPath'" }
    Write-Host @"

======================================================================
Taen $Version built.
  APK (unsigned): $Apk
  SHA-256:        $Sha

Compare with a published build
  Get-FileHash -Algorithm SHA256 $Apk      (dist\SHA256SUMS was written with this build's hash)
  A build on Linux with this source commit is reproducible; a Windows build is NOT guaranteed to give the same bytes
  (BUILDING.md section 5), so compare it with another Windows build, or build on Linux/WSL2 to compare with a published SHA256SUMS.

Sign (Android refuses an unsigned APK)
  Your key stays on your machine, outside the repository; you type its password, nothing is stored.
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1$prefixArg -NoBuild -Keygen -Sign    (first time: creates %USERPROFILE%\.config\taen\release.p12, then signs)
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1$prefixArg -NoBuild -Sign             (later builds: key exists)
  Write down the certificate SHA-256 it prints and keep two offline copies of the key: updates need the same key.
"@
    if ($SignedApk) { Write-Host "  Signed APK: $SignedApk" }
    Write-Host @"

Install on the phone (Android 11 or newer, 64-bit ARM)
  USB debugging on, phone connected:   $adb install -r $target
  or copy the signed APK to the phone, open it in the Files app and allow installs from that app.
  Note the current keyboard first:     $adb shell settings get secure default_input_method

Enable the keyboard
  $adb shell ime enable io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
  $adb shell ime set io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
  or on the phone: Settings > System > Languages & input > On-screen keyboard > Manage keyboards > Taen,
  then pick Taen with the keyboard switcher in any text field.

Voice typing (on the phone, optional; the app has no network permission and never downloads anything)
  Open the Taen app, then:
  1. Settings > Voice typing > Download the model  (your browser downloads one file, about 487 MB, Wi-Fi)
  2. Settings > Voice typing > Install downloaded model  (in the picker select the downloaded file in Downloads; it is checked and unpacked on the phone)

More: BUILDING.md (checks, reproducibility, leak audit), PRIVACY.md.
======================================================================
"@
}

# Dot-sourced (tools/test-build-scripts.sh): only the functions above are defined, nothing runs.
if ($MyInvocation.InvocationName -eq '.') { return }

# ---- main ----
$exitCode = 0
try {
    $version = Get-VersionName
    Write-Step 'Checking this machine'
    if (-not $IsWin) {
        if ($DryRun) { Write-Warn 'this is not Windows: the plan is shown, but a real run needs Windows (on Linux use tools/build-linux.sh)' }
        else { Stop-Build 'this script is for Windows; on Linux use tools/build-linux.sh' }
    }
    $arch = $env:PROCESSOR_ARCHITEW6432; if (-not $arch) { $arch = $env:PROCESSOR_ARCHITECTURE }
    if ($IsWin -and $arch -ne 'AMD64') { Stop-Build "unsupported CPU $arch (64-bit x86 Windows only; ARM Windows: use WSL2 and tools/build-linux.sh)" }
    Write-Info "PowerShell $($PSVersionTable.PSVersion), CPU $arch"
    if (-not ((Test-Path -LiteralPath (Join-Path $Root 'gradlew.bat')) -and (Test-Path -LiteralPath (Join-Path $Root 'app\build.gradle.kts')))) {
        Stop-Build "run this from a complete Taen source tree (gradlew.bat or app\build.gradle.kts not found in $Root): clone it with Git or unpack the source zip"
    }
    if (Test-Path -LiteralPath (Join-Path $Root '.git')) { Write-Info 'source: Git checkout' } else { Write-Info 'source: archive without Git (fine: the build does not need Git)' }

    if (-not $Prefix) {
        if ($env:SystemDrive) { $Prefix = Join-Path $env:SystemDrive 'taen-build' } else { $Prefix = Join-Path $HOME '.cache/taen-build' }
    }
    if (-not [System.IO.Path]::IsPathRooted($Prefix)) { $Prefix = Join-Path (Get-Location).Path $Prefix }
    $Prefix = Test-PrefixSafe $Prefix

    $longPaths = $false
    try { $longPaths = ((Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' -ErrorAction Stop).LongPathsEnabled -eq 1) } catch { $longPaths = $false }
    if (-not $longPaths) {
        if ($Root.Length -gt 100) { Stop-Build "the source path is too long for Windows without long-path support ($($Root.Length) characters): move the source to a short folder such as C:\taen" }
        if ($Root.Length -gt 40 -or $Prefix.Length -gt 30) {
            Write-Warn "long paths are not enabled and the folders are long (source $($Root.Length), prefix $($Prefix.Length) characters). The build creates paths of 150 or more characters below them; if it fails with 'path too long' use a short folder such as C:\taen and -Prefix C:\taen-build."
        }
    }
    $free = Get-FreeBytes $Prefix
    if ($null -eq $free) {
        if ($DryRun) { Write-Warn 'cannot read the free disk space here (needs Windows)' } else { Stop-Build "cannot determine the free disk space at $Prefix (10 GiB are needed)" }
    } elseif ($free -lt $MinDiskBytes) {
        Stop-Build "need at least 10 GiB free disk where $Prefix goes (found $([int]($free / 1GB)) GiB); use -Prefix on a bigger disk"
    } else { Write-Info "free disk at the prefix: $([int]($free / 1GB)) GiB (needs 10)" }
    try {
        $memMb = [int]((Get-CimInstance -ClassName Win32_OperatingSystem).FreePhysicalMemory / 1024)
        if ($memMb -lt 6144) { Write-Warn "only $memMb MiB RAM free; the build wants about 6 GiB free (Gradle JVM about 2.5 GB, more during the native build). Close other programs." }
        else { Write-Info "RAM free: $memMb MiB (the build wants about 6 GiB free)" }
    } catch { Write-Info 'RAM: could not be read (the build wants about 6 GiB free)' }

    Show-Plan $Prefix $version ''
    if ($DryRun) { Write-Host ''; Write-Host 'DRY RUN: nothing was downloaded, installed or changed.'; return }

    $sdk = Join-Path $Prefix 'android-sdk'
    $jdk = Join-Path $Prefix 'jdk'
    $dl = Join-Path $Prefix 'downloads'
    $tmpDir = Join-Path $Prefix 'tmp'
    Write-Step "Preparing $Prefix"
    foreach ($d in @($dl, $tmpDir, (Join-Path $Prefix 'android-user'), (Join-Path $Prefix 'gradle-home'))) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    New-Item -ItemType File -Force -Path (Join-Path $Prefix '.taen-build-prefix') | Out-Null
    Enter-BuildEnv $Prefix

    Write-Step "Temurin JDK $JdkVersion"
    $pin = Join-Path $jdk '.taen-pin'
    if ((Test-Path -LiteralPath $pin) -and ((Get-Content -LiteralPath $pin -Raw).Trim() -eq $JdkSha256) -and (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe'))) {
        Write-Info "already installed in $jdk"
    } else {
        Save-Download $JdkUrl (Join-Path $dl 'jdk.zip') $JdkSha256
        $x = Join-Path $tmpDir 'jdk-extract'
        Remove-Tree $jdk; Remove-Tree $x
        Expand-Zip (Join-Path $dl 'jdk.zip') $x
        $top = @(Get-ChildItem -LiteralPath $x)
        if ($top.Count -ne 1) { Stop-Build 'unexpected JDK archive layout' }
        Move-Item -LiteralPath $top[0].FullName -Destination $jdk
        [System.IO.File]::WriteAllText($pin, $JdkSha256)
        Remove-Item -LiteralPath (Join-Path $dl 'jdk.zip') -Force
    }
    Invoke-Native (Join-Path $jdk 'bin\java.exe') @('-version')

    Write-Step "Android command-line tools $CmdlineBuild"
    $cl = Join-Path $sdk 'cmdline-tools\latest'
    $pin = Join-Path $cl '.taen-pin'
    if ((Test-Path -LiteralPath $pin) -and ((Get-Content -LiteralPath $pin -Raw).Trim() -eq $CmdlineSha256)) {
        Write-Info 'already installed'
    } else {
        Save-Download $CmdlineUrl (Join-Path $dl 'cmdline-tools.zip') $CmdlineSha256
        $x = Join-Path $tmpDir 'cmdline-extract'
        Remove-Tree $x; Remove-Tree $cl
        New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
        Expand-Zip (Join-Path $dl 'cmdline-tools.zip') $x
        if (-not (Test-Path -LiteralPath (Join-Path $x 'cmdline-tools'))) { Stop-Build 'unexpected command-line tools archive layout' }
        Move-Item -LiteralPath (Join-Path $x 'cmdline-tools') -Destination $cl
        [System.IO.File]::WriteAllText($pin, $CmdlineSha256)
        Remove-Item -LiteralPath (Join-Path $dl 'cmdline-tools.zip') -Force
    }

    $sm = Join-Path $cl 'bin\sdkmanager.bat'
    Write-Step 'Android SDK licences'
    $licFile = Join-Path $sdk 'licenses\android-sdk-license'
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        if ($AcceptLicenses) {
            $licText = Join-Path $Prefix 'android-sdk-licences.txt'
            Write-Info "accepting the licences because -AcceptLicenses was given; the text is saved in $licText"
            ((1..80) | ForEach-Object { 'y' }) | & $sm "--sdk_root=$sdk" --licenses *> $licText
        } elseif (-not [Console]::IsInputRedirected) {
            Write-Info 'sdkmanager shows each licence; answer y to accept it or n to stop.'
            & $sm "--sdk_root=$sdk" --licenses
        } else {
            & $sm "--sdk_root=$sdk" --licenses *> $null
        }
    } finally { $ErrorActionPreference = $old }
    if (-not (Test-Path -LiteralPath $licFile)) { Stop-Build 'the Android SDK licences are not accepted. Run in a PowerShell window and answer y, or add -AcceptLicenses.' }

    Write-Step 'Android SDK packages'
    if ($NoBuild) { $pkgs = @($SdkBuildTools) } else { $pkgs = @($SdkPlatform, $SdkBuildTools, $SdkNdk) }
    if ($WithAdb) { $pkgs += $SdkPlatformTools }
    Install-SdkPackages $sm $sdk $pkgs (Join-Path $Prefix 'android-user')

    $unsigned = Join-Path $Root "dist\taen-$version-unsigned.apk"
    $apk = ''; $apkSha = ''
    if (-not $NoBuild) {
        Write-Step 'Building the unsigned release APK (about 10 to 20 minutes the first time)'
        $lp = Join-Path $Root 'local.properties'
        $line = 'sdk.dir=' + ($sdk -replace '\\', '/')
        if (Test-Path -LiteralPath $lp) {
            $cur = @(Get-Content -LiteralPath $lp | Where-Object { $_ -like 'sdk.dir=*' })
            if (-not ($cur.Count -ge 1 -and $cur[0].Trim() -eq $line)) {
                Stop-Build "local.properties already sets sdk.dir, and Gradle prefers it over ANDROID_HOME. Rename it (ren local.properties local.properties.bak), run this script, then rename it back. Or build from a fresh clone."
            }
        } else {
            [System.IO.File]::WriteAllText($lp, "$line`n")
            $CreatedLocalProperties = $true
            Write-Info "wrote local.properties (git-ignored) with $line"
        }
        Push-Location $Root
        try { Invoke-Native (Join-Path $Root 'gradlew.bat') @('--no-daemon', '--console=plain', ':app:assembleRelease') } finally { Pop-Location }
        $built = Join-Path $Root "app\build\outputs\apk\release\Taen_$version-release.apk"
        if (-not (Test-Path -LiteralPath $built)) { Stop-Build "build finished but $built was not found" }
        New-Item -ItemType Directory -Force -Path (Join-Path $Root 'dist') | Out-Null
        Copy-Item -LiteralPath $built -Destination $unsigned -Force
        $apk = $unsigned; $apkSha = Get-Sha256 $unsigned
        [System.IO.File]::WriteAllText((Join-Path $Root 'dist\SHA256SUMS'), "$apkSha  taen-$version-unsigned.apk`n")
        Write-Info "APK: $apk"
        Write-Info "SHA-256: $apkSha"
    } elseif (Test-Path -LiteralPath $unsigned) { $apk = $unsigned; $apkSha = Get-Sha256 $unsigned }

    $ks = $env:TAEN_KEYSTORE; if (-not $ks) { $ks = Join-Path $HOME '.config\taen\release.p12' }
    $alias = $env:TAEN_KEY_ALIAS; if (-not $alias) { $alias = 'taen' }
    $ksFull = [System.IO.Path]::GetFullPath($ks)
    if ($Keygen -or $Sign) {
        $repoPrefix = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
        if ($ksFull.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) { Stop-Build 'the keystore must not be inside the repository' }
    }
    if ($Keygen) {
        Write-Step 'Creating your signing key (keytool)'
        if (Test-Path -LiteralPath $ksFull) { Write-Warn "$ksFull already exists and is never overwritten; skipping key creation" }
        else {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ksFull) | Out-Null
            Invoke-Native (Join-Path $jdk 'bin\keytool.exe') @('-genkeypair', '-alias', $alias, '-keyalg', 'RSA', '-keysize', '4096', '-validity', '36500', '-storetype', 'PKCS12', '-keystore', $ksFull)
            Write-Info "created $ksFull (keep two offline copies; it stays in your user profile folder)"
        }
    }
    if ($Sign) {
        Write-Step 'Signing (apksigner)'
        $signed = Join-Path $Root "dist\taen-$version-signed.apk"
        $bt = $SdkBuildTools.Split(';')[1]
        $apksigner = Join-Path $sdk "build-tools\$bt\apksigner.bat"
        if (-not (Test-Path -LiteralPath $unsigned)) { Write-Warn "$unsigned not found: build first (run without -NoBuild)" }
        elseif (-not (Test-Path -LiteralPath $ksFull)) { Write-Warn "keystore $ksFull not found: add -Keygen to create one" }
        else {
            Invoke-Native $apksigner @('sign', '--ks', $ksFull, '--ks-key-alias', $alias, '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', $signed, $unsigned)
            Invoke-Native $apksigner @('verify', '--verbose', $signed)
            $certs = & $apksigner verify --print-certs $signed
            $certs | Where-Object { $_ -like 'Signer #1 certificate SHA-256*' } | ForEach-Object { Write-Info "certificate SHA-256: $(($_ -split ': ')[1])" }
            $SignedApk = $signed
            $signedSha = Get-Sha256 $signed
            [System.IO.File]::AppendAllText((Join-Path $Root 'dist\SHA256SUMS'), "$signedSha  taen-$version-signed.apk`n")
        }
    }

    Write-Step 'Cleaning up'
    Write-Info 'stopping Gradle'
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { Push-Location $Root; & (Join-Path $Root 'gradlew.bat') --stop *> $null } catch { } finally { Pop-Location; $ErrorActionPreference = $old }
    if ($RemoveToolchain -and $CreatedLocalProperties) { Remove-Item -LiteralPath (Join-Path $Root 'local.properties') -Force }
    Exit-BuildEnv
    Invoke-Cleanup $Prefix ([bool]$RemoveToolchain)
    if ($apk) { Show-Summary $apk $apkSha $version $Prefix }
    else { Write-Info "No APK was built (-NoBuild and no dist\taen-$version-unsigned.apk)." }
    if ($CreatedLocalProperties -and -not $RemoveToolchain) {
        Write-Info "Note: local.properties (git-ignored) now points Gradle at $sdk; delete it if you remove the folder yourself."
    }
} catch {
    $exitCode = 1
    Write-Host ''
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "FAILED during: $CurrentStep"
    Write-Host 'What to do:'
    Write-Host "  1. Read the error above. Nothing outside $Prefix was changed."
    Write-Host '  2. Fix the cause (network, disk space, long paths, antivirus holding files) and run the same command again: finished steps are kept and checked again.'
    Write-Host "  3. For a clean start delete the folder $Prefix (or run with -RemoveToolchain after a good build) and start again."
    Write-Host '  4. A SHA-256 mismatch means the download was damaged or changed: do not use it; try again later and ask before changing a pin.'
    Write-Host '  5. "path too long" errors: move the source to a short folder such as C:\taen and use -Prefix C:\taen-build.'
    try { Exit-BuildEnv; if ($Prefix -and (Test-Path -LiteralPath (Join-Path $Prefix '.taen-build-prefix'))) { Invoke-Cleanup $Prefix $false | Out-Null } } catch { }
} finally {
    Exit-BuildEnv
}
exit $exitCode
