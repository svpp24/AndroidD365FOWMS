Add-Type -AssemblyName System.Drawing

function New-LogoBitmap($w, $h, $scale, $offsetX, $offsetY) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $redColor = [System.Drawing.Color]::FromArgb(255, 163, 30, 52)
    $redBrush = New-Object System.Drawing.SolidBrush($redColor)
    $black = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,26,26,26))

    function T($x,$y) {
        return New-Object System.Drawing.PointF(($x*$scale+$offsetX), ($y*$scale+$offsetY))
    }

    $left = @(T 95 20; T 235 20; T 190 453; T 50 453)
    $right = @(T 365 20; T 505 20; T 460 453; T 320 453)
    $g.FillPolygon($redBrush, $left)
    $g.FillPolygon($redBrush, $right)

    $bars = @(
        @(150,10),@(172,18),@(202,8),@(222,26),@(260,10),
        @(282,14),@(308,22),@(342,10),@(364,16),@(392,8)
    )
    foreach ($b in $bars) {
        $x = $b[0]; $bw = $b[1]
        $rx = $x*$scale+$offsetX
        $ry = 150*$scale+$offsetY
        $rw = $bw*$scale
        $rh = 170*$scale
        $g.FillRectangle($black, $rx, $ry, $rw, $rh)
    }

    $g.Dispose()
    $redBrush.Dispose(); $black.Dispose()
    return $bmp
}

$logo = New-LogoBitmap 556 473 1.0 0 0
$logo.Save("C:\Claude\d365-mobile-ops\design\ic_app_logo_v2.png", [System.Drawing.Imaging.ImageFormat]::Png)
$logo.Dispose()

$targetW = 280.0
$scale = $targetW / 556.0
$markH = 473.0 * $scale
$offX = (432 - $targetW) / 2
$offY = (432 - $markH) / 2
$fg = New-LogoBitmap 432 432 $scale $offX $offY
$fg.Save("C:\Claude\d365-mobile-ops\design\ic_launcher_foreground_v2.png", [System.Drawing.Imaging.ImageFormat]::Png)
$fg.Dispose()

"done"
