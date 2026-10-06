using UnityEngine;
using UnityEngine.Events;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.Controls;
using UnityEngine.XR;
using System.Collections.Generic;
using System.Linq;

namespace StarkIndustries.Input
{
    public enum XrealButton
    {
        None,
        TempleShort,       // Right temple: single press
        TempleLong,        // Right temple: long press (1+ sec)
        TempleDouble,      // Right temple: double tap
        BrightnessCycle,   // Left temple or combo: brightness/2D-3D
        VolumeUp,
        VolumeDown,
        Menu,              // System menu
        TouchpadClick,
        TouchpadSwipeUp,
        TouchpadSwipeDown,
        TouchpadSwipeLeft,
        TouchpadSwipeRight
    }

    [System.Serializable]
    public class ButtonEvent : UnityEvent<XrealButton> { }
    [System.Serializable]
    public class TouchpadEvent : UnityEvent<Vector2> { }

    public class XrealControllerManager : MonoBehaviour
    {
        [Header("Button Events")]
        public ButtonEvent onButtonDown;
        public ButtonEvent onButtonUp;
        public ButtonEvent onButtonLongPress;
        public ButtonEvent onButtonDoubleTap;
        public TouchpadEvent onTouchpadMove;
        public TouchpadEvent onTouchpadClick;

        [Header("Settings")]
        [SerializeField] private float longPressThreshold = 1.0f;
        [SerializeField] private float doubleTapWindow = 0.4f;
        [SerializeField] private float swipeThreshold = 0.3f;

        [Header("Haptic Feedback")]
        [SerializeField] private bool enableHaptics = true;
        [SerializeField] private float hapticIntensity = 0.5f;
        [SerializeField] private float hapticDuration = 0.05f;

        private Dictionary<XrealButton, float> buttonPressTimes = new();
        private Dictionary<XrealButton, int> tapCounts = new();
        private Dictionary<XrealButton, bool> longPressFired = new();
        private Vector2 lastTouchpadPos;
        private float lastTapTime;

        // Input System
        private InputActionMap xrActionMap;
        private InputAction templePressAction;
        private InputAction brightnessAction;
        private InputAction volumeUpAction;
        private InputAction volumeDownAction;
        private InputAction menuAction;
        private InputAction touchpadPositionAction;
        private InputAction touchpadClickAction;
        private InputAction touchpadSwipeAction;

        // XR Input Subsystem
        private List<XRInputSubsystem> inputSubsystems = new();

        public static XrealControllerManager Instance { get; private set; }

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
            InitializeXRInput();
            SetupInputActions();
        }

        private void InitializeXRInput()
        {
            // Get XR Input Subsystems
            SubsystemManager.GetInstances(inputSubsystems);
            foreach (var subsystem in inputSubsystems)
            {
                Debug.Log($"[XrealController] Found XR Input Subsystem: {subsystem.GetType().Name}");
            }

            // Try to find XREAL-specific input subsystem
            var xrealSubsystem = inputSubsystems.FirstOrDefault(s => 
                s.GetType().Name.Contains("XREAL") || 
                s.GetType().Name.Contains("NRSDK") ||
                s.GetType().Name.Contains("Nreal"));
            
            if (xrealSubsystem != null)
            {
                Debug.Log($"[XrealController] Using XREAL subsystem: {xrealSubsystem.GetType().Name}");
            }
        }

