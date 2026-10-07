const defaults = {
  provider: 'ollama',
  profiles: {
    ollama: { endpoint: 'http://127.0.0.1:11434', model: 'llama3.1:8b' },
    lmstudio: { endpoint: 'http://127.0.0.1:1234/v1', model: '' },
    nightmare: { endpoint: '', model: '' }
  },
  prompt: 'You are Jessica, a helpful assistant in the Nightmare Suite. Be concise, clear, and honest about your limitations.',
  temperature: 0.7, maxTokens: 512, timeout: 120,
  spokenReplies: true, autoSendVoice: false, voice: '', rate: 0, volume: 100,
  theme: 'cyan', fontSize: 16
};

function endpointUrl(endpoint, resource) {
  const url = new URL(endpoint);
  if (!['http:', 'https:'].includes(url.protocol) ||
      !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) ||
      url.username || url.password || url.search || url.hash) {
    throw new Error('Use a local HTTP(S) endpoint on localhost, 127.0.0.1, or [::1], without credentials or query parameters.');
  }
  if (url.hostname === 'localhost') url.hostname = '127.0.0.1';
  url.pathname = `${url.pathname.replace(/\/+$/, '')}/${resource}`;
  return url;
}

function settingsFrom(input) {
  const s = structuredClone(defaults);
  if (!input || typeof input !== 'object') return s;
  if (Object.hasOwn(s.profiles, input.provider)) s.provider = input.provider;
  for (const name of Object.keys(s.profiles)) {
    const p = input.profiles?.[name];
    if (!p) continue;
    if (typeof p.endpoint === 'string') {
      if (p.endpoint) endpointUrl(p.endpoint, '');
      s.profiles[name].endpoint = p.endpoint.slice(0, 2048);
    }
    if (typeof p.model === 'string') s.profiles[name].model = p.model.slice(0, 256);
  }
  for (const key of ['prompt', 'voice']) {
    if (typeof input[key] === 'string') s[key] = input[key].slice(0, key === 'prompt' ? 8000 : 256);
  }
  for (const [key, min, max] of [
    ['temperature', 0, 2], ['maxTokens', 1, 8192], ['timeout', 5, 600],
    ['rate', -10, 10], ['volume', 0, 100], ['fontSize', 12, 24]
  ]) {
    if (Number.isFinite(input[key])) s[key] = Math.min(max, Math.max(min, input[key]));
  }
  for (const key of ['spokenReplies', 'autoSendVoice']) {
    if (typeof input[key] === 'boolean') s[key] = input[key];
  }
  if (['cyan', 'matrix', 'amber'].includes(input.theme)) s.theme = input.theme;
  return s;
}

async function jsonRequest(url, options) {
  const response = await fetch(url, { ...options, redirect: 'error' });
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error(`Local server returned HTTP ${response.status}. Check the server, model, and session token.`);
  }
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 2 * 1024 * 1024) throw new Error('Local response exceeds the 2 MB limit.');
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } finally {
    await reader.cancel();
  }
}

async function requestAI(settings, messages, key, signal, modelsOnly = false) {
  const s = settingsFrom(settings);
  const profile = s.profiles[s.provider];
  if (!profile.endpoint) throw new Error('Set your Nightmare Suite OpenAI-compatible local API endpoint in Settings.');
  const ollama = s.provider === 'ollama';
  const resource = modelsOnly ? (ollama ? 'api/tags' : 'models') : (ollama ? 'api/chat' : 'chat/completions');
  const headers = { 'Content-Type': 'application/json' };
  if (key) headers.Authorization = ['Bearer', key].join(' ');
  const options = { headers, signal: AbortSignal.any([signal, AbortSignal.timeout(s.timeout * 1000)]) };
  if (!modelsOnly) {
    if (!profile.model.trim()) throw new Error('Select or enter a model in Settings.');
    if (!Array.isArray(messages) || messages.length > 41 ||
        messages.some(m => !m || !['user', 'assistant'].includes(m.role) ||
          typeof m.content !== 'string' || !m.content.trim() || m.content.length > 16000)) {
      throw new Error('Invalid or oversized conversation.');
    }
    const chat = [{ role: 'system', content: s.prompt }, ...messages];
    options.method = 'POST';
    options.body = JSON.stringify(ollama
      ? { model: profile.model, messages: chat, stream: false,
          options: { temperature: s.temperature, num_predict: Math.floor(s.maxTokens) } }
      : { model: profile.model, messages: chat, stream: false,
          temperature: s.temperature, max_tokens: Math.floor(s.maxTokens) });
  }
  const data = await jsonRequest(endpointUrl(profile.endpoint, resource), options);
  if (modelsOnly) {
    const list = ollama ? data.models : data.data;
    if (!Array.isArray(list)) throw new Error('Server did not return a model list.');
    return list.map(m => ollama ? m.name : m.id).filter(m => typeof m === 'string').slice(0, 200);
  }
  const text = ollama ? data.message?.content : data.choices?.[0]?.message?.content;
  if (typeof text !== 'string' || !text.trim()) throw new Error('Server returned no reply.');
  return text.slice(0, 16000);
}

module.exports = { defaults, endpointUrl, settingsFrom, requestAI };
