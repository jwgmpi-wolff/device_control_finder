import { createServer } from "node:http";
import { app } from "./app.js";
import { config } from "./config.js";
import { redis } from "./store.js";
import { attachSignaling } from "./signaling.js";

await redis.connect();
const server = createServer(app);
attachSignaling(server);
server.listen(config.port, () => console.log(`RemoteHub listening on ${config.port}`));

async function shutdown() {
  server.close();
  await redis.quit();
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
