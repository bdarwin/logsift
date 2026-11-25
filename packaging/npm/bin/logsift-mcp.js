#!/usr/bin/env node
// logsift-mcp from npm: downloads Logsift for this system from its GitHub release once (checked
// against the release's SHA-256 sums), then runs its logsift-mcp with the same arguments and
// standard input and output. Nothing but this file comes from npm; no dependencies.
//
//   npx -y logsift-mcp                 the MCP server (stdio), for an agent's MCP settings
//   npx -y logsift-mcp --version       download now (so an agent's first start isn't slow)
//
// Environment: HTTPS_PROXY (an office proxy), LOGSIFT_MCP_CACHE (where Logsift is kept, default
// ~/.logsift/mcp-runtime), LOGSIFT_MCP_DOWNLOADS (another place to download from: a URL or folder).
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const https = require('https');
const http = require('http');
const tls = require('tls');
const { spawn, spawnSync } = require('child_process');

const VERSION = require('../package.json').version;
const RELEASE = `https://github.com/bdarwin/logsift/releases/download/v${VERSION}/`;

/** Everything said goes to standard error: standard output is the protocol's. */
function say(msg) {
  process.stderr.write(`logsift-mcp: ${msg}\n`);
}

function target() {
  const sys = { darwin: 'macos', win32: 'windows', linux: 'linux' }[process.platform];
  const cpu = { arm64: 'arm64', x64: 'x64' }[process.arch];
  if (!sys || !cpu) {
    throw new Error(`no Logsift for ${process.platform}-${process.arch}; it runs on macOS, Windows and Linux, x64 and arm64`);
  }
  return `${sys}-${cpu}`;
}

/** The launcher inside the unpacked app. */
function launcher(dir, t) {
  if (t.startsWith('macos')) {
    const beta = path.join(dir, 'Logsift Beta.app', 'Contents', 'MacOS', 'logsift-mcp');
    return fs.existsSync(beta) ? beta : path.join(dir, 'Logsift.app', 'Contents', 'MacOS', 'logsift-mcp');
  }
  if (t.startsWith('windows')) return path.join(dir, 'Logsift', 'logsift-mcp.exe');
  return path.join(dir, 'Logsift', 'bin', 'logsift-mcp');
}

/** A proxy for this address, from HTTPS_PROXY / NO_PROXY, or null. */
function proxyFor(url) {
  const p = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.ALL_PROXY || process.env.all_proxy;
  if (!p) return null;
  const host = new URL(url).hostname;
  const no = (process.env.NO_PROXY || process.env.no_proxy || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (no.some((n) => n === '*' || host === n.replace(/^\./, '') || host.endsWith('.' + n.replace(/^\./, '')))) return null;
  return new URL(p.includes('://') ? p : `http://${p}`);
}

/** GET over HTTPS (through a proxy if set), following redirects; resolves with the response. */
function get(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const done = (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        if (redirects > 10) return reject(new Error('too many redirects'));
        return resolve(get(new URL(res.headers.location, url).toString(), redirects + 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`${url}: HTTP ${res.statusCode}`));
      }
      resolve(res);
    };
    const proxy = proxyFor(url);
    if (!proxy) {
      https.get(u, { headers: { 'User-Agent': `logsift-mcp-npm/${VERSION}` } }, done).on('error', reject);
      return;
    }
    // a tunnel through the proxy (CONNECT), then TLS to GitHub as usual
    const auth = proxy.username ? { 'Proxy-Authorization': 'Basic ' + Buffer.from(
      `${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`).toString('base64') } : {};
    http.request({ host: proxy.hostname, port: proxy.port || 80, method: 'CONNECT', path: `${u.hostname}:443`, headers: auth })
      .on('connect', (res, socket) => {
        if (res.statusCode !== 200) return reject(new Error(`proxy ${proxy.host}: HTTP ${res.statusCode}`));
        const secure = tls.connect({ socket, servername: u.hostname });
        https.get(u, { createConnection: () => secure, headers: { 'User-Agent': `logsift-mcp-npm/${VERSION}` } }, done)
          .on('error', reject);
      })
      .on('error', reject)
      .end();
  });
}

