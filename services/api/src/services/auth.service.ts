import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { prisma } from '../prisma';

import { JWT_SECRET, JWT_REFRESH_SECRET } from '../config/secrets';
const JWT_ACCESS_EXPIRES_IN = process.env.JWT_ACCESS_EXPIRES_IN || '15m';
const JWT_REFRESH_EXPIRES_IN = process.env.JWT_REFRESH_EXPIRES_IN || '7d';

export interface RegisterData {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  companyName: string;
  phone?: string;
  plan?: string;
}

export interface LoginData {
  email: string;
  password: string;
}

export interface JwtPayload {
  userId: string;
  companyId: string;
  email: string;
  role: string;
}

export const generateAccessToken = (payload: JwtPayload): string => {
  return jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_ACCESS_EXPIRES_IN as any });
};

export const generateRefreshToken = (payload: JwtPayload): string => {
  return jwt.sign(payload, JWT_REFRESH_SECRET, { expiresIn: JWT_REFRESH_EXPIRES_IN as any });
};

export const verifyRefreshToken = (token: string): JwtPayload => {
  return jwt.verify(token, JWT_REFRESH_SECRET) as JwtPayload;
};

export const hashPassword = async (password: string): Promise<string> => {
  return bcrypt.hash(password, 12);
};

export const comparePassword = async (password: string, hash: string): Promise<boolean> => {
  return bcrypt.compare(password, hash);
};

export const registerCompany = async (data: RegisterData) => {
  const existingUser = await prisma.user.findUnique({
    where: { email: data.email },
  });

  if (existingUser) {
    throw new Error('User with this email already exists');
  }

  const passwordHash = await hashPassword(data.password);

  const result = await prisma.$transaction(async (tx) => {
    const company = await tx.company.create({
      data: {
        name: data.companyName,
        phone: data.phone,
        settings: {},
      },
    });

    const user = await tx.user.create({
      data: {
        companyId: company.id,
        email: data.email,
        passwordHash,
        firstName: data.firstName,
        lastName: data.lastName,
        role: 'OWNER',
      },
    });

    return { company, user };
  });

  const payload: JwtPayload = {
    userId: result.user.id,
    companyId: result.company.id,
    email: result.user.email,
    role: result.user.role,
  };

  return {
    user: {
      id: result.user.id,
      email: result.user.email,
      firstName: result.user.firstName,
      lastName: result.user.lastName,
      role: result.user.role,
      companyId: result.company.id,
    },
    company: {
      id: result.company.id,
      name: result.company.name,
    },
    accessToken: generateAccessToken(payload),
    refreshToken: generateRefreshToken(payload),
  };
};

export const loginUser = async (data: LoginData) => {
  const user = await prisma.user.findUnique({
    where: { email: data.email },
    include: { company: true },
  });

  if (!user) {
    throw new Error('Invalid email or password');
  }

  if (!user.isActive) {
    throw new Error('Account is deactivated');
  }

  const isValid = await comparePassword(data.password, user.passwordHash);

  if (!isValid) {
    throw new Error('Invalid email or password');
  }

  const payload: JwtPayload = {
    userId: user.id,
    companyId: user.companyId,
    email: user.email,
    role: user.role,
  };

  return {
    user: {
      id: user.id,
      email: user.email,
      firstName: user.firstName,
      lastName: user.lastName,
      role: user.role,
      companyId: user.companyId,
    },
    company: {
      id: user.company.id,
      name: user.company.name,
    },
    accessToken: generateAccessToken(payload),
    refreshToken: generateRefreshToken(payload),
  };
};

export const refreshAccessToken = async (refreshToken: string) => {
  const payload = verifyRefreshToken(refreshToken);

  const user = await prisma.user.findUnique({
    where: { id: payload.userId },
    include: { company: true },
  });

  if (!user || !user.isActive) {
    throw new Error('User not found or inactive');
  }

  const newPayload: JwtPayload = {
    userId: user.id,
    companyId: user.companyId,
    email: user.email,
    role: user.role,
  };

  return {
    accessToken: generateAccessToken(newPayload),
    refreshToken: generateRefreshToken(newPayload),
  };
};

export const getUserById = async (userId: string) => {
  return prisma.user.findUnique({
    where: { id: userId },
    include: {
      company: {
        select: { id: true, name: true, subscriptionTier: true },
      },
    },
  });
};

export const listUsers = async (companyId: string) => {
  return prisma.user.findMany({
    where: { companyId },
    select: {
      id: true,
      email: true,
      firstName: true,
      lastName: true,
      role: true,
      phone: true,
      avatarUrl: true,
      isActive: true,
      createdAt: true,
      updatedAt: true,
    },
    orderBy: { createdAt: 'desc' },
  });
};

export interface UpdateUserData {
  email?: string;
  firstName?: string;
  lastName?: string;
  role?: string;
  phone?: string;
  password?: string;
  isActive?: boolean;
}

const userDetailSelect = {
  id: true,
  email: true,
  firstName: true,
  lastName: true,
  role: true,
  phone: true,
  avatarUrl: true,
  isActive: true,
  createdAt: true,
  updatedAt: true,
};

export const updateUser = async (userId: string, companyId: string, data: UpdateUserData) => {
  // Build update data — only allow specific fields (prevent mass assignment)
  const updateData: Record<string, unknown> = {};
  if (data.email) updateData.email = data.email;
  if (data.firstName) updateData.firstName = data.firstName;
  if (data.lastName) updateData.lastName = data.lastName;
  if (data.role) updateData.role = data.role;
  if (data.phone !== undefined) updateData.phone = data.phone;
  if (data.isActive !== undefined) updateData.isActive = data.isActive;
  if (data.password) {
    updateData.passwordHash = await hashPassword(data.password);
  }

  return prisma.user.update({
    where: { id: userId, companyId },
    data: updateData,
    select: userDetailSelect,
  });
};

export const toggleUserStatus = async (userId: string, companyId: string, isActive: boolean) => {
  return prisma.user.update({
    where: { id: userId, companyId },
    data: { isActive },
    select: userDetailSelect,
  });
};
