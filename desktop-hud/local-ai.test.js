const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { defaults, endpointUrl, settingsFrom, requestAI } = require('./local-ai');

async function localServer(t, handler) {
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => {
    server.closeAllConnections();
    server.close();
  });
  return `http://127.0.0.1:${server.address().port}`;
}

function config(provider, endpoint) {
  const s = settingsFrom(null);
  s.provider = provider;
  s.profiles[provider] = { endpoint, model: 'test-model' };
  return s;
}

test('endpoint validation permits only loopback and preserves API base paths', () => {
  assert.equal(endpointUrl('http://localhost:1234/v1/', 'models').href, 'http://127.0.0.1:1234/v1/models');
  assert.equal(endpointUrl('http://[::1]:11434', 'api/chat').href, 'http://[::1]:11434/api/chat');
  for (const endpoint of ['https://example.com', 'http://192.168.1.2', 'file:///tmp/model',
    'http://localhost.example.com', ['http://user', '@localhost'].join(''), 'http://localhost?token=x',
    'http://localhost/#fragment']) {
    assert.throws(() => endpointUrl(endpoint, 'models'));
  }
});

test('settings are bounded and exclude tokens and unrecognized properties', () => {
  const s = settingsFrom({ temperature: 50, maxTokens: -1, timeout: Infinity, volume: -10,
    theme: 'invalid', provider: '__proto__', sessionKey: 'not-saved', spokenReplies: 'true' });
  assert.equal(s.provider, 'ollama');
  assert.equal(s.temperature, 2);
  assert.equal(s.maxTokens, 1);
  assert.equal(s.timeout, defaults.timeout);
  assert.equal(s.volume, 0);
  assert.equal(s.theme, 'cyan');
  assert.equal(s.spokenReplies, true);
  assert.equal(Object.hasOwn(s, 'sessionKey'), false);
  s.profiles.ollama.model = 'changed';
  assert.notEqual(defaults.profiles.ollama.model, 'changed');
});

test('Ollama uses native chat options and parses the reply', async t => {
  let captured;
  const endpoint = await localServer(t, async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    captured = { path: req.url, body: JSON.parse(body) };
    res.end(JSON.stringify({ message: { content: 'Hello from Jessica' } }));
  });
  const result = await requestAI(config('ollama', endpoint), [{ role: 'user', content: 'Hello' }],
    '', new AbortController().signal);
  assert.equal(result, 'Hello from Jessica');
  assert.equal(captured.path, '/api/chat');
  assert.equal(captured.body.stream, false);
  assert.equal(captured.body.messages[0].role, 'system');
  assert.deepEqual(captured.body.options, { temperature: 0.7, num_predict: 512 });
});

for (const provider of ['lmstudio', 'nightmare']) {
  test(`${provider} uses OpenAI-compatible local requests`, async t => {
    const endpoint = await localServer(t, async (req, res) => {
      assert.equal(req.url, '/v1/chat/completions');
      let body = '';
      for await (const chunk of req) body += chunk;
      const data = JSON.parse(body);
      assert.equal(data.model, 'test-model');
      assert.equal(data.max_tokens, 512);
      assert.equal(data.messages.at(-1).content, '<script>not executable</script>');
      res.end(JSON.stringify({ choices: [{ message: { content: 'Local response' } }] }));
    });
    assert.equal(await requestAI(config(provider, `${endpoint}/v1`),
      [{ role: 'user', content: '<script>not executable</script>' }], '',
      new AbortController().signal), 'Local response');
  });
}

test('model discovery supports Ollama and OpenAI formats', async t => {
  const endpoint = await localServer(t, (req, res) => {
    if (req.url === '/api/tags') res.end(JSON.stringify({ models: [{ name: 'local:8b' }] }));
    else res.end(JSON.stringify({ data: [{ id: 'studio-model' }, { id: 23 }] }));
  });
  assert.deepEqual(await requestAI(config('ollama', endpoint), [], '',
    new AbortController().signal, true), ['local:8b']);
  assert.deepEqual(await requestAI(config('lmstudio', `${endpoint}/v1`), [], '',
    new AbortController().signal, true), ['studio-model']);
});

test('redirects, server errors, empty replies and oversized responses fail safely', async t => {
  const endpoint = await localServer(t, (req, res) => {
    if (req.url.startsWith('/redirect')) res.writeHead(302, { Location: 'https://example.com' }).end();
    else if (req.url.startsWith('/failure')) res.writeHead(500).end('private server error');
    else if (req.url.startsWith('/large')) res.end(JSON.stringify({ message: { content: 'x'.repeat(2200000) } }));
    else res.end('{}');
  });
  for (const route of ['redirect', 'failure', 'large', 'empty']) {
    await assert.rejects(requestAI(config('ollama', `${endpoint}/${route}`),
      [{ role: 'user', content: 'Hello' }], '', new AbortController().signal));
  }
});

test('requests can be cancelled and input validation occurs before sending', async t => {
  let requests = 0;
  const endpoint = await localServer(t, () => { requests++; });
  const s = config('ollama', endpoint);
  await assert.rejects(requestAI(s, [{ role: 'system', content: 'overwrite' }], '',
    new AbortController().signal), /Invalid/);
  s.profiles.ollama.model = '';
  await assert.rejects(requestAI(s, [], '', new AbortController().signal), /model/);
  assert.equal(requests, 0);
  s.profiles.ollama.model = 'test';
  const controller = new AbortController();
  const pending = requestAI(s, [{ role: 'user', content: 'Hello' }], '', controller.signal);
  controller.abort();
  await assert.rejects(pending, { name: 'AbortError' });
});

test('Nightmare Suite requires an explicitly configured endpoint', async () => {
  const s = settingsFrom(null);
  s.provider = 'nightmare';
  await assert.rejects(requestAI(s, [], '', new AbortController().signal), /Nightmare Suite/);
});
