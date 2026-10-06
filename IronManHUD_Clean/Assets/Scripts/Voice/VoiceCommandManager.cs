using UnityEngine;
using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace StarkIndustries.Voice
{
    public enum VoiceEngine { AndroidSpeechRecognizer, VoskOffline, WhisperLocal }

    public class VoiceCommandManager : MonoBehaviour
    {
        [Header("Engine Settings")]
        [SerializeField] private VoiceEngine engine = VoiceEngine.AndroidSpeechRecognizer;
        [SerializeField] private string voskModelPath = "vosk-model-small-en-us-0.15";
        [SerializeField] private string wakeWord = "jarvis";
        [SerializeField] private float confidenceThreshold = 0.7f;
        [SerializeField] private bool continuousListening = true;

        [Header("Audio Feedback")]
        [SerializeField] private AudioSource audioSource;
        [SerializeField] private AudioClip wakeSound;
        [SerializeField] private AudioClip processSound;
        [SerializeField] private AudioClip errorSound;

        [Header("Visual Feedback")]
        [SerializeField] private GameObject listeningIndicator;
        [SerializeField] private GameObject processingIndicator;

        private AndroidJavaObject speechRecognizer;
        private AndroidJavaObject recognizerIntent;
        private bool isListening = false;
        private string lastPartialResult = "";
        private Action<string> onCommandRecognized;

        public static VoiceCommandManager Instance { get; private set; }
        public bool IsListening => isListening;

        private void Awake()
        {
            if (Instance != null && Instance != this)
            {
                Destroy(gameObject);
                return;
            }
            Instance = this;
            DontDestroyOnLoad(gameObject);
        }

        private void Start()
        {
            InitializeEngine();
            onCommandRecognized += IronManHUDController.Instance?.OnVoiceCommand;
        }

        private void InitializeEngine()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (engine == VoiceEngine.AndroidSpeechRecognizer)
                InitializeAndroidSpeechRecognizer();
            else if (engine == VoiceEngine.VoskOffline)
                InitializeVosk();
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private void InitializeAndroidSpeechRecognizer()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var speechRecognizerClass = new AndroidJavaClass("android.speech.SpeechRecognizer"))
            using (var intentClass = new AndroidJavaClass("android.content.Intent"))
            using (var recognizerIntentObj = new AndroidJavaObject("android.speech.RecognizerIntent"))
            {
                speechRecognizer = speechRecognizerClass.CallStatic<AndroidJavaObject>("createSpeechRecognizer", activity);
                recognizerIntent = new AndroidJavaObject("android.content.Intent", recognizerIntentObj.GetStatic<string>("ACTION_RECOGNIZE_SPEECH"));
                recognizerIntent.Call<AndroidJavaObject>("putExtra", recognizerIntentObj.GetStatic<string>("EXTRA_LANGUAGE_MODEL"), recognizerIntentObj.GetStatic<string>("LANGUAGE_MODEL_FREE_FORM"));
                recognizerIntent.Call<AndroidJavaObject>("putExtra", recognizerIntentObj.GetStatic<string>("EXTRA_CALLING_PACKAGE"), activity.Call<AndroidJavaObject>("getPackageName"));
                recognizerIntent.Call<AndroidJavaObject>("putExtra", recognizerIntentObj.GetStatic<string>("EXTRA_PARTIAL_RESULTS"), true);
                recognizerIntent.Call<AndroidJavaObject>("putExtra", recognizerIntentObj.GetStatic<string>("EXTRA_MAX_RESULTS"), 3);

                var listener = new SpeechRecognitionListener(this);
                speechRecognizer.Call("setRecognitionListener", listener);
            }
        }

        private void InitializeVosk()
        {
            Debug.Log("[Voice] Vosk offline engine - implement via JNI bridge to Vosk Android library");
        }
