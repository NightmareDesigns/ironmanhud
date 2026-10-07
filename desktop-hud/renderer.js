const api = window.jessica;
const $ = id => document.getElementById(id);
let settings, draft, draftProvider, busy = false, stopped = false;
let history = [];
const controls = ['send', 'listen', 'read', 'clear', 'export', 'settings-open'];
const status = text => { $('status').textContent = text; };

function setBusy(value) {
  busy = value;
  for (const id of controls) $(id).disabled = value;
}

function appearance() {
  document.body.dataset.theme = settings.theme;
  $('chat').className = `font-${Math.round(settings.fontSize)}`;
  $('connection').textContent = `${$('provider').querySelector(`option[value="${settings.provider}"]`).textContent} · ${settings.profiles[settings.provider].model || 'select a model'}`;
}

function addMessage(role, content) {
  const item = document.createElement('div');
  item.className = `message ${role}`;
  const label = document.createElement('strong');
  label.textContent = role === 'user' ? 'You' : 'Jessica';
  item.append(label, document.createTextNode(content));
  $('chat').append(item);
  while ($('chat').children.length > 200) $('chat').firstElementChild.remove();
  $('chat').scrollTop = $('chat').scrollHeight;
}

async function speak(text) {
  status('Jessica speaking…');
  try { await api.speak(text); }
  catch (error) {
    if (!stopped) status(`Reply available as text. Voice: ${error.message}`);
    return false;
  }
  return true;
}

async function send() {
  const text = $('message').value.trim();
  if (!text || busy || !settings) return;
  setBusy(true);
  stopped = false;
  const context = [...history.slice(-40), { role: 'user', content: text }];
  status('Waiting for local model…');
  try {
    const response = await api.send(context);
    if (stopped) return;
    history.push({ role: 'user', content: text }, { role: 'assistant', content: response });
    history = history.slice(-200);
    addMessage('user', text);
    addMessage('assistant', response);
    if ($('message').value.trim() === text) $('message').value = '';
    if (!settings.spokenReplies || await speak(response)) status('Ready.');
  } catch (error) {
    if (!stopped) status(`${error.message} Your message is kept for retry.`);
  } finally {
    setBusy(false);
    if (stopped) status('Stopped.');
    $('message').focus();
  }
}

function updateProfile() {
  draft.profiles[draftProvider] = { endpoint: $('endpoint').value.trim(), model: $('model').value.trim() };
}

function loadProfile() {
  draftProvider = $('provider').value;
  $('endpoint').value = draft.profiles[draftProvider].endpoint;
  $('model').value = draft.profiles[draftProvider].model;
  $('model-list').replaceChildren();
}

function collectSettings() {
  updateProfile();
  draft.provider = $('provider').value;
  for (const key of ['prompt', 'voice', 'theme']) draft[key] = $(key).value;
  for (const key of ['temperature', 'maxTokens', 'timeout', 'rate', 'volume', 'fontSize']) draft[key] = Number($(key).value);
  for (const key of ['spokenReplies', 'autoSendVoice']) draft[key] = $(key).checked;
  return draft;
}

async function saveSettings() {
  if (!$('settings-form').reportValidity()) throw new Error('Check the highlighted setting.');
  await api.setKey($('session-key').value);
  settings = await api.saveSettings(collectSettings());
  draft = structuredClone(settings);
  appearance();
}

function settingsBusy(value) {
  for (const id of ['settings-save', 'settings-close', 'models', 'voices', 'provider']) $(id).disabled = value;
  $('settings-dialog').dataset.busy = String(value);
}

