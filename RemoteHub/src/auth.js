import jwt from "jsonwebtoken";
import { config } from "./config.js";

export function issueToken(claims, expiresIn = config.jwtTtl) {
  return jwt.sign(claims, config.jwtSecret, {
    algorithm: "HS256",
    expiresIn,
    issuer: config.jwtIssuer,
    audience: config.jwtAudience
  });
}

export function verifyToken(token) {
  return jwt.verify(token, config.jwtSecret, {
    algorithms: ["HS256"],
    issuer: config.jwtIssuer,
    audience: config.jwtAudience
  });
}

export function authenticate(req, res, next) {
  const header = req.get("authorization") ?? "";
  const match = /^Bearer ([^\s]+)$/.exec(header);
  if (!match) return res.status(401).json({ error: "Bearer token required" });
  try {
    req.principal = verifyToken(match[1]);
    return next();
  } catch {
    return res.status(401).json({ error: "Invalid or expired token" });
  }
}

export function requireRole(...roles) {
  return (req, res, next) => {
    if (!roles.includes(req.principal?.role)) {
      return res.status(403).json({ error: "Insufficient role" });
    }
    return next();
  };
}
