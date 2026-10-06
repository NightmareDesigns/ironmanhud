using UnityEngine;
using System.Threading.Tasks;
using DG.Tweening;

namespace StarkIndustries.HUD
{
    public class HUDPanel : MonoBehaviour
    {
        [Header("Panel Config")]
        [SerializeField] private string panelId;
        [SerializeField] private bool showOnStart = false;
        [SerializeField] private Vector3 showPosition = Vector3.zero;
        [SerializeField] private Vector3 hidePosition = new Vector3(0, -1000, 0);
        [SerializeField] private Vector3 showScale = Vector3.one;
        [SerializeField] private Vector3 hideScale = Vector3.one * 0.8f;
        [SerializeField] private float showAlpha = 1f;
        [SerializeField] private float hideAlpha = 0f;

        [Header("Animation")]
        [SerializeField] private float animationDuration = 0.3f;
        [SerializeField] private Ease showEase = Ease.OutBack;
        [SerializeField] private Ease hideEase = Ease.InBack;

        private CanvasGroup canvasGroup;
        private RectTransform rectTransform;
        private IronManHUDController controller;
        private bool isVisible = false;
        private bool isAnimating = false;

        public string PanelId => panelId;
        public bool IsVisible => isVisible;

        public void Initialize(string id, IronManHUDController hudController)
        {
            panelId = id;
            controller = hudController;

            rectTransform = GetComponent<RectTransform>();
            canvasGroup = GetComponent<CanvasGroup>() ?? gameObject.AddComponent<CanvasGroup>();

            if (!showOnStart)
                HideInstant();
        }

        public async Task ShowAnimated()
        {
            if (isVisible || isAnimating) return;
            isAnimating = true;
            gameObject.SetActive(true);

            rectTransform.anchoredPosition = hidePosition;
            rectTransform.localScale = hideScale;
            canvasGroup.alpha = hideAlpha;

            var seq = DOTween.Sequence();
            seq.Join(rectTransform.DOAnchorPos(showPosition, animationDuration).SetEase(showEase));
            seq.Join(rectTransform.DOScale(showScale, animationDuration).SetEase(showEase));
            seq.Join(canvasGroup.DOFade(showAlpha, animationDuration * 0.8f).SetEase(Ease.OutQuad));
            seq.OnComplete(() => { isVisible = true; isAnimating = false; });

            await seq.AsyncWaitForCompletion();
        }

        public async Task HideAnimated()
        {
            if (!isVisible || isAnimating) return;
            isAnimating = true;

            var seq = DOTween.Sequence();
            seq.Join(rectTransform.DOAnchorPos(hidePosition, animationDuration).SetEase(hideEase));
            seq.Join(rectTransform.DOScale(hideScale, animationDuration).SetEase(hideEase));
            seq.Join(canvasGroup.DOFade(hideAlpha, animationDuration * 0.8f).SetEase(Ease.InQuad));
            seq.OnComplete(() => { isVisible = false; isAnimating = false; gameObject.SetActive(false); });

            await seq.AsyncWaitForCompletion();
        }

        public void ShowInstant()
        {
            gameObject.SetActive(true);
            rectTransform.anchoredPosition = showPosition;
            rectTransform.localScale = showScale;
            canvasGroup.alpha = showAlpha;
            isVisible = true;
        }

        public void HideInstant()
        {
            rectTransform.anchoredPosition = hidePosition;
            rectTransform.localScale = hideScale;
            canvasGroup.alpha = hideAlpha;
            isVisible = false;
            gameObject.SetActive(false);
        }

        public virtual void OnDataUpdated(object data) { }
    }
}