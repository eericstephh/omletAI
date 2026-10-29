$ErrorActionPreference = "Stop"
$rawDir = Join-Path $PSScriptRoot "app\src\main\res\raw"
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null

$rate = 44100

function Write-Wav {
    param([string]$Name, [double[]]$Samples)
    $n = $Samples.Count
    $dataBytes = New-Object byte[] ($n * 2)
    for ($i = 0; $i -lt $n; $i++) {
        $v = [int][math]::Max(-32768, [math]::Min(32767, [math]::Round($Samples[$i] * 32000)))
        $dataBytes[$i*2]   = $v -band 0xFF
        $dataBytes[$i*2+1] = (($v -shr 8) -band 0xFF)
    }
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([char[]]@('R','I','F','F'))
    $bw.Write([int](36 + $n * 2))
    $bw.Write([char[]]@('W','A','V','E','f','m','t',' '))
    $bw.Write([int]16)
    $bw.Write([int16]1)          # PCM
    $bw.Write([int16]1)          # mono
    $bw.Write([int]$rate)
    $bw.Write([int]($rate * 2))
    $bw.Write([int16]2)
    $bw.Write([int16]16)
    $bw.Write([char[]]@('d','a','t','a'))
    $bw.Write([int]($n * 2))
    $bw.Write($dataBytes)
    [System.IO.File]::WriteAllBytes((Join-Path $rawDir "$Name.wav"), $ms.ToArray())
    $bw.Close(); $ms.Close()
    Write-Host "wrote $Name.wav ($($n) samples)"
}

function New-Rng { New-Object System.Random(12345) }

# --- Whoosh: filtered noise with sweeping emphasis ---
$rng = New-Rng
$dur = 1.2; $n = [int]($dur * $rate); $s = New-Object double[] $n
$ema = 0.0
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $n
    $env = [math]::Pow([math]::Sin([math]::PI * $t), 2)
    $alpha = 0.02 + 0.30 * [math]::Abs($t - 0.5) * 2
    $ema += $alpha * (($rng.NextDouble() * 2 - 1) - $ema)
    $s[$i] = $ema * $env * 1.6
}
Write-Wav "sfx_whoosh" $s

# --- Ding: bright bell ---
$dur = 1.0; $n = [int]($dur * $rate); $s = New-Object double[] $n
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $env = [math]::Exp(-5 * $t)
    $s[$i] = $env * (0.7 * [math]::Sin(2*[math]::PI*1568*$t) + 0.25 * [math]::Sin(2*[math]::PI*2349*$t) + 0.15 * [math]::Sin(2*[math]::PI*3136*$t)) * 0.55
}
Write-Wav "sfx_ding" $s

# --- Pop ---
$dur = 0.18; $n = [int]($dur * $rate); $s = New-Object double[] $n
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $f = 500 - 350 * ($t / $dur)
    $env = [math]::Exp(-28 * $t)
    $s[$i] = $env * [math]::Sin(2*[math]::PI*$f*$t) * 0.9
}
Write-Wav "sfx_pop" $s

# --- Boing ---
$dur = 0.8; $n = [int]($dur * $rate); $s = New-Object double[] $n
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $p = $t / $dur
    $f = 750 * [math]::Exp(-2.2*$p) + 90
    $vib = 1 + 0.06 * [math]::Sin(2*[math]::PI*22*$t) * (1-$p)
    $env = [math]::Exp(-4.5*$t)
    $phase += 2*[math]::PI*$f*$vib/$rate
    $s[$i] = $env * [math]::Sin($phase) * 0.85
}
Write-Wav "sfx_boing" $s

# --- Drum roll ---
$rng = New-Rng
$dur = 1.4; $n = [int]($dur * $rate); $s = New-Object double[] $n
foreach ($start in (0..([int](($dur-0.05)/0.045)))) {
    $st = [int]($start * 0.045 * $rate)
    $len = [int](0.035 * $rate)
    for ($j = 0; $j -lt $len -and ($st+$j) -lt $n; $j++) {
        $env = [math]::Exp(-120 * $j / $rate)
        $s[$st+$j] += ($rng.NextDouble()*2-1) * $env * (0.35 + 0.45*($start/[int](($dur-0.05)/0.045)))
    }
}
$max = ($s | Measure-Object -Maximum).Maximum
if ($max -gt 1) { for ($i=0;$i -lt $n;$i++){ $s[$i] /= $max } }
Write-Wav "sfx_drumroll" $s

