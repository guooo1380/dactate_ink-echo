param([string]$OutputPath = 'build/rounded-artwork.png')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
Add-Type -AssemblyName PresentationCore,PresentationFramework,WindowsBase
$taskNamespace = 'http://schemas.android.com/apk/res/android'
$taskVisual = New-Object System.Windows.Media.DrawingVisual
$taskDrawing = $taskVisual.RenderOpen()
function Brush([string]$color) { return [System.Windows.Media.BrushConverter]::new().ConvertFromString($color) }
function Panel([double]$x,[double]$y,[double]$width,[double]$height) {
    $taskDrawing.DrawRoundedRectangle((Brush '#FFFFFF'),$null,[System.Windows.Rect]::new($x,$y,$width,$height),28,28)
}
function Vector([string]$name,[double]$x,[double]$y,[double]$width,[double]$height) {
    $document = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath "app/src/main/res/drawable/$name.xml")
    $vw = [double]$document.vector.GetAttribute('viewportWidth',$taskNamespace)
    $vh = [double]$document.vector.GetAttribute('viewportHeight',$taskNamespace)
    $taskDrawing.PushTransform([System.Windows.Media.MatrixTransform]::new($width/$vw,0,0,$height/$vh,$x,$y))
    foreach ($shape in $document.vector.path) {
        $fill = $null
        if ($shape.HasAttribute('fillColor',$taskNamespace)) { $fill = Brush ($shape.GetAttribute('fillColor',$taskNamespace)) }
        $pen = $null
        if ($shape.HasAttribute('strokeColor',$taskNamespace)) {
            $pen = [System.Windows.Media.Pen]::new((Brush ($shape.GetAttribute('strokeColor',$taskNamespace))),[double]$shape.GetAttribute('strokeWidth',$taskNamespace))
            if ($shape.GetAttribute('strokeLineCap',$taskNamespace) -eq 'round') { $pen.StartLineCap='Round';$pen.EndLineCap='Round' }
            if ($shape.GetAttribute('strokeLineJoin',$taskNamespace) -eq 'round') { $pen.LineJoin='Round' }
        }
        $taskDrawing.DrawGeometry($fill,$pen,[System.Windows.Media.Geometry]::Parse($shape.GetAttribute('pathData',$taskNamespace)))
    }
    $taskDrawing.Pop()
}
function Label([string]$text,[double]$x,[double]$y) {
    $formatted=[System.Windows.Media.FormattedText]::new($text,[System.Globalization.CultureInfo]::GetCultureInfo('zh-CN'),[System.Windows.FlowDirection]::LeftToRight,[System.Windows.Media.Typeface]::new('Microsoft YaHei'),14,(Brush '#6E7D75'),1.0)
    $taskDrawing.DrawText($formatted,[System.Windows.Point]::new($x,$y))
}
$taskDrawing.DrawRectangle((Brush '#F6F7F2'),$null,[System.Windows.Rect]::new(0,0,720,260))
Panel 20 20 200 220
Panel 236 20 464 220
Vector 'ic_launcher' 70 52 100 100
Label '应用图标' 92 181
Vector 'listening_illustration' 368 26 200 112
$taskIcons=@('ic_play','ic_add','ic_more','ic_arrow')
for($i=0;$i -lt $taskIcons.Length;$i++) {
    $x=311+$i*88
    $taskDrawing.DrawRoundedRectangle((Brush '#EAF0E8'),$null,[System.Windows.Rect]::new($x,156,48,48),24,24)
    Vector $taskIcons[$i] ($x+12) 168 24 24
}
Label '圆头声波与线条图标' 402 211
$taskDrawing.Close()
$taskBitmap=[System.Windows.Media.Imaging.RenderTargetBitmap]::new(1440,520,192,192,[System.Windows.Media.PixelFormats]::Pbgra32)
$taskBitmap.Render($taskVisual)
$taskEncoder=[System.Windows.Media.Imaging.PngBitmapEncoder]::new()
$taskEncoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($taskBitmap))
$taskStream=[System.IO.File]::Create((Join-Path (Get-Location) $OutputPath))
try { $taskEncoder.Save($taskStream) } finally { $taskStream.Dispose() }
Write-Output "Artwork preview: $OutputPath"