$('compose').addEventListener('submit', event => { event.preventDefault(); send(); });
$('message').addEventListener('keydown', event => {
  if (event.key === 'Enter' && event.ctrlKey) { event.preventDefault(); send(); }
});
$('stop').addEventListener('click', async () => {
  stopped = true;
  await Promise.all([api.cancel(), api.stopVoice()]);
  status(busy ? 'Stopping…' : 'Stopped.');
});
$('listen').addEventListener('click', async () => {
  if (busy) return;
  stopped = false;
  setBusy(true);
  status('Listening for one utterance (up to 15 seconds)…');
  let recognized = false;
  try {
    const result = await api.listen();
    if (!stopped) {
      $('message').value = result.text;
      recognized = true;
      status('Speech recognized. Review and send.');
    }
  } catch (error) { if (!stopped) status(error.message); }
  finally { setBusy(false); if (stopped) status('Stopped.'); }
  if (recognized && settings.autoSendVoice) await send();
});
$('read').addEventListener('click', async () => {
  const reply = history.findLast(m => m.role === 'assistant');
  if (!reply) return status('No reply to read yet.');
  stopped = false;
  setBusy(true);
  try { if (await speak(reply.content)) status('Ready.'); }
  finally { setBusy(false); if (stopped) status('Stopped.'); }
});
$('clear').addEventListener('click', () => {
  history = [];
  $('chat').replaceChildren();
  status('New conversation. Previous context cleared.');
});
$('export').addEventListener('click', async () => {
  try { status(await api.exportChat(history) ? 'Transcript exported. Keep this file private.' : 'Export cancelled.'); }
  catch (error) { status(error.message); }
});
$('settings-open').addEventListener('click', () => {
  draft = structuredClone(settings);
  $('provider').value = settings.provider;
  loadProfile();
  for (const key of ['prompt', 'temperature', 'maxTokens', 'timeout', 'rate', 'volume', 'theme', 'fontSize']) $(key).value = settings[key];
  for (const key of ['spokenReplies', 'autoSendVoice']) $(key).checked = settings[key];
  $('voice').replaceChildren(new Option('Windows default', ''));
  if (settings.voice) $('voice').add(new Option(settings.voice, settings.voice));
  $('voice').value = settings.voice;
  $('settings-status').textContent = '';
  $('settings-dialog').showModal();
});
$('provider').addEventListener('change', () => { updateProfile(); loadProfile(); });
$('settings-close').addEventListener('click', () => $('settings-dialog').close());
$('settings-dialog').addEventListener('cancel', event => {
  if ($('settings-dialog').dataset.busy === 'true') event.preventDefault();
});
$('settings-form').addEventListener('submit', async event => {
  event.preventDefault();
  settingsBusy(true);
  try {
    const changedProvider = settings.provider !== $('provider').value ||
      settings.profiles[settings.provider].endpoint !== $('endpoint').value.trim() ||
      settings.profiles[settings.provider].model !== $('model').value.trim();
    await saveSettings();
    if (changedProvider) {
      history = [];
      $('chat').replaceChildren();
    }
    $('settings-dialog').close();
    status(changedProvider ? 'Settings saved. New conversation for this connection.' : 'Settings saved.');
  } catch (error) { $('settings-status').textContent = error.message; }
  finally { settingsBusy(false); }
});
$('models').addEventListener('click', async () => {
  settingsBusy(true);
  try {
    const previous = JSON.stringify(settings.profiles[settings.provider]);
    const provider = settings.provider;
    await saveSettings();
    if (provider !== settings.provider || previous !== JSON.stringify(settings.profiles[settings.provider])) {
      history = [];
      $('chat').replaceChildren();
    }
    $('settings-status').textContent = 'Contacting local server…';
    const models = await api.models();
    $('model-list').replaceChildren(...models.map(id => new Option(id, id)));
    $('settings-status').textContent = `Connected. ${models.length} model(s) available; choose an ID in the Model field and save.`;
  } catch (error) { $('settings-status').textContent = error.message; }
  finally { settingsBusy(false); }
});
$('voices').addEventListener('click', async () => {
  settingsBusy(true);
  try {
    const result = await api.voices();
    const selected = $('voice').value;
    $('voice').replaceChildren(new Option('Windows default', ''), ...result.voices.map(name => new Option(name, name)));
    $('voice').value = result.voices.includes(selected) ? selected : '';
    $('settings-status').textContent = `${result.voices.length} Windows voice(s) available.`;
  } catch (error) { $('settings-status').textContent = error.message; }
  finally { settingsBusy(false); }
});

setBusy(true);
api.getSettings().then(value => {
  settings = value;
  appearance();
  setBusy(false);
  status('Ready. Open Settings to choose a provider and test the local connection.');
}).catch(error => status(error.message));
