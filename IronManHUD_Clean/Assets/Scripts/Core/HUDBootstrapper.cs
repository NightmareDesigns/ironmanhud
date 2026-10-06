using UnityEngine;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.Management;
using UnityEngine.Rendering.Universal;

namespace StarkIndustries.Core
{
    public class HUDBootstrapper : MonoBehaviour
    {
        [Header("Prefab References")]
        [SerializeField] private GameObject hudControllerPrefab;
        [SerializeField] private GameObject voiceManagerPrefab;
        [SerializeField] private GameObject jessicaClientPrefab;
        [SerializeField] private GameObject hudCanvasPrefab;
        [SerializeField] private GameObject arSessionPrefab;

        [Header("Auto Setup")]
        [SerializeField] private bool autoInitialize = true;
        [SerializeField] private bool requestPermissions = true;
        [SerializeField] private bool applyDeviceProfile = true;

        private void Awake()
        {
            if (autoInitialize)
                InitializeHUD();
        }

        private async void InitializeHUD()
        {
            Debug.Log("[BOOTSTRAPPER] Initializing Iron Man HUD...");

            DeviceDetector.Detect();
            
            if (applyDeviceProfile)
                DeviceDetector.ApplySettings();

            SetupXR();
            await System.Threading.Tasks.Task.Delay(500);
            
            SpawnCoreSystems();
            SetupURP();
            
            if (requestPermissions)
                RequestAndroidPermissions();

            ConfigureForDevice();

            Debug.Log($"[BOOTSTRAPPER] HUD initialization complete. JARVIS online on {DeviceDetector.CurrentProfile}.");
        }

        private void ConfigureForDevice()
        {
            var settings = DeviceDetector.GetSettings();
            var hudController = FindObjectOfType<IronManHUD.IronManHUDController>();

            // Adjust quality settings per device
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = settings.targetFrameRate;

            // Configure XR settings based on device capabilities
            var xrSettings = FindObjectOfType<XRGeneralSettings>();
            var activeLoader = xrSettings?.Manager?.activeLoader;
            if (activeLoader != null)
            {
                var loaderType = activeLoader.GetType();
                Debug.Log($"[Bootstrapper] Active XR Loader: {loaderType.Name}");
                // XREAL SDK 3.x config would go here via reflection if needed
                // Hand/eye tracking enabled via XR settings
            }

            // Samsung-specific: disable battery optimization popup
            if (DeviceDetector.CurrentProfile == DeviceProfile.GalaxyS24 || 
                DeviceDetector.CurrentProfile == DeviceProfile.GalaxyS24Ultra)
            {
                DisableSamsungBatteryOptimization();
            }
        }

