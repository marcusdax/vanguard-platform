import { Router } from 'express';
import { body, validationResult } from 'express-validator';
import bcrypt from 'bcryptjs';
import { AuthRequest, authMiddleware, requireRole } from '../middleware/auth';
import { prisma } from '../prisma';
import { companyScope } from '../middleware/company';
import { uploadSingle } from '../middleware/upload';

const router = Router();
router.use(companyScope);

// GET /companies — get current company info
router.get('/', async (req: AuthRequest, res, next) => {
  try {
    const company = await prisma.company.findUnique({
      where: { id: req.user!.companyId },
      select: { id: true, name: true, address: true, phone: true, logoUrl: true, subscriptionTier: true, settings: true, createdAt: true, updatedAt: true },
    });
    if (!company) return res.status(404).json({ error: 'Company not found' });
    res.json({ company });
  } catch (err) { next(err); }
});

// PATCH /companies — update company info (name, address, phone, logoUrl)
router.patch('/', async (req: AuthRequest, res, next) => {
  try {
    const { name, address, phone, logoUrl } = req.body;
    const company = await prisma.company.update({
      where: { id: req.user!.companyId },
      data: { name, address, phone, logoUrl },
    });
    res.json({ company });
  } catch (err) { next(err); }
});

// PATCH /companies/settings — update company settings JSON
router.patch('/settings', async (req: AuthRequest, res, next) => {
  try {
    const { settings } = req.body;
    const company = await prisma.company.update({
      where: { id: req.user!.companyId },
      data: { settings: settings ?? {} },
    });
    res.json({ company });
  } catch (err) { next(err); }
});

// POST /companies/logo — upload a company logo
router.post('/logo', uploadSingle('logo'), async (req: AuthRequest, res, next) => {
  try {
    const file = req.file as Express.Multer.File | undefined;
    if (!file) {
      return res.status(400).json({ error: 'No logo file provided' });
    }
    // Serve uploaded files from /uploads/<filename>
    const logoUrl = `/uploads/${file.filename}`;
    const company = await prisma.company.update({
      where: { id: req.user!.companyId },
      data: { logoUrl },
    });
    res.json({ company, logoUrl });
  } catch (err) { next(err); }
});

// ---------------------------------------------------------------------------
// User management endpoints (OWNER / MANAGER only)
// ---------------------------------------------------------------------------

// GET /companies/users — list company users (role, isActive, createdAt)
router.get('/users', authMiddleware, requireRole('OWNER', 'MANAGER'), async (req: AuthRequest, res, next) => {
  try {
    const users = await prisma.user.findMany({
      where: { companyId: req.user!.companyId },
      select: {
        id: true,
        email: true,
        firstName: true,
        lastName: true,
        role: true,
        phone: true,
        isActive: true,
        createdAt: true,
        updatedAt: true,
      },
      orderBy: { createdAt: 'desc' },
    });
    res.json({ users });
  } catch (err) { next(err); }
});

// POST /companies/users — create a new company user
router.post(
  '/users',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  [
    body('email').isEmail(),
    body('firstName').notEmpty(),
    body('lastName').notEmpty(),
    body('role').isIn(['OWNER', 'MANAGER', 'SALES_REP', 'PROJECT_MANAGER', 'FIELD_CREW', 'OFFICE_ADMIN']),
    body('password').isLength({ min: 8 }),
  ],
  async (req: AuthRequest, res, next) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const { email, firstName, lastName, role, phone, password } = req.body;

      const passwordHash = await bcrypt.hash(password, 12);

      const user = await prisma.user.create({
        data: {
          companyId: req.user!.companyId,
          email,
          passwordHash,
          firstName,
          lastName,
          role,
          phone: phone || undefined,
        },
        select: {
          id: true,
          email: true,
          firstName: true,
          lastName: true,
          role: true,
          phone: true,
          isActive: true,
          createdAt: true,
          updatedAt: true,
        },
      });

      res.status(201).json({ user });
    } catch (err: any) {
      if (err?.code === 'P2002') {
        return res.status(409).json({ error: 'User with this email already exists', code: 'USER_DUPLICATE_EMAIL' });
      }
      next(err);
    }
  }
);

// PATCH /companies/users/:id — update a company user (firstName, lastName, phone, role, isActive)
router.patch(
  '/users/:id',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  [
    body('firstName').optional().notEmpty(),
    body('lastName').optional().notEmpty(),
    body('phone').optional(),
    body('role').optional().isIn(['OWNER', 'MANAGER', 'SALES_REP', 'PROJECT_MANAGER', 'FIELD_CREW', 'OFFICE_ADMIN']),
    body('isActive').optional().isBoolean(),
  ],
  async (req: AuthRequest, res, next) => {
    try {
      const errors = validationResult(req);
      if (!errors.isEmpty()) {
        return res.status(400).json({ errors: errors.array() });
      }

      const { id } = req.params;
      const { firstName, lastName, phone, role, isActive } = req.body;

      // Build update data — only allow specific fields (prevent mass assignment)
      const data: Record<string, unknown> = {};
      if (firstName !== undefined) data.firstName = firstName;
      if (lastName !== undefined) data.lastName = lastName;
      if (phone !== undefined) data.phone = phone;
      if (role !== undefined) data.role = role;
      if (isActive !== undefined) data.isActive = isActive;

      const user = await prisma.user.update({
        where: { id, companyId: req.user!.companyId },
        data,
        select: {
          id: true,
          email: true,
          firstName: true,
          lastName: true,
          role: true,
          phone: true,
          isActive: true,
          createdAt: true,
          updatedAt: true,
        },
      });

      res.json({ user });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res.status(404).json({ error: 'User not found' });
      }
      next(err);
    }
  }
);

// DELETE /companies/users/:id — deactivate a company user (set isActive=false)
router.delete(
  '/users/:id',
  authMiddleware,
  requireRole('OWNER', 'MANAGER'),
  async (req: AuthRequest, res, next) => {
    try {
      const { id } = req.params;

      // Prevent self-deactivation
      if (id === req.user!.id) {
        return res.status(400).json({ error: 'Cannot deactivate your own account' });
      }

      const user = await prisma.user.update({
        where: { id, companyId: req.user!.companyId },
        data: { isActive: false },
        select: {
          id: true,
          email: true,
          firstName: true,
          lastName: true,
          role: true,
          phone: true,
          isActive: true,
          createdAt: true,
          updatedAt: true,
        },
      });

      res.json({ message: 'User deactivated successfully', user });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res.status(404).json({ error: 'User not found' });
      }
      next(err);
    }
  }
);

export default router;