# --- Applause ---
$rng = New-Rng
$dur = 2.0; $n = [int]($dur * $rate); $s = New-Object double[] $n
for ($c = 0; $c -lt 220; $c++) {
    $st = [int]($rng.NextDouble() * ($n - $rate*0.03))
    $len = [int]((0.008 + $rng.NextDouble()*0.02) * $rate)
    $amp = 0.12 + $rng.NextDouble() * 0.28
    for ($j = 0; $j -lt $len -and ($st+$j) -lt $n; $j++) {
        $env = [math]::Exp(-260 * $j / $rate)
        $s[$st+$j] += ($rng.NextDouble()*2-1) * $env * $amp
    }
}
Write-Wav "sfx_applause" $s

# --- Laser ---
$dur = 0.45; $n = [int]($dur * $rate); $s = New-Object double[] $n
$phase = 0.0
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $p = $t / $dur
    $f = 2200 * [math]::Pow(0.09, $p)
    $phase += 2*[math]::PI*$f/$rate
    $sq = if (([math]::Sin($phase)) -gt 0) { 1 } else { -1 }
    $env = [math]::Exp(-6*$t)
    $s[$i] = $sq * $env * 0.5
}
Write-Wav "sfx_laser" $s

# --- Notification chime (two tone) ---
$dur = 1.1; $n = [int]($dur * $rate); $s = New-Object double[] $n
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $v = 0.0
    if ($t -lt 0.45) {
        $e1 = [math]::Exp(-6*$t)
        $v += $e1 * ([math]::Sin(2*[math]::PI*987.77*$t) + 0.3*[math]::Sin(2*[math]::PI*1975.5*$t)) * 0.4
    }
    if ($t -ge 0.15) {
        $t2 = $t - 0.15
        $e2 = [math]::Exp(-5*$t2)
        $v += $e2 * ([math]::Sin(2*[math]::PI*1318.5*$t) + 0.3*[math]::Sin(2*[math]::PI*2637*$t)) * 0.42
    }
    $s[$i] = $v
}
Write-Wav "sfx_chime" $s

# --- Riser ---
$dur = 1.8; $n = [int]($dur * $rate); $s = New-Object double[] $n
$rng = New-Rng; $noiseEma = 0.0
$phase = 0.0
for ($i = 0; $i -lt $n; $i++) {
    $t = $i / $rate
    $p = $t / $dur
    $f = 180 * [math]::Pow(10, $p)      # 180 Hz -> 1800 Hz
    $phase += 2*[math]::PI*$f/$rate
    $tone = [math]::Sin($phase) + 0.4 * [math]::Sin(2*$phase)
    $noiseEma += 0.08 * (($rng.NextDouble()*2-1) - $noiseEma)
    $env = [math]::Pow($p, 1.6)
    $s[$i] = ($tone * 0.6 + $noiseEma * 0.5) * $env * 0.65
}
Write-Wav "sfx_riser" $s

# --- Heartbeat ---
$dur = 1.0; $n = [int]($dur * $rate); $s = New-Object double[] $n
foreach ($beat in @(@(0.0, 1.0), @(0.32, 0.75), @(0.62, 1.0), @(0.94, 0.75))) {
    $bt = [double]$beat[0]; $ba = [double]$beat[1]
    $st = [int]($bt * $rate); $len = [int](0.14 * $rate)
    for ($j = 0; $j -lt $len -and ($st+$j) -lt $n; $j++) {
        $tt = $j / $rate
        $env = [math]::Exp(-38*$tt)
        $thump = [math]::Sin(2*[math]::PI*(58 - 20*$tt)*$tt)
        $s[$st+$j] += $thump * $env * $ba
    }
}
Write-Wav "sfx_heartbeat" $s

Write-Host "Done."
