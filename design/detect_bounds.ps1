Add-Type -AssemblyName System.Drawing
$img = New-Object System.Drawing.Bitmap("C:\Claude\d365-mobile-ops\design\logo_cropped_final.png")
$w = $img.Width; $h = $img.Height

$minX=9999; $maxX=0; $minY=9999; $maxY=0
for ($y=0; $y -lt $h; $y+=1) {
  for ($x=0; $x -lt $w; $x+=1) {
    $p = $img.GetPixel($x,$y)
    $isBlack = ($p.R -lt 60) -and ($p.G -lt 60) -and ($p.B -lt 60)
    if ($isBlack) {
      if ($x -lt $minX) { $minX = $x }
      if ($x -gt $maxX) { $maxX = $x }
      if ($y -lt $minY) { $minY = $y }
      if ($y -gt $maxY) { $maxY = $y }
    }
  }
}
"black bars bbox: x=$minX-$maxX y=$minY-$maxY"

$cy = [int](($minY+$maxY)/2)
$leftWhite=-1; $rightWhite=-1
for ($x=0; $x -lt $w; $x+=1) {
  $p = $img.GetPixel($x,$cy)
  $isWhiteish = ($p.R -gt 200) -and ($p.G -gt 200) -and ($p.B -gt 200)
  if ($isWhiteish -and $x -gt 50 -and $x -lt ($w-50)) {
    if ($leftWhite -eq -1) { $leftWhite = $x }
    $rightWhite = $x
  }
}
"white box at y=$cy spans x=$leftWhite-$rightWhite"

$cx = [int](($minX+$maxX)/2)
$topWhite=-1; $bottomWhite=-1
for ($y=0; $y -lt $h; $y+=1) {
  $p = $img.GetPixel($cx,$y)
  $isWhiteish = ($p.R -gt 200) -and ($p.G -gt 200) -and ($p.B -gt 200)
  if ($isWhiteish -and $y -gt 20 -and $y -lt ($h-20)) {
    if ($topWhite -eq -1) { $topWhite = $y }
    $bottomWhite = $y
  }
}
"white box at x=$cx spans y=$topWhite-$bottomWhite"

$img.Dispose()
