param([ValidateSet('voices', 'speak', 'listen')][string]$Mode)
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$inputData = [Console]::In.ReadToEnd() | ConvertFrom-Json
if ($Mode -eq 'listen') {
    $recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine
    try {
        $recognizer.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
        $recognizer.SetInputToDefaultAudioDevice()
        $result = $recognizer.Recognize([TimeSpan]::FromSeconds(15))
        if ($null -eq $result) { throw 'No speech recognized. Check your microphone and installed Windows speech language.' }
        @{ text = $result.Text } | ConvertTo-Json -Compress
    } finally { $recognizer.Dispose() }
} else {
    $speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if ($Mode -eq 'voices') {
            $voices = @($speaker.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo.Name })
            @{ voices = $voices } | ConvertTo-Json -Compress
        } else {
            $speaker.SetOutputToDefaultAudioDevice()
            if ($inputData.voice) { $speaker.SelectVoice($inputData.voice) }
            $speaker.Rate = [int]$inputData.rate
            $speaker.Volume = [int]$inputData.volume
            $speaker.Speak([string]$inputData.text)
            @{ spoken = $true } | ConvertTo-Json -Compress
        }
    } finally { $speaker.Dispose() }
}
