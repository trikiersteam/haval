// Servidor estático simples do protótipo. Uso: node prototype/serve.js  ->  http://localhost:4577
const http = require('http'), fs = require('fs'), path = require('path');
const MIME = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css',
  '.png':'image/png', '.jpg':'image/jpeg', '.svg':'image/svg+xml', '.woff2':'font/woff2', '.json':'application/json' };
const root = __dirname, port = 4577;
http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const file = path.join(root, p);
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-store',   // protótipo: sempre servir a versão fresca (evita aba em cache)
    });
    res.end(data);
  });
}).listen(port, () => console.log('Protótipo Haval Dock em http://localhost:' + port));
