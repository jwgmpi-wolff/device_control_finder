import admin from "firebase-admin";
import { config } from "./config.js";

let messaging;
if (config.firebaseServiceAccountJson) {
  const credential = JSON.parse(config.firebaseServiceAccountJson);
  admin.initializeApp({ credential: admin.credential.cert(credential) });
  messaging = admin.messaging();
}

export async function sendSessionRequest(device, session) {
  if (!messaging) throw new Error("Firebase Admin is not configured");
  await messaging.send({
    token: device.fcmToken,
    data: {
      type: "SESSION_REQUEST",
      sessionId: session.id,
      capability: session.capability,
      expiresAt: String(Date.parse(session.createdAt) + 5 * 60 * 1000)
    },
    android: { priority: "high" }
  });
}
