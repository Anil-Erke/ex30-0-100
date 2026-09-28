# Play Console gorselleri: 512x512 magaza simgesi + 1024x500 tanitim gorseli.
# PowerShell 5.1'de kaynak dosyasindaki Turkce karakterler bozuluyor (ANSI);
# bu yuzden metinler kod noktasindan kuruluyor (prompt.md §7.6b).
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $PSScriptRoot "..\play-assets"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$i  = [char]0x0131  # i (noktasiz)
$o  = [char]0x00F6  # o umlaut
$u  = [char]0x00FC  # u umlaut
$c  = [char]0x00E7  # c cedilla
$s  = [char]0x015F  # s cedilla

$bg      = [System.Drawing.ColorTranslator]::FromHtml("#0B0E13")
$panel   = [System.Drawing.ColorTranslator]::FromHtml("#161A23")
$accent  = [System.Drawing.ColorTranslator]::FromHtml("#4FC3F7")
$green   = [System.Drawing.ColorTranslator]::FromHtml("#3FB950")
$muted   = [System.Drawing.ColorTranslator]::FromHtml("#8A93A5")
$white   = [System.Drawing.Color]::White

function New-Graphics($w, $h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'ClearTypeGridFit'
    $g.Clear($bg)
    return @($bmp, $g)
}

# Kronometre motifi: govde cemberi, ust dugme, ivmelenme yayi, ibre.
function Draw-Stopwatch($g, $cx, $cy, $r, $stroke) {
    $penBody = New-Object System.Drawing.Pen $accent, $stroke
    $g.DrawEllipse($penBody, ($cx - $r), ($cy - $r), (2 * $r), (2 * $r))

    $penArc = New-Object System.Drawing.Pen $green, $stroke
    $penArc.StartCap = 'Round'; $penArc.EndCap = 'Round'
    # 0'dan 100'e ivmelenme yayi (sol ustten sag uste)
    $g.DrawArc($penArc, ($cx - $r), ($cy - $r), (2 * $r), (2 * $r), 200, 140)

    $penCrown = New-Object System.Drawing.Pen ([System.Drawing.ColorTranslator]::FromHtml("#C7CEDB")), $stroke
    $penCrown.StartCap = 'Round'; $penCrown.EndCap = 'Round'
    $g.DrawLine($penCrown, ($cx - $r * 0.20), ($cy - $r * 1.20), ($cx + $r * 0.20), ($cy - $r * 1.20))
    $g.DrawLine($penCrown, $cx, ($cy - $r * 1.20), $cx, ($cy - $r * 0.98))

    $penHand = New-Object System.Drawing.Pen $white, ($stroke * 0.85)
    $penHand.StartCap = 'Round'; $penHand.EndCap = 'Round'
    $g.DrawLine($penHand, $cx, $cy, ($cx + $r * 0.58), ($cy - $r * 0.52))

    $brushHub = New-Object System.Drawing.SolidBrush $white
    $hub = $r * 0.10
    $g.FillEllipse($brushHub, ($cx - $hub), ($cy - $hub), (2 * $hub), (2 * $hub))
}

# --- 512x512 magaza simgesi ---
$res = New-Graphics 512 512
$bmp = $res[0]; $g = $res[1]
Draw-Stopwatch $g 256 226 150 16
$fontBig = New-Object System.Drawing.Font "Segoe UI", 62, ([System.Drawing.FontStyle]::Bold)
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = 'Center'; $fmt.LineAlignment = 'Center'
$brushW = New-Object System.Drawing.SolidBrush $white
$rectTxt = New-Object System.Drawing.RectangleF 0, 400, 512, 90
$g.DrawString("0-100", $fontBig, $brushW, $rectTxt, $fmt)
$bmp.Save((Join-Path $outDir "icon-512.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# --- 1024x500 tanitim gorseli ---
$res = New-Graphics 1024 500
$bmp = $res[0]; $g = $res[1]

# Sol tarafta kronometre, sagda metin
Draw-Stopwatch $g 190 250 128 14

$dot = [char]0x00B7
$title = "EX30 0-100"
$subtitle = "Volvo EX30 i" + $c + "in h" + $i + "zlanma kronometresi"
$marks = "50  $dot  70  $dot  100  $dot  120  $dot  140 km/h"
$note = "Ara" + $c + " h" + $i + "z" + $i + "ndan " + $o + "l" + $c + "er  " + $dot +
        "  veri ara" + $c + "ta kal" + $i + "r"

$fmtL = New-Object System.Drawing.StringFormat
$fmtL.Alignment = 'Near'; $fmtL.LineAlignment = 'Center'

$fontTitle = New-Object System.Drawing.Font "Segoe UI", 58, ([System.Drawing.FontStyle]::Bold)
$fontSub   = New-Object System.Drawing.Font "Segoe UI", 27, ([System.Drawing.FontStyle]::Regular)
$fontMark  = New-Object System.Drawing.Font "Segoe UI", 30, ([System.Drawing.FontStyle]::Bold)
$fontNote  = New-Object System.Drawing.Font "Segoe UI", 21, ([System.Drawing.FontStyle]::Regular)

$brushMuted = New-Object System.Drawing.SolidBrush $muted
$brushGreen = New-Object System.Drawing.SolidBrush $green

# Metin genisligini asmasin diye punto dusurme
foreach ($pair in @(@($title, [ref]$fontTitle), @($subtitle, [ref]$fontSub),
                    @($marks, [ref]$fontMark), @($note, [ref]$fontNote))) {
    $txt = $pair[0]; $fRef = $pair[1]
    while ($g.MeasureString($txt, $fRef.Value).Width -gt 605 -and $fRef.Value.Size -gt 12) {
        $newSize = $fRef.Value.Size - 2
        $style = $fRef.Value.Style
        $fRef.Value = New-Object System.Drawing.Font "Segoe UI", $newSize, $style
    }
}

$g.DrawString($title, $fontTitle, $brushW, (New-Object System.Drawing.RectangleF 380, 130, 620, 80), $fmtL)
$g.DrawString($subtitle, $fontSub, $brushMuted, (New-Object System.Drawing.RectangleF 384, 200, 620, 50), $fmtL)
$g.DrawString($marks, $fontMark, $brushGreen, (New-Object System.Drawing.RectangleF 384, 288, 620, 50), $fmtL)
$g.DrawString($note, $fontNote, $brushMuted, (New-Object System.Drawing.RectangleF 384, 348, 620, 40), $fmtL)

$bmp.Save((Join-Path $outDir "feature-1024x500.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Output "Gorseller uretildi: $outDir"
