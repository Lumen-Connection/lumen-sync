param(
    [string]$Binary = "",
    [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Binary)) {
    $onWindows = $IsWindows -or $env:OS -eq "Windows_NT"
    $executable = if ($onWindows) { "syncthing.exe" } else { "syncthing" }
    $resourcePlatform = if ($onWindows) { "windows" } else { "linux" }
    $Binary = Join-Path $projectRoot "composeApp/src/desktopMain/appResources/$resourcePlatform/$executable"
}
$Binary = (Resolve-Path -LiteralPath $Binary).Path
$testRoot = Join-Path $projectRoot ".cache/integration/$([Guid]::NewGuid().ToString('N'))"
$null = New-Item -ItemType Directory -Path $testRoot -Force

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function New-TestNode([string]$Name, [int]$SyncPort) {
    $nodeHome = Join-Path $testRoot $Name
    $folder = Join-Path $nodeHome "folder"
    $null = New-Item -ItemType Directory -Path $folder -Force
    $guiPort = Get-FreePort
    $apiKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $arguments = @(
        "serve",
        "--home=$nodeHome",
        "--gui-address=http://127.0.0.1:$guiPort",
        "--gui-apikey=$apiKey",
        "--no-browser",
        "--no-restart",
        "--no-upgrade",
        "--log-file=$(Join-Path $nodeHome 'syncthing.log')"
    )
    $start = @{
        FilePath = $Binary
        ArgumentList = $arguments
        PassThru = $true
    }
    if ($IsWindows -or $env:OS -eq "Windows_NT") { $start.WindowStyle = "Hidden" }
    $process = Start-Process @start
    return [pscustomobject]@{
        Name = $Name
        BaseUrl = "http://127.0.0.1:$guiPort"
        ApiKey = $apiKey
        SyncPort = $SyncPort
        NodeHome = $nodeHome
        Folder = $folder
        Process = $process
    }
}

function Invoke-NodeApi($Node, [string]$Method, [string]$Path, $Body = $null) {
    $parameters = @{
        Method = $Method
        Uri = "$($Node.BaseUrl)$Path"
        Headers = @{ "X-API-Key" = $Node.ApiKey }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 30
    }
    return Invoke-RestMethod @parameters
}

function Wait-Api($Node) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            if ((Invoke-NodeApi $Node GET "/rest/system/ping").ping -eq "pong") { return }
        } catch { Start-Sleep -Milliseconds 250 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$($Node.Name) did not expose its API"
}

function Set-NodeOptions($Node) {
    Invoke-NodeApi $Node PATCH "/rest/config/options" @{
        listenAddresses = @("tcp://127.0.0.1:$($Node.SyncPort)")
        globalAnnounceEnabled = $false
        localAnnounceEnabled = $false
        relaysEnabled = $false
        urAccepted = -1
    } | Out-Null
}

function Add-RemoteDevice($Node, [string]$RemoteId, [string]$RemoteName, [int]$RemotePort) {
    $device = Invoke-NodeApi $Node GET "/rest/config/defaults/device"
    $device.deviceID = $RemoteId
    $device.name = $RemoteName
    $device.addresses = @("tcp://127.0.0.1:$RemotePort")
    $device.introducer = $false
    Invoke-NodeApi $Node POST "/rest/config/devices" $device | Out-Null
}

function Add-SharedFolder($Node, [string]$FolderId, [string[]]$DeviceIds) {
    $folder = Invoke-NodeApi $Node GET "/rest/config/defaults/folder"
    $folder.id = $FolderId
    $folder.label = "Lumen Sync integration test"
    $folder.path = $Node.Folder
    $folder.type = "sendreceive"
    $folder.fsWatcherEnabled = $true
    $folder.rescanIntervalS = 1
    $folder.versioning = @{ type = "" }
    $folder.devices = @($DeviceIds | ForEach-Object { @{ deviceID = $_ } })
    Invoke-NodeApi $Node POST "/rest/config/folders" $folder | Out-Null
}

function Wait-File([string]$Path, [string]$Expected = "") {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-Path -LiteralPath $Path) {
            if ($Expected -eq "" -or (Get-Content -LiteralPath $Path -Raw) -eq $Expected) { return }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Path"
}

function Wait-Deleted([string]$Path) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (-not (Test-Path -LiteralPath $Path)) { return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for deletion of $Path"
}

$syncPortA = Get-FreePort
$syncPortB = Get-FreePort
$nodeA = $null
$nodeB = $null
try {
    $nodeA = New-TestNode "alpha" $syncPortA
    Wait-Api $nodeA
    Set-NodeOptions $nodeA

    $nodeB = New-TestNode "beta" $syncPortB
    Wait-Api $nodeB
    Set-NodeOptions $nodeB

    $idA = (Invoke-NodeApi $nodeA GET "/rest/system/status").myID
    $idB = (Invoke-NodeApi $nodeB GET "/rest/system/status").myID
    Add-RemoteDevice $nodeA $idB "Beta" $syncPortB
    Add-RemoteDevice $nodeB $idA "Alpha" $syncPortA
    Add-SharedFolder $nodeA "smoke-23456" @($idA, $idB)
    Add-SharedFolder $nodeB "smoke-23456" @($idA, $idB)

    $fileA = Join-Path $nodeA.Folder "round-trip.txt"
    $fileB = Join-Path $nodeB.Folder "round-trip.txt"
    Set-Content -LiteralPath $fileA -Value "from-alpha" -NoNewline
    Invoke-NodeApi $nodeA POST "/rest/db/scan?folder=smoke-23456" | Out-Null
    Wait-File $fileB "from-alpha"

    Set-Content -LiteralPath $fileB -Value "from-beta" -NoNewline
    Invoke-NodeApi $nodeB POST "/rest/db/scan?folder=smoke-23456" | Out-Null
    Wait-File $fileA "from-beta"

    Remove-Item -LiteralPath $fileA
    Invoke-NodeApi $nodeA POST "/rest/db/scan?folder=smoke-23456" | Out-Null
    Wait-Deleted $fileB

    $keptAfterLeave = Join-Path $nodeB.Folder "kept-after-leave.txt"
    Set-Content -LiteralPath $keptAfterLeave -Value "stays-local" -NoNewline
    Invoke-NodeApi $nodeB DELETE "/rest/config/devices/$idA" | Out-Null
    Invoke-NodeApi $nodeB DELETE "/rest/config/folders/smoke-23456" | Out-Null
    Wait-File $keptAfterLeave "stays-local"

    $remainingFolders = @(Invoke-NodeApi $nodeB GET "/rest/config/folders")
    if ($remainingFolders | Where-Object { $_.id -eq "smoke-23456" }) {
        throw "The leaving device still has the old folder configured"
    }

    $newFolder = Join-Path $nodeB.NodeHome "new-folder"
    $null = New-Item -ItemType Directory -Path $newFolder -Force
    $nodeB.Folder = $newFolder
    Add-SharedFolder $nodeB "fresh-34567" @($idB)
    $newFolders = @(Invoke-NodeApi $nodeB GET "/rest/config/folders")
    if (-not ($newFolders | Where-Object { $_.id -eq "fresh-34567" })) {
        throw "The leaving device could not configure a new local space"
    }

    Write-Host "Syncthing integration smoke passed: create, modify, delete, leave, and reconfigure."
} finally {
    foreach ($node in @($nodeA, $nodeB)) {
        if ($null -eq $node) { continue }
        try { Invoke-NodeApi $node POST "/rest/system/shutdown" | Out-Null } catch {}
        if (-not $node.Process.WaitForExit(5000)) { Stop-Process -Id $node.Process.Id -Force }
    }
}
