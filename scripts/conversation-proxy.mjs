import http from "node:http";
import { pathToFileURL } from "node:url";

// Test-only loopback proxy. Faults occur after the real backend receives the request.
export function createProxy(mode = "pass", backendPort = 18085) {
  if (!["pass", "drop-send-once", "fail-read"].includes(mode))
    throw new Error("Invalid proxy mode");
  let dropped = false;
  return http.createServer((req, res) => {
    if (
      mode === "fail-read" &&
      req.method === "GET" &&
      req.url.includes("/conversations/")
    ) {
      res.writeHead(503, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          code: 503,
          message: "Synthetic read failure",
          data: null,
        }),
      );
      req.resume();
      return;
    }
    const drop =
      mode === "drop-send-once" &&
      !dropped &&
      req.method === "POST" &&
      /\/messages$/.test(req.url);
    if (drop) dropped = true;
    const upstream = http.request(
      {
        hostname: "127.0.0.1",
        port: backendPort,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, host: `127.0.0.1:${backendPort}` },
      },
      (response) => {
        if (drop) {
          response.resume();
          response.once("end", () => {
            // Receiving a truncated body prevents Chromium's transparent retry of a
            // connection that closed before any response headers were received.
            res.writeHead(200, {
              "Content-Type": "application/json",
              "Content-Length": "1000",
            });
            res.end("{");
          });
        } else {
          res.writeHead(response.statusCode, response.headers);
          response.pipe(res);
        }
      },
    );
    upstream.setTimeout(125000, () => upstream.destroy());
    upstream.on("error", () => {
      if (!res.headersSent) res.writeHead(503);
      res.end();
    });
    let bytes = 0;
    req.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > 1048576) {
        upstream.destroy();
        res.destroy();
      }
    });
    req.pipe(upstream);
    res.once("close", () => upstream.destroy());
  });
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const mode = process.argv[2] ?? "pass";
  const backendPort = Number(process.argv[3] ?? 18085);
  const listenPort = Number(process.argv[4] ?? 18086);
  if (
    ![18085, 15175].includes(backendPort) ||
    ![18086, 15176].includes(listenPort)
  ) {
    throw new Error("Only DEV-015 local ports are supported");
  }
  const server = createProxy(mode, backendPort);
  server.maxConnections = 32;
  server.requestTimeout = 10000;
  const stop = () => {
    server.close();
    server.closeAllConnections();
  };
  setTimeout(stop, 2 * 60 * 60 * 1000).unref();
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  server.listen(listenPort, "127.0.0.1", () =>
    console.log(`Acceptance proxy ready: ${mode} port=${listenPort}`),
  );
}
