import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";

process.env.REDIS_URL ??= "redis://127.0.0.1:6379";
process.env.JWT_SECRET ??= "test-only-secret-that-is-long-enough-32";
process.env.JWT_ISSUER ??= "remotehub-test";
process.env.JWT_AUDIENCE ??= "remote-support-test";

const { app } = await import("../src/app.js");
const { issueToken } = await import("../src/auth.js");
const { keys, redis } = await import("../src/store.js");
const { attachSignaling, authorizeSignal } = await import("../src/signaling.js");

test("signaling requires owner acceptance and enforces offer/answer direction", () => {
  const controller = { sub: "owner-1", role: "controller" };
  const agent = { sub: "device-1", role: "agent" };
  const pending = { status: "pending", controllerId: controller.sub, deviceId: agent.sub };
  const accepted = { ...pending, status: "accepted" };

  assert.equal(authorizeSignal(pending, controller, "offer"), false);
  assert.equal(authorizeSignal(accepted, controller, "offer"), true);
  assert.equal(authorizeSignal(accepted, agent, "offer"), false);
  assert.equal(authorizeSignal(accepted, agent, "answer"), true);
  assert.equal(authorizeSignal(accepted, controller, "answer"), false);
  assert.equal(authorizeSignal(accepted, { sub: "stranger", role: "controller" }, "ice"), false);
});

test("signaling setup is attached only at the signaling endpoint", async () => {
  const server = createServer(app);
  attachSignaling(server);
  await new Promise((resolve) => server.listen(0, resolve));
  const { port } = server.address();
  const response = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
  await new Promise((resolve) => server.close(resolve));
});

test("WebSocket signaling rejects connections without a bearer token", async () => {
  const server = createServer(app);
  attachSignaling(server);
  await new Promise((resolve) => server.listen(0, resolve));
  const { port } = server.address();
  const ws = new WebSocket(`ws://127.0.0.1:${port}/signal`);
  const status = await new Promise((resolve, reject) => {
    ws.once("unexpected-response", (_request, response) => {
      response.resume();
      resolve(response.statusCode);
    });
    ws.once("error", reject);
  });
  assert.equal(status, 401);
  await new Promise((resolve) => server.close(resolve));
});

test("target registration only accepts controller accounts and validates installation identity", async () => {
  const server = createServer(app);
  await new Promise((resolve) => server.listen(0, resolve));
  const { port } = server.address();
  const url = `http://127.0.0.1:${port}/agent/register`;

  const agentResponse = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${issueToken({ sub: "target-1", role: "agent" })}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ name: "Target", installId: "invalid" })
  });
  assert.equal(agentResponse.status, 403);

  const controllerResponse = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${issueToken({ sub: "owner-1", role: "controller" })}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ name: "Target", installId: "invalid" })
  });
  assert.equal(controllerResponse.status, 400);
  assert.deepEqual(await controllerResponse.json(), {
    error: "Valid target installation ID required"
  });
  await new Promise((resolve) => server.close(resolve));
});

test("target authorization persists until explicit revocation", async () => {
  await redis.connect();
  let server = createServer(app);
  try {
    await new Promise((resolve) => server.listen(0, resolve));
    const ownerId = randomUUID();
    const ownerToken = issueToken({ sub: ownerId, role: "controller" });
    const registrationResponse = await fetch(`http://127.0.0.1:${server.address().port}/devices`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${ownerToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ name: "Persistent target" })
    });
    assert.equal(registrationResponse.status, 201);
    const { device, agentToken } = await registrationResponse.json();
    const sessionId = randomUUID();
    await redis.set(keys.session(sessionId), JSON.stringify({
      id: sessionId, deviceId: device.id, status: "pending"
    }));
    await redis.sAdd(keys.deviceSessions(device.id), sessionId);

    const accessUrl = `http://127.0.0.1:${server.address().port}/agent/access`;
    const enableResponse = await fetch(accessUrl, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${agentToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ enabled: true })
    });
    assert.deepEqual(await enableResponse.json(), { enabled: true });
    assert.equal(await redis.ttl(keys.agentEnabled(device.id)), -1);

    await new Promise((resolve) => server.close(resolve));
    server = createServer(app);
    await new Promise((resolve) => server.listen(0, resolve));
    const restartedAccessUrl = `http://127.0.0.1:${server.address().port}/agent/access`;
    const persistedResponse = await fetch(restartedAccessUrl, {
      headers: { Authorization: `Bearer ${agentToken}` }
    });
    assert.deepEqual(await persistedResponse.json(), { enabled: true });

    const revokeResponse = await fetch(restartedAccessUrl, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${agentToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ enabled: false })
    });
    assert.deepEqual(await revokeResponse.json(), { enabled: false });
    assert.equal(await redis.get(keys.agentEnabled(device.id)), "false");
    assert.equal(JSON.parse(await redis.get(keys.session(sessionId))).status, "ended");
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await redis.quit();
  }
});