        private void SetupInputActions()
        {
            // Create action map for XR input
            xrActionMap = new InputActionMap("XREAL");

            // Temple button (right temple on glasses) - often mapped to primaryButton or menuButton
            templePressAction = xrActionMap.AddAction("TemplePress", InputActionType.Button, "<XRController>{PrimaryButton}");
            templePressAction.performed += ctx => OnButtonDown(XrealButton.TempleShort);
            templePressAction.canceled += ctx => OnButtonUp(XrealButton.TempleShort);

            // Brightness button (left temple)
            brightnessAction = xrActionMap.AddAction("Brightness", InputActionType.Button, "<XRController>{SecondaryButton}");
            brightnessAction.performed += ctx => OnButtonDown(XrealButton.BrightnessCycle);
            brightnessAction.canceled += ctx => OnButtonUp(XrealButton.BrightnessCycle);

            // Volume rocker
            volumeUpAction = xrActionMap.AddAction("VolumeUp", InputActionType.Button, "<Keyboard>/f5"); // Fallback
            volumeUpAction.performed += ctx => OnButtonDown(XrealButton.VolumeUp);
            volumeUpAction.canceled += ctx => OnButtonUp(XrealButton.VolumeUp);

            volumeDownAction = xrActionMap.AddAction("VolumeDown", InputActionType.Button, "<Keyboard>/f6"); // Fallback
            volumeDownAction.performed += ctx => OnButtonDown(XrealButton.VolumeDown);
            volumeDownAction.canceled += ctx => OnButtonUp(XrealButton.VolumeDown);

            // Menu/System button
            menuAction = xrActionMap.AddAction("Menu", InputActionType.Button, "<XRController>{MenuButton}");
            menuAction.performed += ctx => OnButtonDown(XrealButton.Menu);
            menuAction.canceled += ctx => OnButtonUp(XrealButton.Menu);

            // Touchpad
            touchpadPositionAction = xrActionMap.AddAction("TouchpadPosition", InputActionType.Value, "<XRController>{Touchpad}");
            touchpadPositionAction.performed += ctx => OnTouchpadMove(ctx.ReadValue<Vector2>());

            touchpadClickAction = xrActionMap.AddAction("TouchpadClick", InputActionType.Button, "<XRController>{TouchpadPress}");
            touchpadClickAction.performed += ctx => OnTouchpadClick(lastTouchpadPos);

            // Swipe detection from touchpad delta
            touchpadSwipeAction = xrActionMap.AddAction("TouchpadSwipe", InputActionType.Value, "<XRController>{Touchpad}");
            
            xrActionMap.Enable();
        }

        private void OnDestroy()
        {
            xrActionMap?.Disable();
            xrActionMap?.Dispose();
        }

        private void Update()
        {
            // Poll XR input devices directly for glasses buttons
            PollXRDevices();
            
            CheckLongPresses();
            CheckDoubleTaps();

            // Editor fallback
#if UNITY_EDITOR
            PollEditorInput();
#endif
        }

        private void PollXRDevices()
        {
            var devices = InputSystem.devices;
            
            // Check XR Controllers (includes glasses controllers)
            foreach (var device in devices)
            {
                if (device is XRController xrController)
                {
                    PollXRController(xrController);
                }
                // Also check for specific XREAL device layouts
                else if (device.layout.Contains("XREAL") || device.layout.Contains("Nreal") || device.layout.Contains("NRSDK"))
                {
                    PollXRDevice(device);
                }
            }

            // Fallback: Check for Android keycodes that map to glasses buttons
            PollAndroidFallback();
        }

        private void PollXRController(XRController controller)
        {
            // Primary button (temple press)
            var primaryBtn = controller["primaryButton"] as ButtonControl;
            if (primaryBtn != null)
            {
                if (primaryBtn.wasPressedThisFrame) OnButtonDown(XrealButton.TempleShort);
                if (primaryBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.TempleShort);
            }

            // Secondary button (brightness)
            var secondaryBtn = controller["secondaryButton"] as ButtonControl;
            if (secondaryBtn != null)
            {
                if (secondaryBtn.wasPressedThisFrame) OnButtonDown(XrealButton.BrightnessCycle);
                if (secondaryBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.BrightnessCycle);
            }

            // Menu button
            var menuBtn = controller["menuButton"] as ButtonControl;
            if (menuBtn != null)
            {
                if (menuBtn.wasPressedThisFrame) OnButtonDown(XrealButton.Menu);
                if (menuBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.Menu);
            }

            // Touchpad
            var touchpad = controller["touchpad"] as Vector2Control;
            if (touchpad != null)
            {
                var pos = touchpad.ReadValue();
                if (pos != Vector2.zero)
                {
                    OnTouchpadMove(pos);
                }
            }

            var touchpadPress = controller["touchpadPress"] as ButtonControl;
            if (touchpadPress != null)
            {
                if (touchpadPress.wasPressedThisFrame) OnTouchpadClick(lastTouchpadPos);
            }
        }

