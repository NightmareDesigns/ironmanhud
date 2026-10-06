using UnityEngine;
using UnityEngine.UI;
using TMPro;
using DG.Tweening;

namespace StarkIndustries.HUD.Panels
{
    public class JessicaAvatar : HUDPanel
    {
        [Header("Avatar Visuals")]
        [SerializeField] private Image avatarImage;
        [SerializeField] private ParticleSystem idleParticles;
        [SerializeField] private ParticleSystem speakingParticles;
        [SerializeField] private ParticleSystem thinkingParticles;
        [SerializeField] private float pulseSpeed = 1f;
        [SerializeField] private float pulseIntensity = 0.1f;

        [Header("State Indicators")]
        [SerializeField] private TextMeshProUGUI stateText;
        [SerializeField] private Image statusRing;
        [SerializeField] private Color idleColor = new Color(0, 1f, 0.8f);
        [SerializeField] private Color listeningColor = new Color(0, 0.8f, 1f);
        [SerializeField] private Color thinkingColor = new Color(1f, 0.6f, 0);
        [SerializeField] private Color speakingColor = new Color(0.8f, 0.4f, 1f);

        [Header("Animation")]
        [SerializeField] private float stateTransitionDuration = 0.3f;

        private JessicaState currentState = JessicaState.Idle;
        private Sequence pulseSequence;
        private Material avatarMaterial;

        public enum JessicaState { Idle, Listening, Thinking, Speaking, Error }

        protected override void Start()
        {
            base.Start();
            avatarMaterial = avatarImage?.material ?? avatarImage?.GetComponent<Image>()?.material;
            
            if (idleParticles) idleParticles.Play();
            SetState(JessicaState.Idle);
            StartPulseAnimation();
        }

        private void StartPulseAnimation()
        {
            if (avatarImage == null) return;

            pulseSequence = DOTween.Sequence();
            pulseSequence.Append(avatarImage.transform.DOScale(Vector3.one * (1 + pulseIntensity), 1f / pulseSpeed).SetEase(Ease.InOutSine));
            pulseSequence.Append(avatarImage.transform.DOScale(Vector3.one, 1f / pulseSpeed).SetEase(Ease.InOutSine));
            pulseSequence.SetLoops(-1);
        }

        public void SetState(JessicaState state)
        {
            if (currentState == state) return;
            currentState = state;

            Color targetColor = state switch
            {
                JessicaState.Idle => idleColor,
                JessicaState.Listening => listeningColor,
                JessicaState.Thinking => thinkingColor,
                JessicaState.Speaking => speakingColor,
                JessicaState.Error => Color.red,
                _ => idleColor
            };

            string stateLabel = state switch
            {
                JessicaState.Idle => "STANDBY",
                JessicaState.Listening => "LISTENING",
                JessicaState.Thinking => "PROCESSING",
                JessicaState.Speaking => "SPEAKING",
                JessicaState.Error => "ERROR",
                _ => "UNKNOWN"
            };

            if (stateText) stateText.text = stateLabel;
            if (statusRing) statusRing.DOColor(targetColor, stateTransitionDuration);

            UpdateParticles(state);
        }

        private void UpdateParticles(JessicaState state)
        {
            if (idleParticles) idleParticles.gameObject.SetActive(state == JessicaState.Idle);
            if (thinkingParticles) thinkingParticles.gameObject.SetActive(state == JessicaState.Thinking);
            if (speakingParticles) speakingParticles.gameObject.SetActive(state == JessicaState.Speaking);
        }

        public void OnVoiceDetected(float volume)
        {
            if (currentState == JessicaState.Listening && avatarImage != null)
            {
                float scale = 1f + volume * 0.5f;
                avatarImage.transform.DOScale(Vector3.one * scale, 0.1f).SetEase(Ease.OutQuad);
            }
        }

        public void OnSpeechStart()
        {
            SetState(JessicaState.Speaking);
        }

        public void OnSpeechEnd()
        {
            SetState(JessicaState.Idle);
        }

        private void OnDestroy()
        {
            pulseSequence?.Kill();
        }

        public override void OnDataUpdated(object data) { }
    }
}