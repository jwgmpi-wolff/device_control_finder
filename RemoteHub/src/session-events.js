import { EventEmitter } from "node:events";

const events = new EventEmitter();

export function onSessionEnded(listener) {
  events.on("ended", listener);
  return () => events.off("ended", listener);
}

export function publishSessionEnded(sessionId) {
  events.emit("ended", sessionId);
}
