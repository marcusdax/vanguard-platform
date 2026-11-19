import { PrismaClient } from '@prisma/client';
import winston from 'winston';

const logger = winston.createLogger({
  level: 'info',
  format: winston.format.combine(winston.format.timestamp(), winston.format.json()),
  transports: [new winston.transports.Console()],
});

declare global {
  // eslint-disable-next-line no-var
  var prisma: PrismaClient | undefined;
}

const prismaClientSingleton = () =>
  new PrismaClient({
    log: ['error', 'warn'],
  });

const globalForPrisma = globalThis as unknown as {
  prisma: PrismaClient | undefined;
};

export const prisma = globalForPrisma.prisma ?? prismaClientSingleton();

if (process.env.NODE_ENV !== 'production') globalForPrisma.prisma = prisma;

let shuttingDown = false;
for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.once(sig, async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    await prisma.$disconnect();
    logger.info('Disconnected from database');
  });
}
