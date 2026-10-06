using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System.Collections.Generic;
using System.Linq;

namespace StarkIndustries.HUD.Panels
{
    [Serializable]
    public class MessageData
    {
        public string id;
        public string from;
        public string body;
        public DateTime timestamp;
        public bool isRead;
        public MessageType type;
    }

    public enum MessageType { SMS, Email, WhatsApp, Signal, System }

    public class MessagesPanel : HUDPanel
    {
        [Header("UI References")]
        [SerializeField] private Transform messageListContainer;
        [SerializeField] private GameObject messageItemPrefab;
        [SerializeField] private TextMeshProUGUI unreadCountText;
        [SerializeField] private Button markAllReadButton;
        [SerializeField] private TMP_Dropdown filterDropdown;

        [Header("Settings")]
        [SerializeField] private int maxMessages = 20;
        [SerializeField] private bool autoMarkRead = true;

        private List<MessageData> messages = new();
        private List<GameObject> messageItems = new();

        protected override void Start()
        {
            base.Start();
            markAllReadButton?.onClick.AddListener(MarkAllRead);
            filterDropdown?.onValueChanged.AddListener(OnFilterChanged);
            
            LoadMockMessages();
            RefreshDisplay();
        }

        private void LoadMockMessages()
        {
            messages = new List<MessageData>
            {
                new() { id = "1", from = "Pepper Potts", body = "Board meeting moved to 3PM. Need you there.", timestamp = DateTime.Now.AddMinutes(-5), isRead = false, type = MessageType.SMS },
                new() { id = "2", from = "J.A.R.V.I.S.", body = "Mark 85 chassis stress test complete. All systems nominal.", timestamp = DateTime.Now.AddMinutes(-12), isRead = false, type = MessageType.System },
                new() { id = "3", from = "Rhodey", body = "Suit deployment authorized for sector 7. Standing by.", timestamp = DateTime.Now.AddHours(-1), isRead = true, type = MessageType.WhatsApp },
                new() { id = "4", from = "Happy Hogan", body = "Car's ready at the usual spot. Traffic's clear.", timestamp = DateTime.Now.AddHours(-2), isRead = true, type = MessageType.SMS },
                new() { id = "5", from = "Stark Industries", body = "Quarterly earnings beat estimates. Stock up 4.2% AH.", timestamp = DateTime.Now.AddHours(-3), isRead = true, type = MessageType.Email },
            };
        }

        public void AddMessage(MessageData message)
        {
            messages.Insert(0, message);
            if (messages.Count > maxMessages)
                messages.RemoveAt(messages.Count - 1);
            
            RefreshDisplay();
        }

        public void OnMessageReceived(string from, string body, MessageType type)
        {
            var msg = new MessageData
            {
                id = Guid.NewGuid().ToString(),
                from = from,
                body = body,
                timestamp = DateTime.Now,
                isRead = false,
                type = type
            };
            AddMessage(msg);
            
            if (IronManHUDController.Instance != null)
                IronManHUDController.Instance.OpenPanel("messages");
        }

        private void RefreshDisplay()
        {
            foreach (var item in messageItems)
                Destroy(item);
            messageItems.Clear();

            string filter = filterDropdown?.options[filterDropdown.value].text ?? "ALL";
            var filtered = messages.Where(m => filter == "ALL" || m.type.ToString() == filter).ToList();

            foreach (var msg in filtered)
            {
                var item = Instantiate(messageItemPrefab, messageListContainer);
                messageItems.Add(item);
                SetupMessageItem(item, msg);
            }

            UpdateUnreadCount();
        }

        private void SetupMessageItem(GameObject item, MessageData msg)
        {
            var fromText = item.transform.Find("FromText")?.GetComponent<TextMeshProUGUI>();
            var bodyText = item.transform.Find("BodyText")?.GetComponent<TextMeshProUGUI>();
            var timeText = item.transform.Find("TimeText")?.GetComponent<TextMeshProUGUI>();
            var typeIcon = item.transform.Find("TypeIcon")?.GetComponent<Image>();
            var unreadIndicator = item.transform.Find("UnreadIndicator");

            if (fromText) fromText.text = msg.from.ToUpper();
            if (bodyText) bodyText.text = msg.body.Length > 50 ? msg.body[..50] + "..." : msg.body;
            if (timeText) timeText.text = msg.timestamp.ToString("HH:mm");
            if (unreadIndicator) unreadIndicator.SetActive(!msg.isRead);

            if (typeIcon)
            {
                Color typeColor = msg.type switch
                {
                    MessageType.SMS => new Color(0, 1f, 0.5f),
                    MessageType.Email => new Color(0.5f, 0.8f, 1f),
                    MessageType.WhatsApp => new Color(0, 0.9f, 0.3f),
                    MessageType.Signal => new Color(0.2f, 0.6f, 1f),
                    MessageType.System => new Color(1f, 0.8f, 0),
                    _ => Color.white
                };
                typeIcon.color = typeColor;
            }

            var button = item.GetComponent<Button>();
            if (button)
            {
                button.onClick.AddListener(() => OnMessageClicked(msg));
            }
        }

        private void OnMessageClicked(MessageData msg)
        {
            if (!msg.isRead && autoMarkRead)
            {
                msg.isRead = true;
                RefreshDisplay();
            }
            
            JessicaClient.Instance?.ProcessQuery($"Read message from {msg.from}: {msg.body}");
        }

        private void MarkAllRead()
        {
            foreach (var msg in messages)
                msg.isRead = true;
            RefreshDisplay();
        }

        private void OnFilterChanged(int index)
        {
            RefreshDisplay();
        }

        private void UpdateUnreadCount()
        {
            int unread = messages.Count(m => !m.isRead);
            if (unreadCountText)
                unreadCountText.text = unread > 0 ? unread.ToString() : "";
        }

        public override void OnDataUpdated(object data)
        {
            if (data is MessageData msg)
                AddMessage(msg);
        }
    }
}