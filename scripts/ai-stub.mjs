import http from "node:http";
import { pathToFileURL } from "node:url";

export const scenarios = [
  "success",
  "delay",
  "rate-limit",
  "unavailable",
  "invalid",
];

// Local acceptance fixture only. Never persist or echo request bodies/headers.
export function createStub({
  scenario = "success",
  delayMs = 1500,
  maxConcurrent = 8,
} = {}) {
  if (
    !scenarios.includes(scenario) ||
    !Number.isInteger(delayMs) ||
    delayMs < 0 ||
    delayMs > 150000 ||
    !Number.isInteger(maxConcurrent) ||
    maxConcurrent < 1 ||
    maxConcurrent > 32
  ) {
    throw new Error("Invalid stub options");
  }
  const stats = { requests: 0, active: 0, rejected: 0 };
  const server = http.createServer(async (req, res) => {
    const reply = (status, data) => {
      if (!res.destroyed) {
        res.writeHead(status, {
          "Content-Type": "application/json",
          "Cache-Control": "no-store",
        });
        res.end(JSON.stringify(data));
      }
    };
    if (req.method === "GET" && req.url === "/stats")
      return reply(200, { scenario, ...stats });
    if (req.method !== "POST" || req.url !== "/v1/responses")
      return reply(404, { error: "not_found" });
    if (stats.active >= maxConcurrent) return reply(503, { error: "capacity" });
    stats.active++;
    let timer;
    try {
      let bytes = 0;
      const chunks = [];
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 1048576) {
          stats.rejected++;
          reply(413, { error: "body_limit" });
          req.resume();
          return;
        }
        chunks.push(chunk);
      }
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      if (
        body.model !== "acceptance-model" ||
        body.store !== false ||
        body.stream !== false ||
        !Number.isInteger(body.max_output_tokens) ||
        body.max_output_tokens < 1 ||
        body.max_output_tokens > 4096 ||
        typeof body.instructions !== "string" ||
        !Array.isArray(body.input) ||
        ["tools", "background", "conversation", "previous_response_id"].some(
          (key) => key in body,
        )
      ) {
        stats.rejected++;
        return reply(400, { error: "contract_mismatch" });
      }
      const id = ++stats.requests;
      if (scenario === "delay")
        await new Promise((resolve) => {
          timer = setTimeout(resolve, delayMs);
          res.once("close", resolve);
        });
      if (scenario === "rate-limit")
        return reply(429, { error: "synthetic_rate_limit" });
      if (scenario === "unavailable")
        return reply(503, { error: "synthetic_unavailable" });
      if (scenario === "invalid")
        return reply(200, { status: "completed", output: [] });
      reply(200, {
        id: `acceptance_${id}`,
        status: "completed",
        output: [
          {
            type: "message",
            role: "assistant",
            content: [
              {
                type: "output_text",
                text: "Local acceptance reply.\nSynthetic content only: <b>plain text</b>.",
              },
            ],
          },
        ],
        usage: { input_tokens: 8, output_tokens: 5, total_tokens: 13 },
      });
    } catch {
      stats.rejected++;
      reply(400, { error: "invalid_request" });
    } finally {
      clearTimeout(timer);
      stats.active--;
    }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 5000;
  server.maxConnections = 32;
  return server;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const [scenario = "success", portText = "19090", delayText = "1500"] =
    process.argv.slice(2);
  const port = Number(portText);
  if (!Number.isInteger(port) || port < 1024 || port > 65535)
    throw new Error("Invalid port");
  const server = createStub({ scenario, delayMs: Number(delayText) });
  // Only set this inside an isolated Docker network; never publish its container port.
  const host =
    process.env.STUB_ISOLATED_CONTAINER === "1" ? "0.0.0.0" : "127.0.0.1";
  const stop = () => {
    server.close();
    server.closeAllConnections();
  };
  const lifetime = setTimeout(stop, 2 * 60 * 60 * 1000);
  lifetime.unref();
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  server.listen(port, host, () =>
    console.log(`Local stub ready: scenario=${scenario} port=${port}`),
  );
}
