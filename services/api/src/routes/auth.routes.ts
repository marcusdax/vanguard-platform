/**
 * Authentication Routes
 * POST /auth/register — Register new company + owner user
 * POST /auth/login — Login + issue JWT tokens
 * POST /auth/refresh — Refresh access token
 * POST /auth/logout — Logout (invalidate refresh token in Redis)
 * GET  /auth/me — Get current user profile
 * GET  /auth/users — List all users in company (admin only)
 */
import { Router, Request, Response, NextFunction } from 'express';
import { body, validationResult } from 'express-validator';
import { registerCompany, loginUser, refreshAccessToken, getUserById, listUsers, updateUser, toggleUserStatus } from '../services/auth.service';
import { prisma } from '../prisma';
import { cacheDel } from '../config/redis';
import { authMiddleware, requireRole, AuthRequest } from '../middleware/auth';
import bcrypt from 'bcryptjs';
import { loginLimiter, refreshLimiter, registerLimiter } from '../middleware/authLimits';

const router = Router();

// POST /auth/register
router.post(
  '/register',
  registerLimiter,
  [
    body('email').isEmail(),
    body('password').isLength({ min: 8 }),
    body('firstName').notEmpty(),
    body('lastName').notEmpty(),
    body('companyName').notEmpty(),
  ],
  async (req, res, next) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const result = await registerCompany(req.body);

      res.status(201).json({
        user: result.user,
        company: result.company,
        accessToken: result.accessToken,
        refreshToken: result.refreshToken,
      });
    } catch (error) {
      next(error);
    }
  }
);

// POST /auth/login
router.post(
  '/login',
  loginLimiter,
  [
    body('email').isEmail(),
    body('password').notEmpty(),
  ],
  async (req, res, next) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const result = await loginUser(req.body);

      res.json({
        user: result.user,
        company: result.company,
        accessToken: result.accessToken,
        refreshToken: result.refreshToken,
      });
    } catch (error) {
      if ((error as Error).message === 'Invalid email or password') {
        return res.status(401).json({
          error: 'Invalid email or password',
          code: 'AUTH_INVALID_CREDENTIALS',
        });
      }
      if ((error as Error).message === 'Account is deactivated') {
        return res.status(403).json({
          error: 'Account is deactivated',
          code: 'AUTH_ACCOUNT_DEACTIVATED',
        });
      }
      next(error);
    }
  }
);

// POST /auth/refresh
router.post(
  '/refresh',
  refreshLimiter,
  [body('refreshToken').notEmpty()],
  async (req, res, next) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const result = await refreshAccessToken(req.body.refreshToken);

      res.json({
        accessToken: result.accessToken,
        refreshToken: result.refreshToken,
      });
    } catch (error) {
      res.status(401).json({
        error: 'Invalid refresh token',
        code: 'AUTH_INVALID_REFRESH_TOKEN',
      });
    }
  }
);

// POST /auth/logout
router.post('/logout', async (req, res, next) => {
  try {
    const refreshToken = req.body.refreshToken;
    if (refreshToken) {
      await cacheDel(`refresh:${refreshToken}`);
    }
    res.json({ message: 'Logged out successfully' });
  } catch (error) {
    next(error);
  }
});

// GET /auth/me
// NOTE: /auth is mounted before the global auth middleware, so the route must
// attach authMiddleware itself — without it req.user is always undefined and a
// page reload would log the user out (fixed in v1.1).
router.get('/me', authMiddleware, async (req: AuthRequest, res, next) => {
  try {
    if (!req.user) {
      return res.status(401).json({ error: 'Not authenticated' });
    }

    const user = await prisma.user.findUnique({
      where: { id: req.user.id },
      include: {
        company: {
          select: {
            id: true,
            name: true,
            logoUrl: true,
            subscriptionTier: true,
            settings: true,
          },
        },
      },
    });

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    res.json({
      user: {
        id: user.id,
        email: user.email,
        firstName: user.firstName,
        lastName: user.lastName,
        role: user.role,
        phone: user.phone,
        avatarUrl: user.avatarUrl,
        company: user.company,
      },
    });
  } catch (error) {
    next(error);
  }
});

// GET /auth/users — list company users (all authenticated users, for @mention autocomplete)
router.get('/users', authMiddleware, async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const users = await listUsers(req.user.companyId);
    res.json({ users });
  } catch (error) {
    next(error);
  }
});

// POST /auth/users — create new user (OWNER/MANAGER only)
router.post(
  '/users',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  [
    body('email').isEmail(),
    body('firstName').notEmpty(),
    body('lastName').notEmpty(),
    body('role').isIn(['OWNER', 'MANAGER', 'SALES_REP', 'PROJECT_MANAGER', 'FIELD_CREW', 'OFFICE_ADMIN']),
  ],
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const passwordHash = req.body.password
        ? await bcrypt.hash(req.body.password, 12)
        : `temp-${Math.random().toString(36).slice(2)}`;

      const user = await prisma.user.create({
        data: {
          companyId: req.user!.companyId,
          email: req.body.email,
          passwordHash,
          firstName: req.body.firstName,
          lastName: req.body.lastName,
          role: req.body.role,
          phone: req.body.phone,
        },
        select: {
          id: true,
          email: true,
          firstName: true,
          lastName: true,
          role: true,
          phone: true,
        },
      });

      res.status(201).json({ user });
    } catch (error) {
      next(error);
    }
  }
);

// PATCH /users/:id/toggle-status — deactivate/reactivate a company user (OWNER/MANAGER only)
router.patch(
  '/users/:id/toggle-status',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  [body('isActive').isBoolean()],
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      // Prevent an admin from locking themselves out
      if (req.body.isActive === false && req.params.id === req.user!.id) {
        return res.status(400).json({ error: 'You cannot deactivate your own account' });
      }

      const user = await toggleUserStatus(req.params.id, req.user!.companyId, req.body.isActive);
      res.json({ user });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res.status(404).json({ error: 'User not found' });
      }
      next(err);
    }
  }
);

// PATCH /users/:id — update a company user (OWNER/MANAGER only)
router.patch(
  '/users/:id',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  [
    body('email').optional().isEmail(),
    body('firstName').optional().notEmpty(),
    body('lastName').optional().notEmpty(),
    body('role').optional().isIn(['OWNER', 'MANAGER', 'SALES_REP', 'PROJECT_MANAGER', 'FIELD_CREW', 'OFFICE_ADMIN']),
    body('phone').optional().isString(),
    body('password').optional().isLength({ min: 8 }),
  ],
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const user = await updateUser(req.params.id, req.user!.companyId, req.body);
      res.json({ user });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res.status(404).json({ error: 'User not found' });
      }
      next(err);
    }
  }
);

export default router;
