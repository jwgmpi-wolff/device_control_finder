import "dotenv/config";

const required = ["REDIS_URL", "JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE"];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing required environment variable: ${key}`);
}
if (process.env.JWT_SECRET.length < 32) {
  throw new Error("JWT_SECRET must contain at least 32 characters");
}
if (process.env.JWT_SECRET.toLowerCase().includes("replace-with")) {
  throw new Error("JWT_SECRET still contains the example placeholder; generate a unique secret");
}

export const config = {
  port: Number(process.env.PORT ?? 8080),
  redisUrl: process.env.REDIS_URL,
  jwtSecret: process.env.JWT_SECRET,
  jwtIssuer: process.env.JWT_ISSUER,
  jwtAudience: process.env.JWT_AUDIENCE,
  jwtTtl: process.env.JWT_TTL ?? "1h",
  allowedOrigin: process.env.ALLOWED_ORIGIN,
  firebaseServiceAccountJson: process.env.FIREBASE_SERVICE_ACCOUNT_JSON
};