        private void PollXRDevice(InputDevice device)
        {
            // Generic polling for any XREAL-specific device layout
            try
            {
                // Try common button mappings for XREAL glasses
                var templeBtn = device.GetChildControl<ButtonControl>("templeButton");
                if (templeBtn != null)
                {
                    if (templeBtn.wasPressedThisFrame) OnButtonDown(XrealButton.TempleShort);
                    if (templeBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.TempleShort);
                }

                var brightnessBtn = device.GetChildControl<ButtonControl>("brightnessButton");
                if (brightnessBtn != null)
                {
                    if (brightnessBtn.wasPressedThisFrame) OnButtonDown(XrealButton.BrightnessCycle);
                    if (brightnessBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.BrightnessCycle);
                }

                var volumeUpBtn = device.GetChildControl<ButtonControl>("volumeUp");
                if (volumeUpBtn != null)
                {
                    if (volumeUpBtn.wasPressedThisFrame) OnButtonDown(XrealButton.VolumeUp);
                    if (volumeUpBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.VolumeUp);
                }

                var volumeDownBtn = device.GetChildControl<ButtonControl>("volumeDown");
                if (volumeDownBtn != null)
                {
                    if (volumeDownBtn.wasPressedThisFrame) OnButtonDown(XrealButton.VolumeDown);
                    if (volumeDownBtn.wasReleasedThisFrame) OnButtonUp(XrealButton.VolumeDown);
                }

                // Touchpad
                var touchpad = device.GetChildControl<Vector2Control>("touchpad");
                if (touchpad != null)
                {
                    var pos = touchpad.ReadValue();
                    if (pos != Vector2.zero)
                    {
                        OnTouchpadMove(pos);
                    }
                }
            }
            catch { }
        }

        private void PollAndroidFallback()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            // Android keycodes that may map to Xreal 1s glasses buttons
            // These vary by firmware version
            
            // Temple button often maps to KEYCODE_HEADSETHOOK (79) or KEYCODE_MEDIA_PLAY_PAUSE (85)
            if (Input.GetKeyDown(KeyCode.Escape)) OnButtonDown(XrealButton.TempleShort);
            if (Input.GetKeyUp(KeyCode.Escape)) OnButtonUp(XrealButton.TempleShort);
            
            // Volume rocker
            if (Input.GetKeyDown(KeyCode.VolumeUp)) OnButtonDown(XrealButton.VolumeUp);
            if (Input.GetKeyUp(KeyCode.VolumeUp)) OnButtonUp(XrealButton.VolumeUp);
            if (Input.GetKeyDown(KeyCode.VolumeDown)) OnButtonDown(XrealButton.VolumeDown);
            if (Input.GetKeyUp(KeyCode.VolumeDown)) OnButtonUp(XrealButton.VolumeDown);

