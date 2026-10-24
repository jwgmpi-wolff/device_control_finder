import express from "express";
import bcrypt from "bcryptjs";
import { randomUUID } from "node:crypto";
import { authenticate, issueToken, requireRole } from "./auth.js";
import { config } from "./config.js";
import { getDevice, getSession, isAgentEnabled, keys, redis, writeAudit } from "./store.js";
import { sendSessionRequest } from "./push.js";
import { publishSessionEnded } from "./session-events.js";

const app = express();
app.use(express.json({ limit: "32kb" }));
app.use((req, res, next) => {
  res.set("Cache-Control", "no-store");
  if (config.allowedOrigin && req.get("origin") === config.allowedOrigin) {
    res.set("Access-Control-Allow-Origin", config.allowedOrigin);
    res.set("Vary", "Origin");
    res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  }
  if (req.method === "OPTIONS") return res.sendStatus(204);
  return next();
});

const allowedCapabilities = new Set(["CAMERA", "MICROPHONE", "LOCATION_ONCE"]);
const asyncRoute = (handler) => (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);

app.get("/health", (_req, res) => res.json({ ok: true }));
app.get("/ready", asyncRoute(async (_req, res) => {
  await redis.ping();
  return res.json({ ready: true });
}));

app.post("/auth/register", asyncRoute(async (req, res) => {
  const email = String(req.body.email ?? "").trim().toLowerCase();
  const password = String(req.body.password ?? "");
  if (email.length > 254 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)
    || password.length < 12 || Buffer.byteLength(password, "utf8") > 72) {
    return res.status(400).json({ error: "Valid email and password (12+ characters) required" });
  }
  if (await redis.exists(keys.email(email))) return res.status(409).json({ error: "Account already exists" });
  const id = randomUUID();
  const user = { id, email, passwordHash: await bcrypt.hash(password, 12) };
  await redis.set(keys.user(id), JSON.stringify(user));
  await redis.set(keys.email(email), id);
  await writeAudit({ actorId: id, action: "account.register" });
  return res.status(201).json({ token: issueToken({ sub: id, role: "controller" }), userId: id });
}));

app.post("/auth/login", asyncRoute(async (req, res) => {
  const email = String(req.body.email ?? "").trim().toLowerCase();
  const id = await redis.get(keys.email(email));
  const user = id ? JSON.parse(await redis.get(keys.user(id))) : null;
  if (!user || !(await bcrypt.compare(String(req.body.password ?? ""), user.passwordHash))) {
    await writeAudit({ actorId: "anonymous", action: "auth.login_failed" });
    return res.status(401).json({ error: "Invalid credentials" });
  }
  await writeAudit({ actorId: id, action: "auth.login" });
  return res.json({ token: issueToken({ sub: id, role: "controller" }), userId: id });
}));

app.post("/devices", authenticate, requireRole("controller"), asyncRoute(async (req, res) => {
  const name = String(req.body.name ?? "").trim();
  const fcmToken = String(req.body.fcmToken ?? "").trim();
  if (!name || name.length > 80) {
    return res.status(400).json({ error: "Device name required (maximum 80 characters)" });
  }
  const id = randomUUID();
  const device = { id, ownerId: req.principal.sub, name, fcmToken, createdAt: new Date().toISOString() };
  await redis.set(keys.device(id), JSON.stringify(device));
  await redis.sAdd(keys.ownerDevices(req.principal.sub), id);
  await writeAudit({ actorId: req.principal.sub, deviceId: id, action: "device.register" });
  return res.status(201).json({
    device: { id, name, createdAt: device.createdAt },
    agentToken: issueToken({ sub: id, ownerId: req.principal.sub, role: "agent" }, "30d")
  });
}));

app.get("/devices", authenticate, requireRole("controller"), asyncRoute(async (req, res) => {
  const ids = await redis.sMembers(keys.ownerDevices(req.principal.sub));
  const devices = (await Promise.all(ids.map(getDevice)))
    .filter(Boolean).map(({ id, name, createdAt }) => ({ id, name, createdAt }));
  return res.json({ devices });
}));

app.get("/agent/access", authenticate, requireRole("agent"), asyncRoute(async (req, res) => {
  return res.json({ enabled: await isAgentEnabled(req.principal.sub) });
}));

app.put("/agent/access", authenticate, requireRole("agent"), asyncRoute(async (req, res) => {
  if (typeof req.body.enabled !== "boolean") {
    return res.status(400).json({ error: "enabled must be a boolean" });
  }
  await redis.set(keys.agentEnabled(req.principal.sub), String(req.body.enabled));
  if (!req.body.enabled) {
    const sessionIds = await redis.sMembers(keys.deviceSessions(req.principal.sub));
    for (const sessionId of sessionIds) {
      const session = await getSession(sessionId);
      if (!session || !["pending", "accepted"].includes(session.status)) continue;
      session.status = "ended";
      session.endedAt = new Date().toISOString();
      session.endReason = "owner_disabled_access";
      await redis.set(keys.session(sessionId), JSON.stringify(session), { EX: 3600 });
      publishSessionEnded(sessionId);
      await writeAudit({
        actorId: req.principal.sub, deviceId: session.deviceId, sessionId,
        action: "session.ended_access_disabled"
      });
    }
  }
  await writeAudit({
    actorId: req.principal.sub,
    action: req.body.enabled ? "agent.access_enabled" : "agent.access_disabled"
  });
  return res.json({ enabled: req.body.enabled });
}));

