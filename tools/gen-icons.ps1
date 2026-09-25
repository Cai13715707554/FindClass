# Generates launcher icons for every density bucket.
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI unless a BOM is present.
# Run: powershell -File tools/gen-icons.ps1

Add-Type -AssemblyName System.Drawing

function New-Icon {
    param([int]$Size, [string]$Path, [bool]$Round)

    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'

    $bg = [System.Drawing.ColorTranslator]::FromHtml('#2F6BFF')
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $green = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#16B364'))
    $blue = New-Object System.Drawing.SolidBrush($bg)

    if ($Round) {
        $g.Clear([System.Drawing.Color]::Transparent)
        $circle = New-Object System.Drawing.SolidBrush($bg)
        $g.FillEllipse($circle, 0, 0, $Size - 1, $Size - 1)
        $circle.Dispose()
    } else {
        $g.Clear($bg)
    }

    # 192 is the design grid; scale everything from it.
    $s = $Size / 192.0

    # Building body
    $g.FillRectangle($white, [int](68 * $s), [int](56 * $s), [int](56 * $s), [int](80 * $s))

    # Floor divider lines
    foreach ($y in 78, 100, 122) {
        $g.FillRectangle($blue, [int](68 * $s), [int]($y * $s), [int](56 * $s), [int](6 * $s))
    }

    # Target marker
    $g.FillEllipse($green, [int](87 * $s), [int](76 * $s), [int](18 * $s), [int](18 * $s))

    $g.Dispose()
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$densities = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

foreach ($entry in $densities.GetEnumerator()) {
    $dir = "app\src\main\res\$($entry.Key)"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    New-Icon -Size $entry.Value -Path (Join-Path $PWD "$dir\ic_launcher.png") -Round $false
    New-Icon -Size $entry.Value -Path (Join-Path $PWD "$dir\ic_launcher_round.png") -Round $true
}

Get-ChildItem "app\src\main\res" -Directory -Filter "mipmap-*" |
    ForEach-Object { Write-Output "$($_.Name): $((Get-ChildItem $_.FullName).Count) files" }
