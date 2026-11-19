/**
 * Public unauthenticated Vanguard routes (/api/v1/public/vanguard):
 *  - GET /catalog            -> 4 Tiers, 23 Academy modules, 10 Verticals, FeeSchedule (C-09)
 *  - GET /verify/:packetId   -> RFC 6962 Proof Bundle + Chain for < 5 KB Browser Verifier (C-04)
 */
import { Router, Request, Response, NextFunction } from 'express';
import { VANGUARD_CURRICULUM } from '../engine/credentials';
import {
  FEE_SCHEDULE_RULES,
  IRS_MILEAGE_CENTS_H1_2026,
  IRS_MILEAGE_CENTS_H2_2026,
  PRELAUNCH_STAGING_DISCLOSURE,
  computeEconomicHalfLife,
} from '../engine/economics';
import { DEMAND_VERTICALS, HARDWARE_GEAR_CATALOG, VANGUARD_TIERS } from '../services/vanguard/catalog';
import { getPublicVerificationBundle } from '../services/vanguard/ledgerChain';

const router = Router();

router.get('/catalog', (_req: Request, res: Response) => {
  res.json({
    tiers: VANGUARD_TIERS,
    modules: VANGUARD_CURRICULUM,
    verticals: DEMAND_VERTICALS,
    hardwareCatalog: HARDWARE_GEAR_CATALOG,
    feeSchedule: Object.values(FEE_SCHEDULE_RULES),
    irsMileageCents: {
      h1_2026: IRS_MILEAGE_CENTS_H1_2026,
      h2_2026: IRS_MILEAGE_CENTS_H2_2026,
    },
    halfLifeSample: [0, 30, 60, 90, 120, 180, 240, 300, 365].map((d) => computeEconomicHalfLife(d)),
    stagingDisclosure: PRELAUNCH_STAGING_DISCLOSURE,
  });
});

router.get('/verify/:packetId', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const bundle = await getPublicVerificationBundle(req.params.packetId);
    if (!bundle) {
      return res.status(404).json({ error: 'Evidence packet not found.' });
    }
    res.json(bundle);
  } catch (err) {
    next(err);
  }
});

export default router;
