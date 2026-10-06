using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System.Collections.Generic;

namespace StarkIndustries.HUD.Panels
{
    public class SystemStatusPanel : HUDPanel
    {
        [Header("UI References")]
        [SerializeField] private TextMeshProUGUI fpsText;
        [SerializeField] private TextMeshProUGUI cpuText;
        [SerializeField] private TextMeshProUGUI gpuText;
        [SerializeField] private TextMeshProUGUI memoryText;
        [SerializeField] private TextMeshProUGUI batteryText;
        [SerializeField] private TextMeshProUGUI temperatureText;
        [SerializeField] private TextMeshProUGUI trackingText;
        [SerializeField] private TextMeshProUGUI networkText;
        [SerializeField] private TextMeshProUGUI jessicaStatusText;

        [Header("Visual")]
        [SerializeField] private Image[] statusIndicators;
        [SerializeField] private Color optimalColor = new Color(0, 1f, 0.5f);
        [SerializeField] private Color warningColor = new Color(1f, 0.8f, 0);
        [SerializeField] private Color criticalColor = new Color(1f, 0.3f, 0.3f);

        private float updateInterval = 1f;
        private float timer = 0f;

        private void Update()
        {
            timer += Time.deltaTime;
            if (timer >= updateInterval)
            {
                UpdateStats();
                timer = 0f;
            }
        }

        private void UpdateStats()
        {
            fpsText.text = $"{Mathf.RoundToInt(1f / Time.unscaledDeltaTime)} FPS";
            
#if UNITY_ANDROID && !UNITY_EDITOR
            UpdateAndroidStats();
#else
            UpdateEditorStats();
#endif

            UpdateTrackingStatus();
            UpdateJessicaStatus();
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private void UpdateAndroidStats()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                try
                {
                    // Battery
                    using (var batteryManager = activity.Call<AndroidJavaObject>("getSystemService", "batterymanager"))
                    {
                        int level = batteryManager.Call<int>("getIntProperty", 4); // BATTERY_PROPERTY_CAPACITY
                        int status = batteryManager.Call<int>("getIntProperty", 5); // BATTERY_PROPERTY_STATUS
                        batteryText.text = $"{level}% {(status == 2 ? "CHARGING" : "DISCHARGING")}";
                        SetIndicatorColor(0, level > 20 ? optimalColor : (level > 10 ? warningColor : criticalColor));
                    }

                    // Memory
                    using (var memoryInfo = new AndroidJavaObject("android.app.ActivityManager$MemoryInfo"))
                    using (var activityManager = activity.Call<AndroidJavaObject>("getSystemService", "activity"))
                    {
                        activityManager.Call("getMemoryInfo", memoryInfo);
                        long availMB = memoryInfo.Get<long>("availMem") / 1048576;
                        long totalMB = memoryInfo.Get<long>("totalMem") / 1048576;
                        float percent = (float)availMB / totalMB * 100;
                        memoryText.text = $"{availMB}MB / {totalMB}MB FREE";
                        SetIndicatorColor(1, percent > 30 ? optimalColor : (percent > 15 ? warningColor : criticalColor));
                    }

                    // Thermal
                    using (var thermalManager = activity.Call<AndroidJavaObject>("getSystemService", "thermal"))
                    {
                        // Simplified - real implementation would use ThermalManager APIs
                        temperatureText.text = "NOMINAL";
                        SetIndicatorColor(2, optimalColor);
                    }
                }
                catch (Exception e)
                {
                    Debug.LogWarning($"[SystemStatus] Android stats error: {e.Message}");
                }
            }
        }
#endif

        private void UpdateEditorStats()
        {
            batteryText.text = "100% PLUGGED";
            memoryText.text = $"{System.GC.GetTotalMemory(false) / 1048576}MB MANAGED";
            temperatureText.text = "NOMINAL";
            SetIndicatorColor(0, optimalColor);
            SetIndicatorColor(1, optimalColor);
            SetIndicatorColor(2, optimalColor);
        }

        private void UpdateTrackingStatus()
        {
            var arSession = FindObjectOfType<UnityEngine.XR.ARFoundation.ARSession>();
            if (arSession != null)
            {
                string state = arSession.subsystem?.running == true ? "TRACKING" : "INITIALIZING";
                trackingText.text = state;
                SetIndicatorColor(3, state == "TRACKING" ? optimalColor : warningColor);
            }
            else
            {
                trackingText.text = "NO AR SESSION";
                SetIndicatorColor(3, criticalColor);
            }
        }

        private void UpdateJessicaStatus()
        {
            bool jessicaOnline = JessicaClient.Instance != null && !JessicaClient.Instance.IsProcessing;
            jessicaStatusText.text = jessicaOnline ? "ONLINE" : "PROCESSING";
            SetIndicatorColor(4, jessicaOnline ? optimalColor : warningColor);
        }

        private void SetIndicatorColor(int index, Color color)
        {
            if (index >= 0 && index < statusIndicators.Length && statusIndicators[index] != null)
                statusIndicators[index].color = color;
        }

        public override void OnDataUpdated(object data) { }
    }
}