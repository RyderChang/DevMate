import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { createStub } from "./ai-stub.mjs";
import http from "node:http";
import { createProxy } from "./conversation-proxy.mjs";

const body = {
  model: "acceptance-model",
  store: false,
  stream: false,
  max_output_tokens: 20,
  instructions: "Synthetic rules",
  input: [{ role: "user", content: "Synthetic question" }],
};

async function fixture(t, options) {
  const server = createStub(options);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => {
    server.close();
    server.closeAllConnections();
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    base,
    send: (data = body) =>
      fetch(`${base}/v1/responses`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      }),
  };
}

test("success is deterministic, counts requests, and never echoes private input", async (t) => {
  const { base, send } = await fixture(t);
  const response = await send();
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.status, "completed");
  assert.equal(data.usage.total_tokens, 13);
  assert.ok(!JSON.stringify(data).includes("Synthetic question"));
  assert.equal((await (await fetch(`${base}/stats`)).json()).requests, 1);
});

for (const [scenario, status] of [
  ["rate-limit", 429],
  ["unavailable", 503],
  ["invalid", 200],
]) {
  test(`fault scenario ${scenario}`, async (t) => {
    const { send } = await fixture(t, { scenario });
    const response = await send();
    assert.equal(response.status, status);
    const data = await response.json();
    if (scenario === "invalid") assert.deepEqual(data.output, []);
    else assert.ok(data.error);
  });
}

test("fails closed on bad options, contracts, JSON, routes and oversized bodies", async (t) => {
  assert.throws(() => createStub({ scenario: "typo" }));
  assert.throws(() => createStub({ delayMs: 150001 }));
  const { base, send } = await fixture(t);
  assert.equal((await send({ ...body, tools: [] })).status, 400);
  assert.equal(
    (await fetch(`${base}/v1/responses`, { method: "POST", body: "{" })).status,
    400,
  );
  assert.equal((await fetch(`${base}/wrong`)).status, 404);
  assert.equal(
    (await send({ ...body, instructions: "x".repeat(1048577) })).status,
    413,
  );
});

test("delayed calls overlap while capacity remains bounded", async (t) => {
  const { base, send } = await fixture(t, {
    scenario: "delay",
    delayMs: 1000,
    maxConcurrent: 2,
  });
  const pending = [send(), send()];
  let stats;
  const deadline = Date.now() + 3000;
  do {
    stats = await (await fetch(`${base}/stats`)).json();
    if (stats.active === 2) break;
    await new Promise((resolve) => setTimeout(resolve, 5));
  } while (Date.now() < deadline);
  assert.equal(stats.active, 2);
  assert.equal((await send()).status, 503);
  assert.deepEqual(
    (await Promise.all(pending)).map((r) => r.status),
    [200, 200],
  );
});

test("proxy drops only the first completed send and forwards its retry", async (t) => {
  let calls = 0;
  const backend = http.createServer((req, res) => {
    req.resume();
    req.on("end", () => {
      calls++;
      res.end("completed");
    });
  });
  backend.listen(0, "127.0.0.1");
  await once(backend, "listening");
  const proxy = createProxy("drop-send-once", backend.address().port);
  proxy.listen(0, "127.0.0.1");
  await once(proxy, "listening");
  t.after(() => {
    for (const server of [proxy, backend]) {
      server.close();
      server.closeAllConnections();
    }
  });
  const url = `http://127.0.0.1:${proxy.address().port}/projects/1/conversations/1/messages`;
  await assert.rejects(async () =>
    (await fetch(url, { method: "POST", body: "synthetic" })).text(),
  );
  assert.equal(calls, 1);
  assert.equal(
    await (await fetch(url, { method: "POST", body: "synthetic" })).text(),
    "completed",
  );
  assert.equal(calls, 2);
});
