// Shared dashboard server. Used by the plugin (auto-start) and by `node .opencode/dashboard/serve.mjs`.
import http from "node:http"; import fs from "node:fs"; import path from "node:path"
import { fileURLToPath } from "node:url"
const here = path.dirname(fileURLToPath(import.meta.url))

export function startServer(root, port = 4310) {
  const rd = (p, d) => { try { return fs.readFileSync(path.join(root, p), "utf8") } catch { return d } }
  const snap = () => {
    let progress = {}; try { progress = JSON.parse(rd(".opencode/state/progress.json", "{}")) } catch {}
    const events = rd(".opencode/state/events.jsonl", "").trim().split("\n").filter(Boolean)
      .slice(-60).map(l => { try { return JSON.parse(l) } catch { return null } }).filter(Boolean)
    return JSON.stringify({ progress, events })
  }
  const clients = new Set(); let last = ""
  const timer = setInterval(() => {
    if (!clients.size) return
    const s = snap(); if (s === last) return; last = s
    for (const c of clients) c.write(`data: ${s}\n\n`)
  }, 700)
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      if (req.url.startsWith("/api/stream")) {
        res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" })
        res.write(`data: ${snap()}\n\n`); clients.add(res)
        req.on("close", () => clients.delete(res)); return
      }
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" })
      res.end(fs.readFileSync(path.join(here, "index.html")))
    })
    server.once("error", e => { clearInterval(timer); reject(e) })
    server.listen(port, "127.0.0.1", () => resolve(server))
  })
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  startServer(process.cwd()).then(() => console.log("Dashboard: http://localhost:4310"))
    .catch(() => console.log("Port 4310 is busy - the dashboard is probably already running: http://localhost:4310"))
}