        private void DisableSamsungBatteryOptimization()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var powerManager = activity.Call<AndroidJavaObject>("getSystemService", "power"))
                using (var packageName = activity.Call<string>("getPackageName"))
                {
                    bool isIgnoring = powerManager.Call<bool>("isIgnoringBatteryOptimizations", packageName);
                    if (!isIgnoring)
                    {
                        using (var intent = new AndroidJavaObject("android.content.Intent", "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
                        {
                            intent.Call<AndroidJavaObject>("setData", new AndroidJavaObject("android.net.Uri", "package:" + packageName));
                            activity.Call("startActivity", intent);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[Bootstrapper] Samsung battery optimization check failed: {e.Message}");
            }
#endif
        }

        private void SetupXR()
        {
            var xrManager = FindObjectOfType<XRGeneralSettings>();
            if (xrManager == null)
            {
                var go = new GameObject("XR General Settings");
                xrManager = go.AddComponent<XRGeneralSettings>();
                xrManager.Manager = go.AddComponent<XRManagerSettings>();
            }

            var loaderManager = xrManager.Manager.activeLoader;
            if (loaderManager == null)
            {
                xrManager.Manager.InitializeLoaderSync();
            }
        }

        private void SpawnCoreSystems()
        {
            // AR Session
            if (arSessionPrefab != null)
                Instantiate(arSessionPrefab);
            else
                CreateDefaultARSession();

            // HUD Controller
            if (hudControllerPrefab != null)
                Instantiate(hudControllerPrefab);
            else
                CreateDefaultHUDController();

            // Voice Manager
            if (voiceManagerPrefab != null)
                Instantiate(voiceManagerPrefab);
            else
                CreateDefaultVoiceManager();

            // Jessica Client
            if (jessicaClientPrefab != null)
                Instantiate(jessicaClientPrefab);
            else
                CreateDefaultJessicaClient();

            // HUD Canvas
            if (hudCanvasPrefab != null)
                Instantiate(hudCanvasPrefab);
            else
                CreateDefaultHUDCanvas();
        }

        private void CreateDefaultARSession()
        {
            var go = new GameObject("AR Session");
            go.AddComponent<ARSession>();
            var origin = go.AddComponent<ARSessionOrigin>();
            go.AddComponent<ARPlaneManager>();
            go.AddComponent<ARRaycastManager>();
            go.AddComponent<ARAnchorManager>();

            // Configure for device
            var settings = DeviceDetector.GetSettings();
            var planeManager = go.GetComponent<ARPlaneManager>();
            if (planeManager != null)
            {
                planeManager.detectionMode = UnityEngine.XR.ARSubsystems.PlaneDetectionMode.Horizontal | UnityEngine.XR.ARSubsystems.PlaneDetectionMode.Vertical;
            }
        }

        private void CreateDefaultHUDController()
        {
            var go = new GameObject("IronManHUDController");
            var controller = go.AddComponent<IronManHUD.IronManHUDController>();
            
            var camera = Camera.main;
            if (camera == null)
            {
                var camGo = new GameObject("AR Camera");
                camera = camGo.AddComponent<Camera>();
                camera.tag = "MainCamera";
                camera.clearFlags = CameraClearFlags.Depth;
                camera.backgroundColor = new Color(0, 0, 0, 0);
                camera.nearClipPlane = 0.01f;
                camera.farClipPlane = 1000f;
                camera.stereoTargetEye = StereoTargetEyeMask.Both;
            }
        }

        private void CreateDefaultVoiceManager()
        {
            var go = new GameObject("VoiceCommandManager");
            go.AddComponent<Voice.VoiceCommandManager>();
        }

        private void CreateDefaultJessicaClient()
        {
            var go = new GameObject("JessicaClient");
            go.AddComponent<AI.JessicaClient>();
        }

        private void CreateDefaultHUDCanvas()
        {
            var go = new GameObject("HUD Canvas");
            var canvas = go.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = 100;
            canvas.targetDisplay = 0;
            go.AddComponent<UnityEngine.UI.GraphicRaycaster>();
            var scaler = go.AddComponent<UnityEngine.UI.CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920, 1080);
            scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            scaler.matchWidthOrHeight = 0.5f;
        }

        private void SetupURP()
        {
            var pipelineAsset = GraphicsSettings.currentRenderPipeline as UniversalRenderPipelineAsset;
            if (pipelineAsset != null)
            {
                var settings = DeviceDetector.GetSettings();
                
                pipelineAsset.supportsCameraDepthTexture = true;
                pipelineAsset.supportsCameraOpaqueTexture = true;
                pipelineAsset.supportsHDR = true;
                pipelineAsset.msaaSampleCount = settings.targetFrameRate >= 90 ? 2 : 4;
                pipelineAsset.renderScale = settings.renderScale;
                
                // Foveated rendering for supported devices
                if (settings.enableFoveatedRendering)
                {
                    // Would need URP foveated rendering feature enabled
                }
            }
        }

        private void RequestAndroidPermissions()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            string[] permissions = {
                "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.BLUETOOTH",
                "android.permission.BLUETOOTH_ADMIN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.NEARBY_WIFI_DEVICES"
            };

            foreach (var perm in permissions)
            {
                if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(perm))
                    UnityEngine.Android.Permission.RequestUserPermission(perm);
            }
#endif
        }
    }
}