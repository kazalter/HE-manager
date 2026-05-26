$ErrorActionPreference = "Continue"
$fontDir = "D:\HE manager\android-app\app\src\main\res\font"

# Oxanium — sourcefoundry/Oxanium
$ox = "https://raw.githubusercontent.com/sourcefoundry/Oxanium/master/fonts/ttf"
# Geist + Geist Mono — vercel/geist-font
$gst = "https://raw.githubusercontent.com/vercel/geist-font/main/packages/next/dist/fonts/geist-sans"
$gstMono = "https://raw.githubusercontent.com/vercel/geist-font/main/packages/next/dist/fonts/geist-mono"
# Noto Sans SC — googlefonts/noto-cjk
$noto = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/SubsetOTF/SC"

$tasks = @(
    # Oxanium
    @{ url = "$ox/Oxanium-Regular.ttf"; out = "oxanium_regular.ttf" },
    @{ url = "$ox/Oxanium-SemiBold.ttf"; out = "oxanium_semibold.ttf" },
    @{ url = "$ox/Oxanium-Bold.ttf"; out = "oxanium_bold.ttf" },

    # Geist
    @{ url = "$gst/Geist-Regular.ttf"; out = "geist_regular.ttf" },
    @{ url = "$gst/Geist-Medium.ttf"; out = "geist_medium.ttf" },
    @{ url = "$gst/Geist-SemiBold.ttf"; out = "geist_semibold.ttf" },
    @{ url = "$gst/Geist-Bold.ttf"; out = "geist_bold.ttf" },

    # Geist Mono
    @{ url = "$gstMono/GeistMono-Regular.ttf"; out = "geist_mono_regular.ttf" },
    @{ url = "$gstMono/GeistMono-Medium.ttf"; out = "geist_mono_medium.ttf" },
    @{ url = "$gstMono/GeistMono-SemiBold.ttf"; out = "geist_mono_semibold.ttf" },

    # Noto Sans SC — OTF format (Android handles .otf fine, but we'll see if .ttf path exists)
    @{ url = "$noto/NotoSansSC-Medium.otf"; out = "noto_sans_sc_medium.otf" },
    @{ url = "$noto/NotoSansSC-Bold.otf"; out = "noto_sans_sc_bold.otf" },
    @{ url = "$noto/NotoSansSC-Black.otf"; out = "noto_sans_sc_black.otf" }
)

foreach ($t in $tasks) {
    $dest = Join-Path $fontDir $t.out
    try {
        Invoke-WebRequest -Uri $t.url -OutFile $dest -UseBasicParsing -TimeoutSec 60
        $size = (Get-Item $dest).Length
        Write-Host ("OK   {0,-28} {1,10:N0} bytes" -f $t.out, $size)
    } catch {
        Write-Host ("FAIL {0,-28} {1}" -f $t.out, $_.Exception.Message)
    }
}
