# IronMan HUD for Xreal Glasses

Voice-controlled AR HUD with "Jessica" AI assistant. Hybrid reality overlay + full Matrix immersion mode.

## Features
- **Voice Commands**: "Jarvis, show weather" / "Jarvis, matrix mode" / "Jarvis, messages"
- **Hybrid Mode**: Translucent HUD panels over real world (weather, messages, system status)
- **Matrix Mode**: Full immersion - skybox swap, orbital menu, Jessica avatar front & center
- **Jessica AI**: Local (Ollama) or cloud LLM integration with TTS
- **Device Profiles**: Auto-tunes for Pixel 10 Pro XL, Galaxy S24, S24 Ultra

## Quick Start

### 1. Open in Unity
```
Unity Hub → Add Project → Select: ~/Desktop/IronManHUD
Unity Version: 2022.3.21f1 (LTS)
```

### 2. Install NRSDK (Xreal SDK)
- Window → Package Manager → + → Add from git URL
- `https://github.com/nreal-dev/NRSDK-Unity.git#2.0.0`
- Or download `.unitypackage` from https://developer.xreal.com/download/ and import

### 3. Configure Jessica
Edit `Assets/Scripts/AI/JessicaClient.cs`:
```csharp
// For local Ollama (recommended - runs on your PC/phone):
apiBaseUrl = "http://YOUR_PC_IP:11434"
modelName = "llama3.1:8b"

// For cloud (OpenAI):
apiBaseUrl = "https://api.openai.com/v1"
modelName = "gpt-4o-mini"
// Add API key header in SendChatRequest()
```

### 4. Weather API Key
Edit `Assets/Scripts/HUD/Panels/WeatherPanel.cs`:
```csharp
apiKey = "YOUR_OPENWEATHERMAP_API_KEY"  // Get free key at openweathermap.org
```

### 5. Build & Run
- Connect Pixel 10 Pro XL / Galaxy S24 via USB-C
- Enable USB Debugging (Settings → Developer Options)
- File → Build Settings → Android → **Build And Run**
- Put on Xreal glasses → USB-C to phone

## Voice Commands

| Command | Action |
|---------|--------|
| "Jarvis, matrix mode" | Enter full immersion |
| "Jarvis, hybrid mode" | Return to AR overlay |
| "Jarvis, weather" | Toggle weather panel |
| "Jarvis, messages" | Toggle messages |
| "Jarvis, systems" | Toggle system status |
| "Jarvis, menu" | Toggle orbital menu |
| "Jarvis, Jessica" | Toggle AI avatar |
| "Jarvis, close all" | Hide all panels |
| "Jarvis, [any question]" | Ask Jessica |

## Device Profiles

| Device | Frame Rate | Render Scale | Hand Tracking | Eye Tracking |
|--------|------------|--------------|---------------|--------------|
| Pixel 10 Pro XL | 90 FPS | 1.0x | ✅ | ✅ |
| Galaxy S24 Ultra | 90 FPS | 1.0x | ✅ | ❌ |
| Galaxy S24 | 60 FPS | 0.9x | ✅ | ❌ |
| Generic | 60 FPS | 0.8x | ❌ | ❌ |

## Project Structure
```
Assets/
├── Scripts/
│   ├── Core/           # Bootstrapper, DeviceDetector
│   ├── HUD/            # Main controller, panel base class
│   ├── HUD/Panels/     # Weather, Messages, System, Orbital, Jessica
│   ├── Voice/          # VoiceCommandManager (Android SpeechRecognizer + Vosk)
│   └── AI/             # JessicaClient (Ollama/OpenAI + Android TTS)
├── Scenes/
│   └── IronManHUD.unity
└── StarkIndustries.asmdef
```

## Troubleshooting

**"NRSDK not found"**
- Import NRSDK package first, then reopen project

**Voice not working on Samsung**
- Settings → Apps → IronManHUD → Permissions → Microphone → Allow
- Settings → Battery → IronManHUD → Unrestricted

**Jessica not responding**
- Check `apiBaseUrl` reaches your Ollama server (PC IP, not localhost)
- Test: `curl http://YOUR_PC_IP:11434/api/tags`

**Overheating on S24**
- DeviceDetector enables battery optimization automatically
- Lower QualitySettings in Project Settings → Quality

## Next Steps
- [ ] Add hand gesture navigation (pinch to select, swipe to rotate orbital)
- [ ] Eye-tracking foveated rendering (Xreal One/Pro)
- [ ] Spatial anchors for persistent panel positions
- [ ] Suit status telemetry (Bluetooth LE from DIY hardware)
- [ ] Computer vision object detection (MediaPipe on phone)

## License
MIT - Build something cool. "Sometimes you gotta run before you can walk." - Tony Stark