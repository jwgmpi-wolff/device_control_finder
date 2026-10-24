import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { WebSocket } from "ws";

process.env.REDIS_URL ??= "redis://127.0.0.1:6379";
process.env.JWT_SECRET ??= "test-only-secret-that-is-long-enough-32";
process.env.JWT_ISSUER ??= "remotehub-test";
process.env.JWT_AUDIENCE ??= "remote-support-test";

const { app } = await import("../src/app.js");
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
