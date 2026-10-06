using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace StarkIndustries.HUD.Panels
{
    [Serializable]
    public class WeatherData
    {
        public Main main;
        public Weather[] weather;
        public Wind wind;
        public string name;
        public long dt;
    }

    [Serializable]
    public class Main
    {
        public float temp;
        public float feels_like;
        public float humidity;
        public float pressure;
    }

    [Serializable]
    public class Weather
    {
        public string main;
        public string description;
        public string icon;
    }

    [Serializable]
    public class Wind
    {
        public float speed;
        public int deg;
    }

    public class WeatherPanel : HUDPanel
    {
        [Header("UI References")]
        [SerializeField] private TextMeshProUGUI locationText;
        [SerializeField] private TextMeshProUGUI temperatureText;
        [SerializeField] private TextMeshProUGUI conditionText;
        [SerializeField] private TextMeshProUGUI humidityText;
        [SerializeField] private TextMeshProUGUI windText;
        [SerializeField] private Image conditionIcon;
        [SerializeField] private TextMeshProUGUI lastUpdatedText;
        [SerializeField] private Button refreshButton;

        [Header("Settings")]
        [SerializeField] private string apiKey = "YOUR_OPENWEATHERMAP_API_KEY";
        [SerializeField] private string defaultCity = "New York";
        [SerializeField] private float updateIntervalMinutes = 15f;

        private float updateTimer = 0f;

        protected override void Start()
        {
            base.Start();
            refreshButton?.onClick.AddListener(RefreshWeather);
            InvokeRepeating(nameof(RefreshWeather), 0f, updateIntervalMinutes * 60f);
        }

        private void Update()
        {
            updateTimer += Time.deltaTime;
        }

        public async void RefreshWeather()
        {
            try
            {
                string url = $"https://api.openweathermap.org/data/2.5/weather?q={defaultCity}&appid={apiKey}&units=imperial";
                
                using (var request = new UnityEngine.Networking.UnityWebRequest(url))
                {
                    request.downloadHandler = new UnityEngine.Networking.DownloadHandlerBuffer();
                    await request.SendWebRequest();

                    if (request.result == UnityEngine.Networking.UnityWebRequest.Result.Success)
                    {
                        var data = JsonConvert.DeserializeObject<WeatherData>(request.downloadHandler.text);
                        UpdateDisplay(data);
                    }
                    else
                    {
                        Debug.LogWarning($"[Weather] API Error: {request.error}");
                        SetErrorState();
                    }
                }
            }
            catch (Exception e)
            {
                Debug.LogError($"[Weather] Exception: {e.Message}");
                SetErrorState();
            }
        }

        private void UpdateDisplay(WeatherData data)
        {
            if (data == null) return;

            locationText.text = data.name.ToUpper();
            temperatureText.text = $"{Mathf.RoundToInt(data.main.temp)}°";
            conditionText.text = data.weather[0].description.ToUpper();
            humidityText.text = $"{data.main.humidity}%";
            windText.text = $"{data.wind.speed} MPH {DegToCardinal(data.wind.deg)}";
            lastUpdatedText.text = $"UPDATED {DateTime.Now:HH:mm}";

            LoadConditionIcon(data.weather[0].icon);
        }

        private string DegToCardinal(int deg)
        {
            string[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
            return dirs[Mathf.RoundToInt(deg / 45f) % 8];
        }

        private async void LoadConditionIcon(string iconCode)
        {
            string url = $"https://openweathermap.org/img/wn/{iconCode}@2x.png";
            using (var request = UnityEngine.Networking.UnityWebRequestTexture.GetTexture(url))
            {
                await request.SendWebRequest();
                if (request.result == UnityEngine.Networking.UnityWebRequest.Result.Success)
                {
                    var texture = UnityEngine.Networking.DownloadHandlerTexture.GetContent(request);
                    conditionIcon.sprite = Sprite.Create(texture, new Rect(0, 0, texture.width, texture.height), new Vector2(0.5f, 0.5f));
                }
            }
        }

        private void SetErrorState()
        {
            temperatureText.text = "--°";
            conditionText.text = "DATA LINK DOWN";
            lastUpdatedText.text = "RETRYING...";
        }

        public override void OnDataUpdated(object data)
        {
            if (data is WeatherData weather)
                UpdateDisplay(weather);
        }
    }
}