app.post("/devices/:deviceId/requests", authenticate, requireRole("controller"), asyncRoute(async (req, res) => {
  const device = await getDevice(req.params.deviceId);
  if (!device || device.ownerId !== req.principal.sub) return res.status(404).json({ error: "Device not found" });
  if (!(await isAgentEnabled(device.id))) return res.status(409).json({ error: "Agent is not accepting session requests" });
  const capability = String(req.body.capability ?? "");
  if (!allowedCapabilities.has(capability)) return res.status(400).json({ error: "Unsupported capability" });
  const sessionId = randomUUID();
  const session = {
    id: sessionId, deviceId: device.id, ownerId: device.ownerId,
    controllerId: req.principal.sub, capability, status: "pending",
    createdAt: new Date().toISOString()
  };
  await redis.set(keys.session(sessionId), JSON.stringify(session), { EX: 300 });
  await redis.sAdd(keys.deviceSessions(device.id), sessionId);
  await redis.expire(keys.deviceSessions(device.id), 30 * 24 * 60 * 60);
  await writeAudit({ actorId: req.principal.sub, deviceId: device.id, sessionId, capability, action: "session.requested" });
  try {
    await sendSessionRequest(device, session);
  } catch (error) {
    await redis.del(keys.session(sessionId));
    await writeAudit({ actorId: req.principal.sub, deviceId: device.id, sessionId, action: "session.push_failed" });
    throw error;
  }
  return res.status(202).json({ sessionId, status: "pending_owner_approval" });
}));

app.post("/agent/sessions/:sessionId/decision", authenticate, requireRole("agent"), asyncRoute(async (req, res) => {
  const session = await getSession(req.params.sessionId);
  if (!session || session.deviceId !== req.principal.sub || session.status !== "pending"
    || !(await isAgentEnabled(req.principal.sub))) {
    return res.status(404).json({ error: "Pending request not found" });
  }
  if (req.body.decision !== "accept" && req.body.decision !== "decline") {
    return res.status(400).json({ error: "Decision must be accept or decline" });
  }
  session.status = req.body.decision === "accept" ? "accepted" : "declined";
  session.decidedAt = new Date().toISOString();
  await redis.set(keys.session(session.id), JSON.stringify(session), { EX: 3600 });
  await writeAudit({
    actorId: req.principal.sub, deviceId: session.deviceId, sessionId: session.id,
    capability: session.capability, action: `session.${session.status}`
  });
  if (session.status === "declined") publishSessionEnded(session.id);
  return res.json({ sessionId: session.id, status: session.status });
}));

app.post("/agent/sessions/:sessionId/end", authenticate, requireRole("agent"), asyncRoute(async (req, res) => {
  const session = await getSession(req.params.sessionId);
  if (!session || session.deviceId !== req.principal.sub || session.status !== "accepted") {
    return res.status(404).json({ error: "Active session not found" });
  }
  session.status = "ended";
  session.endedAt = new Date().toISOString();
  await redis.set(keys.session(session.id), JSON.stringify(session), { EX: 3600 });
  publishSessionEnded(session.id);
  await writeAudit({ actorId: req.principal.sub, deviceId: session.deviceId, sessionId: session.id, action: "session.ended_by_owner" });
  return res.json({ sessionId: session.id, status: session.status });
}));

app.put("/agent/fcm-token", authenticate, requireRole("agent"), asyncRoute(async (req, res) => {
  const fcmToken = String(req.body.fcmToken ?? "").trim();
  if (!fcmToken || fcmToken.length > 4096) {
    return res.status(400).json({ error: "Valid FCM token required" });
  }
  const device = await getDevice(req.principal.sub);
  if (!device || device.ownerId !== req.principal.ownerId) return res.status(404).json({ error: "Device not found" });
  device.fcmToken = fcmToken;
  await redis.set(keys.device(device.id), JSON.stringify(device));
  await writeAudit({ actorId: device.id, deviceId: device.id, action: "device.fcm_token_updated" });
  return res.sendStatus(204);
}));

app.get("/sessions/:sessionId", authenticate, asyncRoute(async (req, res) => {
  const session = await getSession(req.params.sessionId);
  if (!session || (req.principal.sub !== session.controllerId && req.principal.sub !== session.deviceId)) {
    return res.status(404).json({ error: "Session not found" });
  }
  return res.json({ sessionId: session.id, capability: session.capability, status: session.status });
}));

app.use((error, _req, res, _next) => {
  console.error("Request failed", error);
  return res.status(500).json({ error: "Internal server error" });
});

export { app };
