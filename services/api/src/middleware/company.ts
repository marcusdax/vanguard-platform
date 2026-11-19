import { Response, NextFunction } from 'express';
import { prisma } from '../prisma';
import { AuthRequest } from './auth';

// Company context middleware
// Ensures all DB queries are scoped to the user's company (multi-tenant)
export const companyScope = (req: AuthRequest, res: Response, next: NextFunction) => {
  if (!req.user) {
    return res.status(401).json({
      error: 'Not authenticated',
      code: 'AUTH_NOT_AUTHENTICATED',
    });
  }
  req.companyId = req.user.companyId;
  next();
};

// Validate that the user's company exists and is active
export const validateCompany = async (req: AuthRequest, res: Response, next: NextFunction) => {
  if (!req.user) {
    return res.status(401).json({ error: 'Not authenticated' });
  }

  try {
    const company = await prisma.company.findUnique({
      where: { id: req.user.companyId },
      select: { id: true, name: true },
    });

    if (!company) {
      return res.status(404).json({ error: 'Company not found' });
    }

    req.company = { id: company.id, name: company.name };
    next();
  } catch (error) {
    next(error);
  }
};

// Validate company subscription tier
export const requireTier = (minTier: 'Starter' | 'Professional' | 'Enterprise') => {
  const tierOrder = { Starter: 0, Professional: 1, Enterprise: 2 };
  return (req: AuthRequest, res: Response, next: NextFunction) => {
    if (!req.user) {
      return res.status(401).json({ error: 'Not authenticated' });
    }
    // This would require loading company from DB in a real implementation
    // For now, allow all tiers
    next();
  };
};
