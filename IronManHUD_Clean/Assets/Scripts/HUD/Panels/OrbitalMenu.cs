using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System.Collections.Generic;
using DG.Tweening;

namespace StarkIndustries.HUD.Panels
{
    [Serializable]
    public class OrbitalMenuItem
    {
        public string id;
        public string label;
        public Sprite icon;
        public Color color = new Color(0, 1f, 0.8f);
        public System.Action onSelect;
    }

    public class OrbitalMenu : HUDPanel
    {
        [Header("Menu Config")]
        [SerializeField] private float radius = 300f;
        [SerializeField] private float itemScale = 1f;
        [SerializeField] private float centerScale = 1.3f;
        [SerializeField] private float rotationSpeed = 5f;
        [SerializeField] private bool autoRotate = true;

        [Header("UI References")]
        [SerializeField] private Transform itemsContainer;
        [SerializeField] private GameObject menuItemPrefab;
        [SerializeField] private TextMeshProUGUI centerLabel;
        [SerializeField] private Image centerIcon;
        [SerializeField] private ParticleSystem activationParticles;

        [Header("Items")]
        [SerializeField] private List<OrbitalMenuItem> menuItems = new();

        private List<GameObject> itemObjects = new();
        private int selectedIndex = 0;
        private float currentAngle = 0f;
        private bool isExpanded = false;

        protected override void Start()
        {
            base.Start();
            BuildMenu();
            HideInstant();
        }

        private void BuildMenu()
        {
            if (menuItems.Count == 0) SetupDefaultItems();

            float angleStep = 360f / menuItems.Count;

            for (int i = 0; i < menuItems.Count; i++)
            {
                var item = Instantiate(menuItemPrefab, itemsContainer);
                itemObjects.Add(item);

                var data = menuItems[i];
                float angle = i * angleStep - 90f;

                var btn = item.GetComponent<Button>();
                var iconImg = item.transform.Find("Icon")?.GetComponent<Image>();
                var labelTxt = item.transform.Find("Label")?.GetComponent<TextMeshProUGUI>();
                var glowImg = item.transform.Find("Glow")?.GetComponent<Image>();

                if (iconImg && data.icon) iconImg.sprite = data.icon;
                if (iconImg) iconImg.color = data.color;
                if (labelTxt) labelTxt.text = data.label;
                if (glowImg) glowImg.color = data.color;

                int index = i;
                btn?.onClick.AddListener(() => OnItemSelected(index));

                item.transform.localRotation = Quaternion.Euler(0, 0, angle);
                item.transform.localPosition = Quaternion.Euler(0, 0, angle) * Vector3.up * radius;
                item.transform.localScale = Vector3.one * itemScale;
            }

            UpdateSelection(0);
        }

        private void SetupDefaultItems()
        {
            menuItems = new List<OrbitalMenuItem>
            {
                new() { id = "weather", label = "WEATHER", color = new Color(0, 0.8f, 1f), onSelect = () => TogglePanel("weather") },
                new() { id = "messages", label = "MESSAGES", color = new Color(0, 1f, 0.5f), onSelect = () => TogglePanel("messages") },
                new() { id = "system", label = "SYSTEMS", color = new Color(1f, 0.6f, 0), onSelect = () => TogglePanel("system") },
                new() { id = "jessica", label = "JESSICA", color = new Color(0.8f, 0.4f, 1f), onSelect = () => TogglePanel("jessica") },
                new() { id = "map", label = "NAVIGATION", color = new Color(1f, 0.9f, 0), onSelect = () => Debug.Log("Open Navigation") },
                new() { id = "suit", label = "SUIT STATUS", color = new Color(1f, 0.3f, 0.3f), onSelect = () => Debug.Log("Open Suit Status") },
                new() { id = "scan", label = "SCAN", color = new Color(0, 1f, 0.8f), onSelect = () => Debug.Log("Initiate Scan") },
                new() { id = "mode", label = "MODE", color = new Color(1f, 1f, 1f), onSelect = () => IronManHUDController.Instance?.SetMode(
                    IronManHUDController.Instance.CurrentMode == IronManHUD.HUDMode.Hybrid ? IronManHUD.HUDMode.Matrix : IronManHUD.HUDMode.Hybrid) }
            };
        }

        public override async Task ShowAnimated()
        {
            isExpanded = true;
            gameObject.SetActive(true);

            if (activationParticles) activationParticles.Play();

            var seq = DOTween.Sequence();
            
            for (int i = 0; i < itemObjects.Count; i++)
            {
                var item = itemObjects[i];
                float delay = i * 0.03f;
                float angle = (i * 360f / itemObjects.Count) - 90f;

                item.transform.localScale = Vector3.zero;
                item.transform.localRotation = Quaternion.Euler(0, 0, angle - 180f);

                seq.Insert(delay, item.transform.DOScale(Vector3.one * itemScale, 0.4f).SetEase(Ease.OutBack));
                seq.Insert(delay, item.transform.DOLocalRotate(new Vector3(0, 0, angle), 0.4f).SetEase(Ease.OutCubic));
            }

            if (centerLabel) centerLabel.transform.DOScale(Vector3.one * centerScale, 0.3f).SetEase(Ease.OutBack).From(Vector3.zero);
            
            await seq.AsyncWaitForCompletion();
            isVisible = true;
        }

        public override async Task HideAnimated()
        {
            isExpanded = false;
            var seq = DOTween.Sequence();

            for (int i = 0; i < itemObjects.Count; i++)
            {
                var item = itemObjects[i];
                float delay = (itemObjects.Count - 1 - i) * 0.02f;
                seq.Insert(delay, item.transform.DOScale(Vector3.zero, 0.25f).SetEase(Ease.InBack));
            }

            if (centerLabel) centerLabel.transform.DOScale(Vector3.zero, 0.2f).SetEase(Ease.InBack);
            
            await seq.AsyncWaitForCompletion();
            gameObject.SetActive(false);
            isVisible = false;
        }

        private void Update()
        {
            if (!isVisible || !isExpanded || !autoRotate) return;

            currentAngle += rotationSpeed * Time.deltaTime;
            itemsContainer.localRotation = Quaternion.Euler(0, 0, currentAngle);
        }

        private void OnItemSelected(int index)
        {
            selectedIndex = index;
            UpdateSelection(index);
            menuItems[index].onSelect?.Invoke();

            var item = itemObjects[index];
            item.transform.DOPunchScale(Vector3.one * 0.2f, 0.2f, 5, 0.5f);
        }

        private void UpdateSelection(int index)
        {
            for (int i = 0; i < itemObjects.Count; i++)
            {
                var item = itemObjects[i];
                var glow = item.transform.Find("Glow")?.GetComponent<Image>();
                var outline = item.transform.Find("Outline")?.GetComponent<Outline>();

                bool selected = i == index;
                if (glow) glow.enabled = selected;
                if (outline) outline.enabled = selected;
                item.transform.localScale = Vector3.one * (selected ? centerScale : itemScale);
            }

            var data = menuItems[index];
            if (centerLabel) centerLabel.text = data.label;
            if (centerIcon) centerIcon.color = data.color;
        }

        public void RotateNext()
        {
            int next = (selectedIndex + 1) % menuItems.Count;
            OnItemSelected(next);
        }

        public void RotatePrevious()
        {
            int prev = (selectedIndex - 1 + menuItems.Count) % menuItems.Count;
            OnItemSelected(prev);
        }

        private void TogglePanel(string panelId)
        {
            IronManHUDController.Instance?.TogglePanel(panelId);
        }
    }
}