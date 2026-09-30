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
"black bars bbox: x=$minX-$maxX y=$minY-$maxY  (width=$($maxX-$minX) height=$($maxY-$minY))"

$cy = [int](($minY+$maxY)/2)
$isRed = { param($p) ($p.R -gt 120) -and ($p.G -lt 90) -and ($p.B -lt 90) }
$leftRedEdge=-1; $rightRedEdge=-1
$lastWasRed = $false
for ($x=0; $x -lt $w; $x+=1) {
  $p = $img.GetPixel($x,$cy)
  $red = (& $isRed $p)
  if ($lastWasRed -and -not $red -and $leftRedEdge -eq -1) { $leftRedEdge = $x }
  if (-not $lastWasRed -and $red -and $leftRedEdge -ne -1 -and $rightRedEdge -eq -1) { $rightRedEdge = $x }
  $lastWasRed = $red
}
"gap between H strokes at y=$cy : x=$leftRedEdge to $rightRedEdge (width=$($rightRedEdge-$leftRedEdge))"

$img.Dispose()