/** Copies a download (URL or local folder) to a file. */
async function download(name, to) {
  const base = process.env.LOGSIFT_MCP_DOWNLOADS || RELEASE;
  if (!/^https?:\/\//.test(base)) {
    fs.copyFileSync(path.join(base, name), to);
    return;
  }
  const res = await get(new URL(name, base.endsWith('/') ? base : base + '/').toString());
  const total = Number(res.headers['content-length'] || 0);
  let got = 0;
  let shown = 0;
  await new Promise((resolve, reject) => {
    const out = fs.createWriteStream(to);
    res.on('data', (chunk) => {
      got += chunk.length;
      if (total && got - shown > total / 10) {
        shown = got;
        say(`downloading ${name}: ${Math.round((100 * got) / total)}%`);
      }
    });
    res.pipe(out);
    out.on('finish', resolve);
    res.on('error', reject);
    out.on('error', reject);
  });
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

/** Logsift for this system in the cache, downloaded and unpacked the first time. Gives the launcher. */
async function install() {
  const t = target();
  const cache = process.env.LOGSIFT_MCP_CACHE || path.join(os.homedir(), '.logsift', 'mcp-runtime');
  const dir = path.join(cache, VERSION, t);
  const exe = launcher(dir, t);
  if (fs.existsSync(exe)) return exe;

  const asset = `Logsift-${VERSION}-${t}.${t.startsWith('windows') ? 'zip' : 'tar.gz'}`;
  fs.mkdirSync(cache, { recursive: true });
  const work = fs.mkdtempSync(path.join(cache, `.download-${t}-`));
  try {
    say(`getting Logsift ${VERSION} for ${t} (once; about 150 MB)`);
    const sums = path.join(work, 'SHA256SUMS.txt');
    await download('SHA256SUMS.txt', sums);
    const line = fs.readFileSync(sums, 'utf8').split(/\r?\n/).find((l) => l.trim().endsWith(' ' + asset) || l.trim().endsWith('*' + asset));
    if (!line) throw new Error(`${asset} isn't in the release's SHA256SUMS.txt`);
    const want = line.trim().split(/\s+/)[0].toLowerCase();
    const file = path.join(work, asset);
    await download(asset, file);
    const have = sha256(file);
    if (have !== want) throw new Error(`${asset} doesn't match its SHA-256 (got ${have}, expected ${want}); not used`);
    const unpacked = path.join(work, 'app');
    fs.mkdirSync(unpacked);
    // tar unpacks both: .tar.gz everywhere, and .zip on Windows (its tar.exe since Windows 10)
    // Windows' own tar (bsdtar) reads zips; Git's GNU tar, often first on a developer's PATH, doesn't
    const winTar = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe');
    const tar = process.platform === 'win32' && fs.existsSync(winTar) ? winTar : 'tar';
    const r = spawnSync(tar, ['-xf', file, '-C', unpacked], { stdio: ['ignore', 'ignore', 'inherit'] });
    if (r.status !== 0) throw new Error(`couldn't unpack ${asset} (tar ${r.error ? r.error.message : 'exit ' + r.status})`);
    if (!fs.existsSync(launcher(unpacked, t))) throw new Error(`${asset} has no logsift-mcp where expected`);
    fs.mkdirSync(path.dirname(dir), { recursive: true });
    try {
      fs.renameSync(unpacked, dir);
    } catch (e) {
      if (!fs.existsSync(exe)) throw e; // another start got there first
    }
    say(`Logsift ${VERSION} is in ${dir}`);
    return launcher(dir, t); // as unpacked (the beta's app has its own name)
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

install().then(
  (exe) => {
    const child = spawn(exe, process.argv.slice(2), { stdio: 'inherit', windowsHide: true });
    for (const s of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
      process.on(s, () => child.kill(s));
    }
    child.on('exit', (code, signal) => process.exit(code === null ? (signal ? 1 : 0) : code));
    child.on('error', (e) => {
      say(`couldn't start ${exe}: ${e.message}`);
      process.exit(1);
    });
  },
  (e) => {
    say(e.message);
    say('Install Logsift from https://github.com/bdarwin/logsift/releases and use its logsift-mcp instead.');
    process.exit(1);
  },
);