#endif

        public void StartListening()
        {
            if (isListening) return;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (engine == VoiceEngine.AndroidSpeechRecognizer && speechRecognizer != null)
            {
                speechRecognizer.Call("startListening", recognizerIntent);
                isListening = true;
                UpdateIndicators(true, false);
                PlaySound(wakeSound);
            }
#endif
        }

        public void StopListening()
        {
            if (!isListening) return;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (engine == VoiceEngine.AndroidSpeechRecognizer && speechRecognizer != null)
            {
                speechRecognizer.Call("stopListening");
                isListening = false;
                UpdateIndicators(false, false);
            }
#endif
        }

        public void ToggleListening()
        {
            if (isListening) StopListening();
            else StartListening();
        }

        internal void OnPartialResult(string partial)
        {
            lastPartialResult = partial;
            CheckWakeWord(partial);
        }

        internal void OnFinalResult(string result, float confidence)
        {
            isListening = false;
            UpdateIndicators(false, false);

            if (confidence < confidenceThreshold) return;

            result = result.ToLower().Trim();
            Debug.Log($"[Voice] Final: {result} (confidence: {confidence})");

            if (result.StartsWith(wakeWord))
                result = result.Substring(wakeWord.Length).Trim();

            if (!string.IsNullOrEmpty(result))
            {
                PlaySound(processSound);
                UpdateIndicators(false, true);
                onCommandRecognized?.Invoke(result);
            }
        }

        internal void OnError(int errorCode)
        {
            isListening = false;
            UpdateIndicators(false, false);
            PlaySound(errorSound);
            Debug.LogWarning($"[Voice] Error: {errorCode}");

            if (continuousListening)
            {
                Invoke(nameof(StartListening), 2f);
            }
        }

        private void CheckWakeWord(string partial)
        {
            if (!isListening && partial.ToLower().Contains(wakeWord))
            {
                Debug.Log("[Voice] Wake word detected!");
                StartListening();
            }
        }

        private void UpdateIndicators(bool listening, bool processing)
        {
            listeningIndicator?.SetActive(listening);
            processingIndicator?.SetActive(processing);
        }

        private void PlaySound(AudioClip clip)
        {
            if (audioSource != null && clip != null)
                audioSource.PlayOneShot(clip);
        }

        public void SetWakeWord(string word)
        {
            wakeWord = word.ToLower().Trim();
        }

        public void SetContinuousListening(bool enabled)
        {
            continuousListening = enabled;
            if (enabled && !isListening) StartListening();
        }

        private void OnApplicationPause(bool pause)
        {
            if (pause) StopListening();
            else if (continuousListening) StartListening();
        }

        private void OnDestroy()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            speechRecognizer?.Call("destroy");
#endif
        }
    }

#if UNITY_ANDROID && !UNITY_EDITOR
    public class SpeechRecognitionListener : AndroidJavaProxy
    {
        private VoiceCommandManager manager;

        public SpeechRecognitionListener(VoiceCommandManager mgr) : base("android.speech.RecognitionListener")
        {
            manager = mgr;
        }

        public void onReadyForSpeech(AndroidJavaObject params) { }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float rmsdB) { }
        public void onBufferReceived(byte[] buffer) { }

        public void onEndOfSpeech()
        {
            manager.UpdateIndicators(false, true);
        }

        public void onError(int error)
        {
            manager.OnError(error);
        }

        public void onResults(AndroidJavaObject results)
        {
            using (var matches = results.Call<AndroidJavaObject>("getStringArrayList", "results_recognition"))
            {
                if (matches != null)
                {
                    var size = matches.Call<int>("size");
                    if (size > 0)
                    {
                        string best = matches.Call<string>("get", 0);
                        manager.OnFinalResult(best, 1.0f);
                    }
                }
            }
        }

        public void onPartialResults(AndroidJavaObject partialResults)
        {
            using (var matches = partialResults.Call<AndroidJavaObject>("getStringArrayList", "results_recognition"))
            {
                if (matches != null)
                {
                    var size = matches.Call<int>("size");
                    if (size > 0)
                    {
                        string partial = matches.Call<string>("get", 0);
                        manager.OnPartialResult(partial);
                    }
                }
            }
        }

        public void onEvent(int eventType, AndroidJavaObject params) { }
    }
#endif
}