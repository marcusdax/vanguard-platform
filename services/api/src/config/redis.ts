import Redis from 'ioredis';
import winston from 'winston';

const logger = winston.createLogger({
  level: 'info',
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.json()
  ),
  transports: [new winston.transports.Console()],
});

const redisUrl = process.env.REDIS_URL || 'redis://localhost:6379';

let redis: Redis | null = null;

try {
  redis = new Redis(redisUrl);
  redis.on('error', (err) => {
    logger.error('Redis connection error', { error: err.message });
  });
  redis.on('connect', () => {
    logger.info('Connected to Redis');
  });
} catch (err) {
  logger.warn('Redis connection failed — running without cache', {
    error: (err as Error).message,
  });
}

export const getRedis = () => redis;
export const redisClient = redis;

// Simple in-memory fallback cache for when Redis is unavailable
const memoryCache = new Map<string, { value: unknown; expiresAt: number }>();

export const cacheGet = async (key: string): Promise<string | null> => {
  if (redis) {
    return redis.get(key);
  }
  const cached = memoryCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return JSON.stringify(cached.value);
  }
  memoryCache.delete(key);
  return null;
};

export const cacheSet = async (
  key: string,
  value: unknown,
  ttlSeconds = 300
): Promise<void> => {
  if (redis) {
    await redis.setex(key, ttlSeconds, JSON.stringify(value));
    return;
  }
  memoryCache.set(key, {
    value,
    expiresAt: Date.now() + ttlSeconds * 1000,
  });
};

export const cacheDel = async (key: string): Promise<void> => {
  if (redis) {
    await redis.del(key);
    return;
  }
  memoryCache.delete(key);
};
