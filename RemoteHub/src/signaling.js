import { WebSocketServer } from "ws";
import { verifyToken } from "./auth.js";
import { getSession, writeAudit } from "./store.js";
import { onSessionEnded } from "./session-events.js";

const MAX_MESSAGE_BYTES = 64 * 1024;
const ALLOWED_TYPES = new Set(["join", "offer", "answer", "ice"]);

function parseBearer(request) {
  const value = request.headers.authorization ?? "";
  const match = /^Bearer ([^\s]+)$/.exec(value);
  if (!match) throw new Error("Bearer token required");
  return verifyToken(match[1]);
}

export function authorizeSignal(session, principal, type) {
  if (!session || session.status !== "accepted") return false;
  const isController = principal.role === "controller" && principal.sub === session.controllerId;
  const isAgent = principal.role === "agent" && principal.sub === session.deviceId;
  if (!isController && !isAgent) return false;
  if (type === "offer") return isController;
  if (type === "answer") return isAgent;
  return type === "join" || type === "ice";
}

export function attachSignaling(server) {
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: MAX_MESSAGE_BYTES,
    perMessageDeflate: false
  });
  const peers = new Map();
  onSessionEnded((sessionId) => {
    const room = peers.get(sessionId);
    if (!room) return;
    const payload = JSON.stringify({ sessionId, type: "session_ended" });
    for (const peer of room) {
      if (peer.readyState === peer.OPEN) peer.send(payload);
    }
    peers.delete(sessionId);
  });

  server.on("upgrade", (request, socket, head) => {
    if (new URL(request.url, "https://localhost").pathname !== "/signal") {
      socket.destroy();
      return;
    }
    let principal;
    try {
      principal = parseBearer(request);
    } catch {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => wss.emit("connection", ws, principal));
  });

  wss.on("connection", (ws, principal) => {
    ws.on("message", (data) => {
      void (async () => {
        let message;
        try {
          message = JSON.parse(data.toString());
        } catch {
          ws.close(1008, "Invalid message");
          return;
        }
        if (typeof message.sessionId !== "string" || !ALLOWED_TYPES.has(message.type)) {
          ws.close(1008, "Invalid signaling message");
          return;
        }
        const session = await getSession(message.sessionId);
        if (!session || session.status !== "accepted") {
          ws.close(1008, "Owner approval required");
          return;
        }
        if (!authorizeSignal(session, principal, message.type)) {
          ws.close(1008, "Invalid signaling direction");
          return;
        }
        if (!peers.has(session.id)) peers.set(session.id, new Set());
        const room = peers.get(session.id);
        const isNewPeer = !room.has(ws);
        if (message.type === "join") {
          room.add(ws);
          await writeAudit({
            actorId: principal.sub, sessionId: session.id, deviceId: session.deviceId,
            action: "signaling.join"
          });
          if (isNewPeer) {
            const joined = JSON.stringify({ sessionId: session.id, type: "peer_joined" });
            for (const peer of room) {
              if (peer !== ws && peer.readyState === peer.OPEN) peer.send(joined);
            }
          }
          return;
        }
        if (typeof message.payload !== "object" || message.payload === null || Array.isArray(message.payload)) {
          ws.close(1008, "Signaling payload required");
          return;
        }
        const payload = JSON.stringify({
          sessionId: session.id,
          type: message.type,
          payload: message.payload
        });
        await writeAudit({
          actorId: principal.sub, sessionId: session.id, deviceId: session.deviceId,
          action: `signaling.${message.type}`
        });
        room.add(ws);
        for (const peer of room) {
          if (peer !== ws && peer.readyState === peer.OPEN) peer.send(payload);
        }
      })().catch((error) => {
        console.error("Signaling message failed", error);
        ws.close(1011, "Signaling unavailable");
      });
    });
    ws.on("close", () => {
      for (const [id, room] of peers) {
        room.delete(ws);
        if (room.size === 0) peers.delete(id);
      }
    });
  });
  return wss;
}
