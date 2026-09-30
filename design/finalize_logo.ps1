Add-Type -AssemblyName System.Drawing

$src = "C:\Claude\d365-mobile-ops\design\logo_redrawn.png"
$bmp = New-Object System.Drawing.Bitmap($src)
$w = $bmp.Width; $h = $bmp.Height
$out = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($y=0; $y -lt $h; $y++) {
  for ($x=0; $x -lt $w; $x++) {
    $p = $bmp.GetPixel($x,$y)
    $isNearWhite = ($p.R -gt 225) -and ($p.G -gt 225) -and ($p.B -gt 225)
    if ($isNearWhite) {
      $out.SetPixel($x,$y,[System.Drawing.Color]::FromArgb(0,255,255,255))
    } else {
      $out.SetPixel($x,$y,$p)
    }
  }
}
$transparentPath = "C:\Claude\d365-mobile-ops\design\logo_redrawn_transparent.png"
$out.Save($transparentPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# Build launcher foreground: 432x432 canvas, mark scaled to ~280px wide, centered
$targetW = 280.0
$scale = $targetW / $w
$targetH = [int]($h * $scale)
$canvas = New-Object System.Drawing.Bitmap(432, 432, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($canvas)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$destX = [int]((432 - $targetW) / 2)
$destY = [int]((432 - $targetH) / 2)
$g.DrawImage($out, (New-Object System.Drawing.Rectangle($destX, $destY, $targetW, $targetH)))
$fgPath = "C:\Claude\d365-mobile-ops\design\ic_launcher_foreground_final.png"
$canvas.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)

$g.Dispose(); $canvas.Dispose(); $out.Dispose()
"done: $transparentPath and $fgPath"
