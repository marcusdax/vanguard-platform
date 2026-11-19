#!/bin/sh
set -e

echo "Running Prisma DB push..."
npx prisma db push --skip-generate 2>&1 || echo "prisma db push warning, continuing..."

echo "Ensuring standalone demo company & Vanguard Field operator exist..."
node -e "
const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const p = new PrismaClient();
(async () => {
  try {
    const existing = await p.user.findUnique({ where: { email: 'operator@vanguardfield.io' } });
    if (!existing) {
      let company = await p.company.findFirst();
      if (!company) {
        company = await p.company.create({
          data: {
            name: 'Vanguard Field Operations',
            email: 'ops@vanguardfield.io',
            phone: '(555) 204-8810',
            address: 'Burleson / DFW Metroplex, TX',
          },
        });
      }
      const passwordHash = await bcrypt.hash('Vanguard!2026', 10);
      await p.user.create({
        data: {
          companyId: company.id,
          email: 'operator@vanguardfield.io',
          passwordHash,
          firstName: 'Jordan',
          lastName: 'Vance',
          role: 'OWNER',
        },
      });
      console.log('Seeded standalone Vanguard operator: operator@vanguardfield.io');
    }
  } catch (e) {
    console.warn('Bootstrap seed note:', e.message);
  } finally {
    await p.\$disconnect();
  }
})();
"

echo "Starting Vanguard Platform API..."
npx tsx dist/src/server.js
