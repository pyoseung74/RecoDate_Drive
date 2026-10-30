# Speaks each scene's "spoken" text from script.json with the Korean Heami voice into tts\<id>.wav
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$dir = "C:\Android\video"
New-Item -ItemType Directory -Force "$dir\tts" | Out-Null
$scenes = Get-Content "$dir\script.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice("Microsoft Heami Desktop")
$synth.Rate = 1
foreach ($s in @($scenes)) {
  $synth.SetOutputToWaveFile("$dir\tts\$($s.id).wav")
  $synth.Speak([string]$s.spoken)
  $synth.SetOutputToNull()
}
$synth.Dispose()
