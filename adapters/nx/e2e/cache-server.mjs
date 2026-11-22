// Minimal Nx self-hosted remote cache (the HTTP protocol Nx speaks via NX_SELF_HOSTED_REMOTE_CACHE_SERVER):
//   GET /v1/cache/{hash} -> 200 artifact | 404,   PUT /v1/cache/{hash} -> 200 (409 if present)
// Stores artifacts in a directory. Usage: node cache-server.mjs <dir> <port>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const [dir, port] = process.argv.slice(2);
fs.mkdirSync(dir, { recursive: true });
const stats = { hits: 0, misses: 0, stored: 0 };

http.createServer((req, res) => {
  const m = req.url.match(/^\/v1\/cache\/([A-Za-z0-9]+)$/);
  if (req.url === '/stats') { res.end(JSON.stringify(stats)); return; }
  if (!m) { res.writeHead(404).end(); return; }
  const file = path.join(dir, m[1]);
  if (req.method === 'GET') {
    if (!fs.existsSync(file)) { stats.misses++; res.writeHead(404).end(); return; }
    stats.hits++;
    res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  } else if (req.method === 'PUT') {
    if (fs.existsSync(file)) { res.writeHead(409).end(); req.resume(); return; }
    const tmp = file + '.tmp';
    const out = fs.createWriteStream(tmp);
    req.pipe(out);
    out.on('finish', () => { fs.renameSync(tmp, file); stats.stored++; res.writeHead(200).end(); });
  } else {
    res.writeHead(405).end();
  }
}).listen(Number(port), '127.0.0.1', () => console.log(`cache server on ${port}`));
