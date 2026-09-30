Add-Type -AssemblyName System.Drawing
$src = "C:\Claude\d365-mobile-ops\design\logo_cropped_final.png"
$img = New-Object System.Drawing.Bitmap($src)
$g = [System.Drawing.Graphics]::FromImage($img)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None

$white = [System.Drawing.Brushes]::White
$black = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,26,26,26))

# Erase old dense bars within their bounding box (a little padding to fully clear edges)
$g.FillRectangle($white, 110, 178, 365, 128)

# Draw fewer, thicker bars, evenly spaced, in the same footprint
$barY = 185
$barH = 114
$barW = 30
$gapW = 20
$startX = 128
for ($i=0; $i -lt 7; $i++) {
    $x = $startX + $i * ($barW + $gapW)
    $g.FillRectangle($black, $x, $barY, $barW, $barH)
}

$g.Dispose()
$out = "C:\Claude\d365-mobile-ops\design\logo_redrawn.png"
$img.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
"saved: $out"
