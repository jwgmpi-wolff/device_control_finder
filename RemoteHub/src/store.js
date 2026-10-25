import { createClient } from "redis";
import { config } from "./config.js";

export const redis = createClient({ url: config.redisUrl });
redis.on("error", (error) => console.error("Redis error", error));

export const keys = {
  user: (id) => `user:${id}`,
  email: (email) => `email:${email.toLowerCase()}`,
  device: (id) => `device:${id}`,
  agentInstall: (ownerId, installId) => `owner:${ownerId}:agent-install:${installId}`,
  agentEnabled: (id) => `agent:${id}:enabled`,
  deviceSessions: (id) => `device:${id}:sessions`,
  ownerDevices: (ownerId) => `owner:${ownerId}:devices`,
  session: (id) => `session:${id}`,
  audit: "audit:events"
};

export async function writeAudit(event) {
  await redis.rPush(keys.audit, JSON.stringify({ ...event, at: new Date().toISOString() }));
}

export async function getDevice(id) {
  const data = await redis.get(keys.device(id));
  return data ? JSON.parse(data) : null;
}

export async function getSession(id) {
  const data = await redis.get(keys.session(id));
  return data ? JSON.parse(data) : null;
}

export async function isAgentEnabled(id) {
  return (await redis.get(keys.agentEnabled(id))) === "true";
}
