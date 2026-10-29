import type { Plugin } from "@opencode-ai/plugin"
import { appendFile, mkdir } from "node:fs/promises"
import { join } from "node:path"
import { pathToFileURL } from "node:url"
import { spawn } from "node:child_process"

const PORT = 4310
const URL_ = `http://localhost:${PORT}`

// 1) logs every subagent start/finish  2) auto-starts the dashboard and opens the browser
//    the first time the "orchestrator" agent is used (or any subagent is called).
export const ProgressTracker: Plugin = async ({ directory }) => {
  const dir = join(directory, ".opencode", "state")
  let started = false

  const log = async (type: string, msg: string) => {
    try {
      await mkdir(dir, { recursive: true })
      await appendFile(join(dir, "events.jsonl"),
        JSON.stringify({ t: new Date().toISOString(), type, msg }) + "\n")
    } catch {}
  }

  const openBrowser = () => {
    try {
      const p = process.platform
      const child = p === "win32" ? spawn("cmd", ["/c", "start", "", URL_], { detached: true, stdio: "ignore" })
        : p === "darwin" ? spawn("open", [URL_], { detached: true, stdio: "ignore" })
        : spawn("xdg-open", [URL_], { detached: true, stdio: "ignore" })
      child.on("error", () => {}); child.unref()
    } catch {}
  }

  const startDashboard = async () => {
    if (started) return
    started = true
    try {
      const mod = await import(pathToFileURL(join(directory, ".opencode", "dashboard", "serve.mjs")).href)
      await mod.startServer(directory, PORT)
      openBrowser() // only reached when WE started it; if the port was busy it is already running
    } catch {}
  }

  return {
    "chat.message": async (input: any) => {
      if (input?.agent === "orchestrator") await startDashboard()
    },
    "tool.execute.before": async (input: any, output: any) => {
      if (input.tool === "task") {
        await startDashboard()
        await log("start", `${output?.args?.subagent_type ?? "agent"}: ${output?.args?.description ?? ""}`)
      }
    },
    "tool.execute.after": async (input: any, output: any) => {
      if (input.tool === "task") await log("done", output?.title ?? "agent finished")
    },
  }
}
