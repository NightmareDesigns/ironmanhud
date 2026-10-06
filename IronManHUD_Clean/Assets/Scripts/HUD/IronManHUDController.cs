using UnityEngine;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;
using System.Collections.Generic;
using TMPro;
using UnityEngine.UI;

namespace StarkIndustries.HUD
{
    public enum HUDMode { Hybrid, Matrix }

    public class IronManHUDController : MonoBehaviour
    {
        [Header("Mode Settings")]
        [SerializeField] private HUDMode currentMode = HUDMode.Hybrid;
        [SerializeField] private Camera arCamera;
        [SerializeField] private Canvas hudCanvas;
        [SerializeField] private Material matrixSkybox;

        [Header("HUD Panels")]
        [SerializeField] private GameObject orbitalMenu;
        [SerializeField] private GameObject weatherPanel;
        [SerializeField] private GameObject messagesPanel;
        [SerializeField] private GameObject systemStatusPanel;
        [SerializeField] private GameObject jessicaAvatar;

        [Header("Visual Effects")]
        [SerializeField] private ParticleSystem bootParticles;
        [SerializeField] private ParticleSystem modeSwitchParticles;
        [SerializeField] private AudioSource hudAudio;
        [SerializeField] private AudioClip bootSound;
        [SerializeField] private AudioClip modeSwitchSound;
        [SerializeField] private AudioClip panelOpenSound;
        [SerializeField] private AudioClip panelCloseSound;

        [Header("Animation")]
        [SerializeField] private float panelAnimationDuration = 0.3f;
        [SerializeField] private AnimationCurve panelAnimationCurve = AnimationCurve.EaseInOut(0, 0, 1, 1);

        private ARSession arSession;
        private ARPlaneManager planeManager;
        private Dictionary<string, HUDPanel> panels = new();
        private bool isInitialized = false;
        private Material originalSkybox;

        public HUDMode CurrentMode => currentMode;
        public static IronManHUDController Instance { get; private set; }

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

        private async void Start()
        {
            await InitializeHUD();
        }

        private async System.Threading.Tasks.Task InitializeHUD()
        {
            arSession = FindObjectOfType<ARSession>();
            planeManager = FindObjectOfType<ARPlaneManager>();

            originalSkybox = RenderSettings.skybox;

            RegisterPanels();
            HideAllPanels();

            if (bootParticles != null) bootParticles.Play();
            if (hudAudio != null && bootSound != null) hudAudio.PlayOneShot(bootSound);

            await AnimateBootSequence();

            isInitialized = true;
            Debug.Log("[JARVIS] HUD systems online. Welcome back, sir.");
        }

        private void RegisterPanels()
        {
            RegisterPanel("weather", weatherPanel);
            RegisterPanel("messages", messagesPanel);
            RegisterPanel("system", systemStatusPanel);
            RegisterPanel("jessica", jessicaAvatar);
            RegisterPanel("orbital", orbitalMenu);
        }

        private void RegisterPanel(string id, GameObject panel)
        {
            if (panel == null) return;
            var hudPanel = panel.GetComponent<HUDPanel>() ?? panel.AddComponent<HUDPanel>();
            hudPanel.Initialize(id, this);
            panels[id] = hudPanel;
        }

        private void HideAllPanels()
        {
            foreach (var panel in panels.Values)
                panel.HideInstant();
        }

        private async System.Threading.Tasks.Task AnimateBootSequence()
        {
            var panelsInOrder = new[] { "system", "orbital", "jessica" };
            foreach (var id in panelsInOrder)
            {
                if (panels.TryGetValue(id, out var panel))
                {
                    await panel.ShowAnimated();
                    await System.Threading.Tasks.Task.Delay(150);
                }
            }
        }

        public async void TogglePanel(string panelId)
        {
            if (!panels.TryGetValue(panelId, out var panel)) return;

            if (panel.IsVisible)
                await panel.HideAnimated();
            else
                await panel.ShowAnimated();
        }

        public async void OpenPanel(string panelId)
        {
            if (panels.TryGetValue(panelId, out var panel) && !panel.IsVisible)
                await panel.ShowAnimated();
        }

        public async void ClosePanel(string panelId)
        {
            if (panels.TryGetValue(panelId, out var panel) && panel.IsVisible)
                await panel.HideAnimated();
        }

        public async void SetMode(HUDMode mode)
        {
            if (currentMode == mode) return;

            currentMode = mode;

            if (modeSwitchParticles != null) modeSwitchParticles.Play();
            if (hudAudio != null && modeSwitchSound != null) hudAudio.PlayOneShot(modeSwitchSound);

            if (mode == HUDMode.Matrix)
            {
                EnterMatrixMode();
            }
            else
            {
                ExitMatrixMode();
            }

            await System.Threading.Tasks.Task.Delay(300);
            NotifyJessicaModeChange(mode);
        }

        private void EnterMatrixMode()
        {
            if (arCamera != null)
            {
                arCamera.clearFlags = CameraClearFlags.Skybox;
                arCamera.backgroundColor = Color.black;
            }
            if (matrixSkybox != null)
                RenderSettings.skybox = matrixSkybox;

            if (planeManager != null)
                planeManager.enabled = false;

            ClosePanel("weather");
            ClosePanel("messages");
            OpenPanel("orbital");
            OpenPanel("jessica");

            Time.timeScale = 1f;
        }

        private void ExitMatrixMode()
        {
            if (arCamera != null)
            {
                arCamera.clearFlags = CameraClearFlags.Depth;
                arCamera.backgroundColor = new Color(0, 0, 0, 0);
            }
            RenderSettings.skybox = originalSkybox;

            if (planeManager != null)
                planeManager.enabled = true;

            ClosePanel("orbital");
            OpenPanel("weather");
            OpenPanel("system");
        }

        private void NotifyJessicaModeChange(HUDMode mode)
        {
            var jessica = FindObjectOfType<JessicaClient>();
            jessica?.OnModeChanged(mode);
        }

        public void OnVoiceCommand(string command)
        {
            command = command.ToLower().Trim();
            Debug.Log($"[HUD] Voice command: {command}");

            if (command.Contains("matrix") || command.Contains("full immersion"))
                SetMode(HUDMode.Matrix);
            else if (command.Contains("hybrid") || command.Contains("reality") || command.Contains("normal"))
                SetMode(HUDMode.Hybrid);
            else if (command.Contains("weather"))
                TogglePanel("weather");
            else if (command.Contains("message") || command.Contains("notification"))
                TogglePanel("messages");
            else if (command.Contains("system") || command.Contains("status"))
                TogglePanel("system");
            else if (command.Contains("jessica") || command.Contains("assistant"))
                TogglePanel("jessica");
            else if (command.Contains("menu") || command.Contains("orbital"))
                TogglePanel("orbital");
            else if (command.Contains("close all") || command.Contains("clear"))
                CloseAllPanels();
            else
                JessicaClient.Instance?.ProcessQuery(command);
        }

        public void CloseAllPanels()
        {
            foreach (var panel in panels.Values)
                if (panel.IsVisible) panel.HideAnimated();
        }

        private void Update()
        {
            if (!isInitialized) return;

            if (Keyboard.current.escapeKey.wasPressedThisFrame)
                SetMode(currentMode == HUDMode.Hybrid ? HUDMode.Matrix : HUDMode.Hybrid);
        }

        private void OnDestroy()
        {
            RenderSettings.skybox = originalSkybox;
        }
    }
}