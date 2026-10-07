const { spawn } = require('node:child_process');
const path = require('node:path');
const { StringDecoder } = require('node:string_decoder');
const jobs = new Map();

function stopVoice() {
  for (const child of jobs.values()) child.kill();
  jobs.clear();
}

function windowsVoice(mode, data = {}) {
  if (process.platform !== 'win32') return Promise.reject(new Error('Voice requires Windows with an installed speech language. Typed chat works on this OS.'));
  if (!['voices', 'speak', 'listen'].includes(mode)) return Promise.reject(new Error('Invalid voice action.'));
  if (jobs.has(mode)) return Promise.reject(new Error('This voice action is already running.'));
  return new Promise((resolve, reject) => {
    const executable = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
    const child = spawn(executable, [
      '-NoProfile', '-NonInteractive', '-File', path.join(__dirname, 'voice.ps1'), '-Mode', mode
    ], { windowsHide: true, shell: false });
    jobs.set(mode, child);
    let output = '', errors = '';
    const decoder = new StringDecoder('utf8');
    const timer = setTimeout(() => child.kill(), mode === 'speak' ? 300000 : 30000);
    child.stdout.on('data', chunk => {
      output += decoder.write(chunk);
      if (output.length > 65536) child.kill();
    });
    child.stderr.on('data', chunk => { errors = (errors + chunk.toString('utf8')).slice(0, 4096); });
    child.stdin.on('error', () => {});
    child.on('error', reject);
    child.on('close', code => {
      output += decoder.end();
      clearTimeout(timer);
      if (jobs.get(mode) === child) jobs.delete(mode);
      if (code !== 0) return reject(new Error(errors || 'Voice stopped or timed out.'));
      try { resolve(JSON.parse(output.replace(/^\uFEFF/, ''))); }
      catch { reject(new Error('Windows speech returned an invalid result.')); }
    });
    child.stdin.end(JSON.stringify(data));
  });
}

module.exports = { windowsVoice, stopVoice };