            // Brightness/2D-3D toggle - sometimes KEYCODE_BRIGHTNESS_UP (221) or custom
            // Use F5/F6 as fallback in editor
#endif
        }

        private void PollEditorInput()
        {
            // Editor testing keys
            if (Input.GetKeyDown(KeyCode.Alpha1)) OnButtonDown(XrealButton.TempleShort);
            if (Input.GetKeyUp(KeyCode.Alpha1)) OnButtonUp(XrealButton.TempleShort);
            
            if (Input.GetKeyDown(KeyCode.Alpha2)) OnButtonDown(XrealButton.BrightnessCycle);
            if (Input.GetKeyUp(KeyCode.Alpha2)) OnButtonUp(XrealButton.BrightnessCycle);
            
            if (Input.GetKeyDown(KeyCode.Alpha3)) OnButtonDown(XrealButton.VolumeUp);
            if (Input.GetKeyUp(KeyCode.Alpha3)) OnButtonUp(XrealButton.VolumeUp);
            
            if (Input.GetKeyDown(KeyCode.Alpha4)) OnButtonDown(XrealButton.VolumeDown);
            if (Input.GetKeyUp(KeyCode.Alpha4)) OnButtonUp(XrealButton.VolumeDown);
            
            if (Input.GetKeyDown(KeyCode.Alpha5)) OnButtonDown(XrealButton.Menu);
            if (Input.GetKeyUp(KeyCode.Alpha5)) OnButtonUp(XrealButton.Menu);
            
            // Touchpad simulation with mouse
            if (Input.GetMouseButton(0))
            {
                Vector2 pos = new Vector2(
                    (Input.mousePosition.x / Screen.width) * 2 - 1,
                    (Input.mousePosition.y / Screen.height) * 2 - 1
                );
                OnTouchpadMove(pos);
            }
            if (Input.GetMouseButtonDown(0))
                OnTouchpadClick(Input.mousePosition);
        }

        private void OnButtonDown(XrealButton button)
        {
            buttonPressTimes[button] = Time.time;
            longPressFired[button] = false;
            
            if (!tapCounts.ContainsKey(button)) tapCounts[button] = 0;
            tapCounts[button]++;
            
            onButtonDown?.Invoke(button);
            TriggerHaptic();
        }

        private void OnButtonUp(XrealButton button)
        {
            float pressDuration = Time.time - buttonPressTimes.GetValueOrDefault(button, Time.time);
            onButtonUp?.Invoke(button);

            if (pressDuration >= longPressThreshold && !longPressFired.GetValueOrDefault(button, false))
            {
                longPressFired[button] = true;
                XrealButton longVersion = GetLongPressVersion(button);
                if (longVersion != XrealButton.None)
                    onButtonLongPress?.Invoke(longVersion);
            }

            buttonPressTimes.Remove(button);
        }

        private void OnTouchpadMove(Vector2 position)
        {
            Vector2 delta = position - lastTouchpadPos;
            if (delta.magnitude > swipeThreshold)
            {
                DetectSwipe(delta);
            }
            lastTouchpadPos = position;
            onTouchpadMove?.Invoke(position);
        }

        private void OnTouchpadClick(Vector2 position)
        {
            onTouchpadClick?.Invoke(position);
        }

        private void DetectSwipe(Vector2 delta)
        {
            XrealButton swipeButton = XrealButton.None;
            
            if (Mathf.Abs(delta.x) > Mathf.Abs(delta.y))
            {
                swipeButton = delta.x > 0 ? XrealButton.TouchpadSwipeRight : XrealButton.TouchpadSwipeLeft;
            }
            else
            {
                swipeButton = delta.y > 0 ? XrealButton.TouchpadSwipeUp : XrealButton.TouchpadSwipeDown;
            }

            onButtonDown?.Invoke(swipeButton);
            TriggerHaptic(hapticIntensity * 0.5f, hapticDuration);
        }

        private XrealButton GetLongPressVersion(XrealButton button)
        {
            return button switch
            {
                XrealButton.TempleShort => XrealButton.TempleLong,
                XrealButton.BrightnessCycle => XrealButton.BrightnessCycle,
                XrealButton.Menu => XrealButton.Menu,
                _ => XrealButton.None
            };
        }

        private void CheckLongPresses()
        {
            foreach (var kvp in buttonPressTimes.ToList())
            {
                if (longPressFired.GetValueOrDefault(kvp.Key, false)) continue;
                
                float duration = Time.time - kvp.Value;
                if (duration >= longPressThreshold)
                {
                    longPressFired[kvp.Key] = true;
                    XrealButton longVersion = GetLongPressVersion(kvp.Key);
                    if (longVersion != XrealButton.None)
                        onButtonLongPress?.Invoke(longVersion);
                }
            }
        }

        private void CheckDoubleTaps()
        {
            // Double tap detection handled in OnButtonDown via tapCounts
            // Could add coroutine to reset tapCounts after window
        }

        private void TriggerHaptic(float intensity = -1, float duration = -1)
        {
            if (!enableHaptics) return;

            intensity = intensity < 0 ? hapticIntensity : intensity;
            duration = duration < 0 ? hapticDuration : duration;

#if UNITY_ANDROID && !UNITY_EDITOR
            // Vibrate phone (glasses don't have haptics)
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var vibrator = activity.Call<AndroidJavaObject>("getSystemService", "vibrator"))
            {
                if (vibrator.Call<bool>("hasVibrator"))
                {
                    vibrator.Call("vibrate", (long)(duration * 1000));
                }
            }
#else
            Handheld.Vibrate();
#endif
        }

        // Public API for binding
        public void Bind(XrealButton button, UnityAction onDown = null, UnityAction onUp = null, UnityAction onLong = null, UnityAction onDouble = null)
        {
            if (onDown != null) onButtonDown.AddListener(b => { if (b == button) onDown(); });
            if (onUp != null) onButtonUp.AddListener(b => { if (b == button) onUp(); });
            if (onLong != null) onButtonLongPress.AddListener(b => { if (b == button) onLong(); });
            if (onDouble != null) onButtonDoubleTap.AddListener(b => { if (b == button) onDouble(); });
        }

        public void UnbindAll()
        {
            onButtonDown.RemoveAllListeners();
            onButtonUp.RemoveAllListeners();
            onButtonLongPress.RemoveAllListeners();
            onButtonDoubleTap.RemoveAllListeners();
            onTouchpadMove.RemoveAllListeners();
            onTouchpadClick.RemoveAllListeners();
        }

        // Debug: Log all connected XR devices
        [ContextMenu("Log XR Devices")]
        public void LogXRDevices()
        {
            foreach (var device in InputSystem.devices)
            {
                if (device is XRController || device.layout.Contains("XR") || device.layout.Contains("XREAL") || device.layout.Contains("Nreal"))
                {
                    Debug.Log($"[XrealController] Device: {device.name} | Layout: {device.layout} | ID: {device.deviceId}");
                    foreach (var control in device.allControls)
                    {
                        Debug.Log($"  Control: {control.name} | Type: {control.GetType().Name} | Path: {control.path}");
                    }
                }
            }
        }
    }
}