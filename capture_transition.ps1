param (
    [int]$Duration = 5,
    [string]$Device = '192.168.1.4:5555',
    [string]$OutputDir = 'captured_frames'
)

Write-Host '==============================================' -ForegroundColor Cyan
Write-Host '   POCO F1 Screen & Frame Capture Tool       ' -ForegroundColor Cyan
Write-Host '==============================================' -ForegroundColor Cyan

# 1. Check device connection
Write-Host ('[1/4] Checking device (' + $Device + ')...') -ForegroundColor Yellow
$state = (adb -s $Device get-state 2>&1).ToString().Trim()
if ($state -ne 'device') {
    $foundDevice = (adb devices | Select-String 'device$' | Select-Object -First 1)
    if ($foundDevice) {
        $Device = $foundDevice.ToString().Split([char]9)[0].Trim()
        Write-Host ('Found connected device: ' + $Device) -ForegroundColor Green
    } else {
        Write-Host 'ERROR: No ADB device connected! Please connect device or check ADB.' -ForegroundColor Red
        exit 1
    }
}

# 2. Record video on device
$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$remotePath = '/sdcard/screen_record_' + $timestamp + '.mp4'
$localVideo = $OutputDir + '\video_' + $timestamp + '.mp4'
$framesFolder = $OutputDir + '\run_' + $timestamp

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}
New-Item -ItemType Directory -Force -Path $framesFolder | Out-Null

Write-Host ('
[2/4] RECORDING FOR ' + $Duration + ' SECONDS ON DEVICE...') -ForegroundColor Green
Write-Host '*** Perform your gesture / transition on phone NOW! ***
' -ForegroundColor Yellow

adb -s $Device shell screenrecord --time-limit $Duration $remotePath

# 3. Pull video to PC
Write-Host '
[3/4] Pulling video to PC...' -ForegroundColor Cyan
adb -s $Device pull $remotePath $localVideo
adb -s $Device shell rm $remotePath

# 4. Unpack frames with FFmpeg
Write-Host '[4/4] Unpacking frames with FFmpeg...' -ForegroundColor Cyan
$ffmpeg = 'C:\Android\ffmpeg.exe'
if (-not (Test-Path $ffmpeg)) {
    $ffmpegCmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($ffmpegCmd) {
        $ffmpeg = $ffmpegCmd.Source
    }
}

if (Test-Path $ffmpeg) {
    & $ffmpeg -y -i $localVideo -vf 'fps=30' ($framesFolder + '\frame_%04d.png') -loglevel error
    $frameCount = (Get-ChildItem -Path $framesFolder -Filter '*.png').Count
    Write-Host ('
SUCCESS! Extracted ' + $frameCount + ' frames to:') -ForegroundColor Green
    Write-Host ('  ' + $framesFolder) -ForegroundColor White
    
    explorer.exe (Resolve-Path $framesFolder).Path
} else {
    Write-Host ('FFmpeg not found at C:\Android\ffmpeg.exe! Video saved to ' + $localVideo) -ForegroundColor Yellow
}
