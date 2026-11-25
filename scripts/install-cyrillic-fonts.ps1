# Install Cyrillic-capable OFL families, preserving Prism's existing font keys and resource IDs.
# Run from any directory with Windows PowerShell 5.1+ and Git. No automatic commit or push.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$rev = '6ebe737e44fd64ee736bbc6feed6136a6dbfd222'
$base = "https://raw.githubusercontent.com/google/fonts/$rev/ofl"
$fontDir = Join-Path $root 'app\src\main\res\font'
$licenseDir = Join-Path $root 'app\src\main\assets\licenses'
$kotlinDir = Join-Path $root 'app\src\main\java\com\prism\music'
$settingsPath = Join-Path $kotlinDir 'data\prefs\Settings.kt'
$tokensPath = Join-Path $kotlinDir 'ui\theme\Tokens.kt'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$items = @(
    @{ dir='onest'; file='Onest[wght].ttf'; dest='outfit'; sha='477cb31027450d80e486a53119641bf87dde3d4c'; license='b6f4f91487900bb8f00ae991724121f1d59e30d9' }
    @{ dir='golostext'; file='GolosText[wght].ttf'; dest='plus_jakarta_sans'; sha='05ee5ecc570b97818e24e58693662cd8294fdfc9'; license='4db2dc99dd31ec2dd9655d3b71565a9959394ab1' }
    @{ dir='commissioner'; file='Commissioner[FLAR,VOLM,slnt,wght].ttf'; dest='urbanist'; sha='2ac22fba70bcf5d36052dfa604b43333b826996f'; license='eaa7c1c436f0303948dd0d6ec51cfce14bf376e8' }
    @{ dir='lora'; file='Lora[wght].ttf'; dest='fraunces'; sha='ee1914c3746140523de2940d267ec2215bd85d5b'; license='d0ce7fe062e60eaa92c4af3ee1bebe81c5141b4e' }
    @{ dir='jetbrainsmono'; file='JetBrainsMono[wght].ttf'; dest='geist_mono'; sha='aa310be8b717fe3774f9444dd89d5f4101cc6d10'; license='821a3dac22aff15a1f1c9689a1d79c45bb58ca39' }
    @{ dir='notosans'; file='NotoSans[wdth,wght].ttf'; dest='atkinson_hyperlegible_next'; sha='75575046c015ff623a848096a15779867ba71453'; license='6843f31878c9945e1e71e1baa275546b4eefead8' }
)

function Download-Verified([string]$Url, [string]$Path, [string]$Sha) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Path
    $actual = (& git hash-object --no-filters $Path).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $Sha) {
        throw "Git blob hash mismatch: $Path"
    }
}
function Replace-Checked([string]$Text, [string]$Old, [string]$New) {
    if ($Text.Contains($New)) { return $Text }
    if (-not $Text.Contains($Old)) { throw "Unexpected source; cannot replace: $Old" }
    return $Text.Replace($Old, $New)
}

# Verify source compatibility before touching any font. Keep user imports, Manrope, Nunito,
# System and the dedicated OpenDyslexic accessibility option untouched.
$settings = [IO.File]::ReadAllText($settingsPath)
$tokens = [IO.File]::ReadAllText($tokensPath)
$settings = Replace-Checked $settings 'tr(R.string.outfit)' '"Onest"'
$settings = Replace-Checked $settings 'tr(R.string.plus_jakarta_sans)' '"Golos Text"'
$settings = Replace-Checked $settings 'tr(R.string.urbanist)' '"Commissioner"'
$settings = Replace-Checked $settings 'tr(R.string.fraunces)' '"Lora"'
$settings = Replace-Checked $settings 'tr(R.string.geist_mono)' '"JetBrains Mono"'
$settings = Replace-Checked $settings 'tr(R.string.atkinson_hyperlegible)' '"Noto Sans"'
$tokens = Replace-Checked $tokens 'com.prism.music.R.font.fraunces to arrayOf(FontVariation.Setting("opsz", 48f), FontVariation.Setting("WONK", 0f))' 'com.prism.music.R.font.fraunces to emptyArray()'
$tokens = Replace-Checked $tokens 'private fun lighten(choice: FontChoice): Int = if (choice == FontChoice.SERIF) 100 else 0' @'
private fun requestedWeight(choice: FontChoice, w: Int): Int = when (choice) {
        FontChoice.SERIF -> w.coerceIn(400, 700)
        FontChoice.GEOMETRIC -> w.coerceIn(400, 900)
        FontChoice.MONO, FontChoice.CLEAN -> w.coerceIn(100, 800)
        else -> w.coerceIn(100, 900)
    }
'@
$tokens = Replace-Checked $tokens '        val less = lighten(choice)' '        // Each family has its own weight range.'
$tokens = Replace-Checked $tokens 'FontVariation.weight((w - less).coerceAtLeast(100))' 'FontVariation.weight(requestedWeight(choice, w))'
$tokens = $tokens.Replace("Outfit, Prism's own typeface.", "Onest, Prism's own typeface.")
$tokens = $tokens.Replace("Latin (and for some Cyrillic / Greek) only:", "Cyrillic and Latin:")

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("prism-fonts-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    foreach ($f in $items) {
        $name = [Uri]::EscapeDataString($f.file)
        Download-Verified "$base/$($f.dir)/$name" (Join-Path $tmp "$($f.dest).ttf") $f.sha
        Download-Verified "$base/$($f.dir)/OFL.txt" (Join-Path $tmp "$($f.dest).txt") $f.license
        Write-Host "Verified $($f.dir)" -ForegroundColor Green
    }
    foreach ($f in $items) {
        Copy-Item -LiteralPath (Join-Path $tmp "$($f.dest).ttf") -Destination (Join-Path $fontDir "$($f.dest).ttf") -Force
        Copy-Item -LiteralPath (Join-Path $tmp "$($f.dest).txt") -Destination (Join-Path $licenseDir "$($f.dest).txt") -Force
    }
    [IO.File]::WriteAllText($settingsPath, $settings, $utf8)
    [IO.File]::WriteAllText($tokensPath, $tokens, $utf8)
    Write-Host 'Cyrillic fonts installed. Review git diff and build Prism.' -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force
}
