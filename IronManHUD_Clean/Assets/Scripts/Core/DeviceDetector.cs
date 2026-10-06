using UnityEngine;
using System.Collections.Generic;

namespace StarkIndustries.Core
{
    public enum DeviceProfile { Pixel10ProXL, GalaxyS24, GalaxyS24Ultra, GenericAndroid, Unknown }

    public static class DeviceDetector
    {
        public static DeviceProfile CurrentProfile { get; private set; } = DeviceProfile.Unknown;
        public static string DeviceModel { get; private set; } = "";
        public static string DeviceManufacturer { get; private set; } = "";

        public static void Detect()
        {
            DeviceModel = SystemInfo.deviceModel;
            DeviceManufacturer = SystemInfo.deviceType.ToString();

            string model = DeviceModel.ToLower();
            string manufacturer = SystemInfo.deviceName.ToLower();

            if (model.Contains("pixel") && model.Contains("10") && model.Contains("pro") && model.Contains("xl"))
                CurrentProfile = DeviceProfile.Pixel10ProXL;
            else if (model.Contains("sm-s921") || model.Contains("s24") && model.Contains("ultra"))
                CurrentProfile = DeviceProfile.GalaxyS24Ultra;
            else if (model.Contains("sm-s921") || model.Contains("s24"))
                CurrentProfile = DeviceProfile.GalaxyS24;
            else if (manufacturer.Contains("samsung") || manufacturer.Contains("galaxy"))
                CurrentProfile = DeviceProfile.GalaxyS24;
            else if (manufacturer.Contains("google") || manufacturer.Contains("pixel"))
                CurrentProfile = DeviceProfile.Pixel10ProXL;
            else
                CurrentProfile = DeviceProfile.GenericAndroid;

            Debug.Log($"[DeviceDetector] Profile: {CurrentProfile} | Model: {DeviceModel} | Manufacturer: {DeviceManufacturer}");
        }

        public static DeviceSettings GetSettings()
        {
            return CurrentProfile switch
            {
                DeviceProfile.Pixel10ProXL => new DeviceSettings
                {
                    targetFrameRate = 90,
                    renderScale = 1.0f,
                    useVulkan = true,
                    enableFoveatedRendering = true,
                    thermalThrottleThreshold = 45f,
                    batteryOptimization = false,
                    usbCVersion = "3.2",
                    displayRefreshRate = 120,
                    supportsHandTracking = true,
                    supportsEyeTracking = true,
                    recommendedQualityLevel = 2
                },
                DeviceProfile.GalaxyS24 => new DeviceSettings
                {
                    targetFrameRate = 60,
                    renderScale = 0.9f,
                    useVulkan = true,
                    enableFoveatedRendering = false,
                    thermalThrottleThreshold = 42f,
                    batteryOptimization = true,
                    usbCVersion = "3.2",
                    displayRefreshRate = 120,
                    supportsHandTracking = true,
                    supportsEyeTracking = false,
                    recommendedQualityLevel = 1
                },
                DeviceProfile.GalaxyS24Ultra => new DeviceSettings
                {
                    targetFrameRate = 90,
                    renderScale = 1.0f,
                    useVulkan = true,
                    enableFoveatedRendering = true,
                    thermalThrottleThreshold = 44f,
                    batteryOptimization = false,
                    usbCVersion = "3.2",
                    displayRefreshRate = 120,
                    supportsHandTracking = true,
                    supportsEyeTracking = false,
                    recommendedQualityLevel = 2
                },
                _ => new DeviceSettings
                {
                    targetFrameRate = 60,
                    renderScale = 0.8f,
                    useVulkan = true,
                    enableFoveatedRendering = false,
                    thermalThrottleThreshold = 40f,
                    batteryOptimization = true,
                    usbCVersion = "2.0",
                    displayRefreshRate = 60,
                    supportsHandTracking = false,
                    supportsEyeTracking = false,
                    recommendedQualityLevel = 0
                }
            };
        }

        public static void ApplySettings()
        {
            var settings = GetSettings();
            Application.targetFrameRate = settings.targetFrameRate;
            QualitySettings.SetQualityLevel(settings.recommendedQualityLevel, true);
            
            if (settings.useVulkan)
            {
                PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { UnityEngine.Rendering.GraphicsDeviceType.Vulkan, UnityEngine.Rendering.GraphicsDeviceType.OpenGLES3 });
            }

            Debug.Log($"[DeviceDetector] Applied settings for {CurrentProfile}: {settings.targetFrameRate}fps, Quality Level {settings.recommendedQualityLevel}");
        }
    }

    public struct DeviceSettings
    {
        public int targetFrameRate;
        public float renderScale;
        public bool useVulkan;
        public bool enableFoveatedRendering;
        public float thermalThrottleThreshold;
        public bool batteryOptimization;
        public string usbCVersion;
        public int displayRefreshRate;
        public bool supportsHandTracking;
        public bool supportsEyeTracking;
        public int recommendedQualityLevel;
    }
}