param([string]$ApkPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$expectedCommit = '56194e3d317a407c452dfc243722877bf552cf83'
$expectedHash = '6b5579e93f50a62f4d96279683bb447325e5a4de9015deae787eee56c248211f'
$sourceUrl = 'https://github.com/AngelAuraMC/Amethyst-Android/releases/download/1.1.7/Amethyst.apk'
& git -c "safe.directory=$repoRoot" -C $repoRoot merge-base --is-ancestor $expectedCommit HEAD
if ($LASTEXITCODE -ne 0) { throw 'The pinned Amethyst 1.1.7 base is not present in this source history.' }
$nativeInputs = @('app_pojavlauncher/src/main/jni', 'app_pojavlauncher/libs',
    'jre_lwjgl3glfw', 'methods_injector_agent', 'arc_dns_injector', 'forge_installer')
$changedEngine = @(& git -c "safe.directory=$repoRoot" -C $repoRoot diff --name-only $expectedCommit -- @nativeInputs)
if ($LASTEXITCODE -ne 0 -or $changedEngine.Count -gt 0) {
    throw 'Native engine inputs differ from 1.1.7. Build these components from source instead of using prebuilt mode.'
}
$vendorRoot = Join-Path $repoRoot '.vendor'
New-Item -ItemType Directory -Force -Path $vendorRoot | Out-Null
if (!$ApkPath) {
    $ApkPath = Join-Path $vendorRoot 'Amethyst-1.1.7.apk'
    if (!(Test-Path -LiteralPath $ApkPath)) { Invoke-WebRequest -Uri $sourceUrl -OutFile $ApkPath }
}
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
if ((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Upstream APK SHA-256 mismatch. No engine files extracted.'
}
$engineRoot = Join-Path $vendorRoot 'amethyst-1.1.7'
$aarNatives = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($aar in Get-ChildItem -LiteralPath (Join-Path $repoRoot 'app_pojavlauncher/libs') -Filter '*.aar') {
    $zip = [IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -match '^jni/([^/]+/[^/]+\.so)$') { [void]$aarNatives.Add($Matches[1]) }
        }
    } finally { $zip.Dispose() }
}
$zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
$nativeCount = 0
$assetCount = 0
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $relative = $null
        if ($entry.FullName.StartsWith('assets/')) {
            $relative = $entry.FullName
            $assetCount++
        } elseif ($entry.FullName -match '^lib/((?:arm64-v8a|armeabi-v7a|x86|x86_64)/([^/]+\.so))$') {
            $nativePath = $Matches[1]
            $nativeName = $Matches[2]
            # These binaries already come from pinned Gradle/AAR dependencies.
            if ($aarNatives.Contains($nativePath) -or $nativeName -in @('libjnidispatch.so', 'libbytehook.so')) { continue }
            $relative = 'jniLibs/' + $nativePath
            $nativeCount++
        }
        if (!$relative) { continue }
        $destination = [IO.Path]::GetFullPath((Join-Path $engineRoot $relative))
        if (!$destination.StartsWith($engineRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unsafe engine archive path'
        }
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    }
} finally { $zip.Dispose() }
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    if (!(Test-Path -LiteralPath (Join-Path $engineRoot "jniLibs/$abi/libpojavexec.so"))) {
        throw "Missing native engine for $abi"
    }
}
$metadata = [ordered]@{
    upstream = 'AngelAuraMC/Amethyst-Android'
    version = '1.1.7'
    commit = $expectedCommit
    sourceUrl = $sourceUrl
    apkSha256 = $expectedHash
    nativeFiles = $nativeCount
    assetFiles = $assetCount
    mode = 'Pinned upstream native engine and runtime assets; app Java/resources built from source'
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $engineRoot 'engine-source.json') -Encoding utf8
$metadata | ConvertTo-Json
