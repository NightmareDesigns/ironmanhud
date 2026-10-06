using UnityEngine;
using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json;
using TMPro;

namespace StarkIndustries.AI
{
    [Serializable]
    public class JessicaConfig
    {
        public string apiBaseUrl = "http://192.168.1.100:11434"; // Ollama default
        public string modelName = "llama3.1:8b";
        public string systemPrompt = "You are JESSICA, Tony Stark's personal AI assistant. Be concise, witty, and helpful. Respond in 1-2 sentences max for HUD display.";
        public float temperature = 0.7f;
        public int maxTokens = 150;
        public float requestTimeout = 30f;
    }

    [Serializable]
    public class ChatMessage
    {
        public string role;
        public string content;
    }

    [Serializable]
    public class ChatRequest
    {
        public string model;
        public List<ChatMessage> messages;
        public float temperature;
        public int max_tokens;
        public bool stream;
    }

    [Serializable]
    public class ChatResponse
    {
        public string model;
        public Message message;
        public bool done;
    }

    [Serializable]
    public class Message
    {
        public string role;
        public string content;
    }

    public class JessicaClient : MonoBehaviour
    {
        [Header("Configuration")]
        [SerializeField] private JessicaConfig config = new();
        [SerializeField] private TextMeshProUGUI responseText;
        [SerializeField] private GameObject typingIndicator;
        [SerializeField] private float typingSpeed = 0.02f;

        [Header("Personality")]
        [SerializeField] private AudioSource voiceAudio;
        [SerializeField] private AudioClip[] thinkingSounds;
        [SerializeField] private AudioClip[] responseSounds;

        private HttpClient httpClient;
        private List<ChatMessage> conversationHistory = new();
        private bool isProcessing = false;
        private StringBuilder currentResponse = new();

        public static JessicaClient Instance { get; private set; }
        public bool IsProcessing => isProcessing;

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
            httpClient = new HttpClient
            {
                Timeout = TimeSpan.FromSeconds(config.requestTimeout)
            };

            conversationHistory.Add(new ChatMessage { role = "system", content = config.systemPrompt });
        }

        public async void ProcessQuery(string query)
        {
            if (isProcessing) return;
            isProcessing = true;
            typingIndicator?.SetActive(true);

            if (thinkingSounds.Length > 0 && voiceAudio != null)
                voiceAudio.PlayOneShot(thinkingSounds[UnityEngine.Random.Range(0, thinkingSounds.Length)]);

            conversationHistory.Add(new ChatMessage { role = "user", content = query });

            try
            {
                var response = await SendChatRequest();
                await TypewriterEffect(response);
                
                conversationHistory.Add(new ChatMessage { role = "assistant", content = response });
                SpeakResponse(response);
            }
            catch (Exception e)
            {
                Debug.LogError($"[Jessica] Error: {e.Message}");
                await TypewriterEffect("Systems experiencing interference. Try again, sir.");
            }
            finally
            {
                isProcessing = false;
                typingIndicator?.SetActive(false);
            }
        }

        private async Task<string> SendChatRequest()
        {
            var request = new ChatRequest
            {
                model = config.modelName,
                messages = conversationHistory,
                temperature = config.temperature,
                max_tokens = config.maxTokens,
                stream = false
            };

            var json = JsonConvert.SerializeObject(request);
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            var response = await httpClient.PostAsync($"{config.apiBaseUrl}/api/chat", content);
            response.EnsureSuccessStatusCode();

            var responseJson = await response.Content.ReadAsStringAsync();
            var chatResponse = JsonConvert.DeserializeObject<ChatResponse>(responseJson);

            return chatResponse?.message?.content ?? "No response received.";
        }

        private async Task TypewriterEffect(string text)
        {
            currentResponse.Clear();
            responseText.text = "";

            foreach (char c in text)
            {
                currentResponse.Append(c);
                responseText.text = currentResponse.ToString();
                await System.Threading.Tasks.Task.Delay((int)(typingSpeed * 1000));
            }
        }

        private void SpeakResponse(string text)
        {
            if (voiceAudio != null && responseSounds.Length > 0)
                voiceAudio.PlayOneShot(responseSounds[UnityEngine.Random.Range(0, responseSounds.Length)]);

#if UNITY_ANDROID && !UNITY_EDITOR
            AndroidTTS.Speak(text);
#endif
        }

        public void OnModeChanged(IronManHUD.HUDMode mode)
        {
            string context = mode == IronManHUD.HUDMode.Matrix 
                ? "Switching to full immersion mode. All systems dedicated to tactical display."
                : "Returning to hybrid mode. Reality overlay active.";
            
            ProcessQuery(context);
        }

        public void ClearHistory()
        {
            conversationHistory.Clear();
            conversationHistory.Add(new ChatMessage { role = "system", content = config.systemPrompt });
        }

        private void OnDestroy()
        {
            httpClient?.Dispose();
        }
    }
}

#if UNITY_ANDROID && !UNITY_EDITOR
public static class AndroidTTS
{
    private static AndroidJavaObject tts;
    private static bool initialized = false;

    public static void Speak(string text)
    {
        if (!initialized) Initialize();
        tts?.Call("speak", text, 0, null, "UTTERANCE_ID");
    }

    private static void Initialize()
    {
        using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
        using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
        using (var ttsClass = new AndroidJavaClass("android.speech.tts.TextToSpeech"))
        {
            tts = new AndroidJavaObject("android.speech.tts.TextToSpeech", activity, new TTSInitListener());
            initialized = true;
        }
    }

    private class TTSInitListener : AndroidJavaProxy
    {
        public TTSInitListener() : base("android.speech.tts.TextToSpeech$OnInitListener") { }
        public void onInit(int status) { }
    }
}
#endif