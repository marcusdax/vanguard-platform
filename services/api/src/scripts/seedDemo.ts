/**
 * Demo dataset seed — RoofPro CRM storm intelligence showcase.
 *
 * Owner: backend-storm. Idempotent: reuses the `demo@roofprocrm.com` company,
 * clears ONLY that company's dependent rows, and recreates a rich demo dataset.
 *
 * Run with:
 *   cd backend
 *   npx tsx src/scripts/seedDemo.ts            (Lead wires `npm run seed:demo`)
 *
 * Login: demo@roofprocrm.com / RoofPro!2026
 *
 * Datasets: 6 users, 24 geocoded leads, 8 jobs, 10 estimates + items, 12 calendar
 * events, 6 feed posts + 2 system posts, 3 manual storm events with polygon
 * geometry around McKinney/DFW, price database entries, 2 subcontractors,
 * 2 material orders and 2 AI usage rows.
 *
 * NOTE: backend/src/prisma.ts registers a `beforeExit` handler that calls
 * `$disconnect()`; the CLI runner below therefore calls `process.exit()` so the
 * script terminates cleanly instead of re-triggering that handler.
 */
import bcrypt from 'bcryptjs';
import type {
  Estimate,
  Job,
  Lead,
  StormEvent,
  User,
} from '@prisma/client';
import { Prisma } from '@prisma/client';
import { prisma } from '../prisma';
import { approximateBufferPolygon } from '../services/storm/geo';
import type { GeoPoint } from '../services/storm/types';
import { rescoreLeadsForCompany } from '../services/storm/stormIntelligence';

export const DEMO_EMAIL = 'demo@roofprocrm.com';
export const DEMO_PASSWORD = 'RoofPro!2026';
const DEMO_COMPANY_NAME = 'RoofPro Demo Roofing';

const DAY_MS = 86_400_000;

function daysAgo(days: number): Date {
  return new Date(Date.now() - days * DAY_MS);
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

// ─────────────────────────────────────────────────────────────
// Demo data definitions
// ─────────────────────────────────────────────────────────────

interface StaffSeed {
  email: string;
  firstName: string;
  lastName: string;
  role: 'MANAGER' | 'SALES_REP' | 'PROJECT_MANAGER' | 'FIELD_CREW' | 'OFFICE_ADMIN';
  phone: string;
}

const STAFF: StaffSeed[] = [
  { email: 'marcus.reid@roofprocrm.com', firstName: 'Marcus', lastName: 'Reid', role: 'MANAGER', phone: '(214) 555-0101' },
  { email: 'sofia.alvarez@roofprocrm.com', firstName: 'Sofia', lastName: 'Alvarez', role: 'SALES_REP', phone: '(214) 555-0102' },
  { email: 'derek.nguyen@roofprocrm.com', firstName: 'Derek', lastName: 'Nguyen', role: 'PROJECT_MANAGER', phone: '(214) 555-0103' },
  { email: 'tasha.brooks@roofprocrm.com', firstName: 'Tasha', lastName: 'Brooks', role: 'FIELD_CREW', phone: '(214) 555-0104' },
  { email: 'ellen.wu@roofprocrm.com', firstName: 'Ellen', lastName: 'Wu', role: 'OFFICE_ADMIN', phone: '(214) 555-0105' },
];

const STORM_SEEDS: Array<{
  name: string;
  description: string;
  severity: 'SEVERE' | 'EXTREME' | 'MODERATE';
  eventType: 'SEVERE_THUNDERSTORM' | 'TORNADO' | 'FLASH_FLOOD';
  center: GeoPoint;
  radiusKm: number;
  startDaysAgo: number;
  endDaysAgo: number;
  /** When set, the event is still active: endDate = now + this many hours. */
  endInHours?: number;
  area: string;
  headline: string;
}> = [
  {
    name: 'North Texas Hail Swath — McKinney',
    description:
      'Widespread 1.75" hail reported across McKinney and Allen. Roof shingle and gutter damage concentrated in the Stonebridge Ranch corridor.',
    severity: 'SEVERE',
    eventType: 'SEVERE_THUNDERSTORM',
    center: { lat: 33.19, lng: -96.7 },
    radiusKm: 18,
    startDaysAgo: 3.2,
    endDaysAgo: 2.9,
    area: 'McKinney, TX; Allen, TX; Fairview, TX',
    headline: 'Severe Thunderstorm Warning — 1.75 inch hail across Collin County',
  },
  {
    name: 'Collin County Tornado Outbreak',
    description:
      'EF-1 tornado track confirmed near Frisco with downed trees and lifted shingles. Emergency canvassing in progress across the Frisco/Prosper corridor.',
    severity: 'EXTREME',
    eventType: 'TORNADO',
    center: { lat: 33.14, lng: -96.76 },
    radiusKm: 12,
    startDaysAgo: 1.1,
    endDaysAgo: 0.6,
    endInHours: 8,
    area: 'Frisco, TX; Prosper, TX; Celina, TX',
    headline: 'Tornado Warning — confirmed EF-1 near Frisco',
  },
  {
    name: 'Trinity River Flash Flood — Dallas',
    description:
      'Training thunderstorms produced urban flash flooding along the Trinity River corridor. Water intrusion and gutter damage reported in Oak Lawn.',
    severity: 'MODERATE',
    eventType: 'FLASH_FLOOD',
    center: { lat: 32.8, lng: -96.81 },
    radiusKm: 15,
    startDaysAgo: 5.4,
    endDaysAgo: 4.7,
    area: 'Dallas, TX; Irving, TX',
    headline: 'Flash Flood Warning — Trinity River corridor',
  },
];

interface LeadSeed {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  address: string;
  lat: number;
  lng: number;
  source: 'WEBSITE' | 'PHONE' | 'REFERRAL' | 'STORM_EVENT' | 'MANUAL' | 'IMPORT';
  status: 'NEW' | 'CONTACTED' | 'INSPECTION_SCHEDULED' | 'ESTIMATE_SENT' | 'WON' | 'LOST';
  score: number;
  stormIndex: number | null;
  staffIndex: number | null;
}

const LEAD_SEEDS: LeadSeed[] = [
  { firstName: 'Avery', lastName: 'Coleman', email: 'avery.coleman@example.com', phone: '(469) 555-1101', address: '1420 W Hunt St, McKinney, TX 75069', lat: 33.1973, lng: -96.6399, source: 'STORM_EVENT', status: 'NEW', score: 88, stormIndex: 0, staffIndex: 1 },
  { firstName: 'Blake', lastName: 'Hoffman', email: 'blake.hoffman@example.com', phone: '(469) 555-1102', address: '3812 Canyon Creek Dr, McKinney, TX 75070', lat: 33.1712, lng: -96.7021, source: 'STORM_EVENT', status: 'CONTACTED', score: 82, stormIndex: 0, staffIndex: 1 },
  { firstName: 'Carmen', lastName: 'Diaz', email: 'carmen.diaz@example.com', phone: '(469) 555-1103', address: '7409 Legacy Oaks Ln, McKinney, TX 75071', lat: 33.218, lng: -96.749, source: 'REFERRAL', status: 'INSPECTION_SCHEDULED', score: 71, stormIndex: 0, staffIndex: 0 },
  { firstName: 'Devon', lastName: 'Price', email: 'devon.price@example.com', phone: '(469) 555-1104', address: '5517 Sweetwater Dr, Frisco, TX 75034', lat: 33.1465, lng: -96.839, source: 'STORM_EVENT', status: 'ESTIMATE_SENT', score: 91, stormIndex: 1, staffIndex: 1 },
  { firstName: 'Elena', lastName: 'Petrov', email: 'elena.petrov@example.com', phone: '(469) 555-1105', address: '2200 Panther Creek Pkwy, Frisco, TX 75033', lat: 33.183, lng: -96.857, source: 'WEBSITE', status: 'NEW', score: 64, stormIndex: 1, staffIndex: 1 },
  { firstName: 'Franklin', lastName: 'Osei', email: 'franklin.osei@example.com', phone: '(469) 555-1106', address: '4901 Ridge Point Dr, Plano, TX 75024', lat: 33.081, lng: -96.793, source: 'PHONE', status: 'CONTACTED', score: 58, stormIndex: 0, staffIndex: 0 },
  { firstName: 'Grace', lastName: 'Whitfield', email: 'grace.whitfield@example.com', phone: '(469) 555-1107', address: '3108 Windy Knoll Dr, Plano, TX 75025', lat: 33.07, lng: -96.748, source: 'REFERRAL', status: 'WON', score: 95, stormIndex: 0, staffIndex: 2 },
  { firstName: 'Hassan', lastName: 'Khan', email: 'hassan.khan@example.com', phone: '(469) 555-1108', address: '1205 Oak Hollow Ln, Allen, TX 75002', lat: 33.101, lng: -96.662, source: 'STORM_EVENT', status: 'INSPECTION_SCHEDULED', score: 79, stormIndex: 0, staffIndex: 1 },
  { firstName: 'Irene', lastName: 'Salazar', email: 'irene.salazar@example.com', phone: '(469) 555-1109', address: '908 Cypress Bend, Allen, TX 75013', lat: 33.1124, lng: -96.6901, source: 'MANUAL', status: 'NEW', score: 55, stormIndex: 0, staffIndex: 2 },
  { firstName: 'Jasper', lastName: 'Mills', email: 'jasper.mills@example.com', phone: '(469) 555-1110', address: '4612 Twin Lakes Way, Prosper, TX 75078', lat: 33.241, lng: -96.809, source: 'STORM_EVENT', status: 'CONTACTED', score: 76, stormIndex: 1, staffIndex: 3 },
  { firstName: 'Keisha', lastName: 'Grant', email: 'keisha.grant@example.com', phone: '(469) 555-1111', address: '700 Heritage Pkwy, Prosper, TX 75078', lat: 33.226, lng: -96.793, source: 'WEBSITE', status: 'ESTIMATE_SENT', score: 84, stormIndex: 1, staffIndex: 1 },
  { firstName: 'Liam', lastName: 'Foster', email: 'liam.foster@example.com', phone: '(469) 555-1112', address: '3315 S Preston Rd, Celina, TX 75009', lat: 33.3166, lng: -96.7842, source: 'STORM_EVENT', status: 'NEW', score: 69, stormIndex: 1, staffIndex: 1 },
  { firstName: 'Maya', lastName: 'Brighton', email: 'maya.brighton@example.com', phone: '(214) 555-1113', address: '8114 Bent Tree Dr, Dallas, TX 75248', lat: 32.998, lng: -96.774, source: 'PHONE', status: 'CONTACTED', score: 61, stormIndex: 2, staffIndex: 0 },
  { firstName: 'Noah', lastName: 'Cruz', email: 'noah.cruz@example.com', phone: '(214) 555-1114', address: '12011 Coit Rd, Dallas, TX 75251', lat: 32.928, lng: -96.768, source: 'WEBSITE', status: 'INSPECTION_SCHEDULED', score: 73, stormIndex: 2, staffIndex: 1 },
  { firstName: 'Olivia', lastName: 'Marsh', email: 'olivia.marsh@example.com', phone: '(214) 555-1115', address: '4421 Maple Springs Blvd, Dallas, TX 75219', lat: 32.809, lng: -96.811, source: 'REFERRAL', status: 'WON', score: 97, stormIndex: 2, staffIndex: 2 },
  { firstName: 'Priya', lastName: 'Raman', email: 'priya.raman@example.com', phone: '(972) 555-1116', address: '2204 Ridgeview Dr, Richardson, TX 75080', lat: 32.953, lng: -96.741, source: 'STORM_EVENT', status: 'ESTIMATE_SENT', score: 86, stormIndex: 2, staffIndex: 1 },
  { firstName: 'Quentin', lastName: 'Banks', email: 'quentin.banks@example.com', phone: '(972) 555-1117', address: '3608 Centennial Dr, Garland, TX 75042', lat: 32.918, lng: -96.646, source: 'MANUAL', status: 'NEW', score: 48, stormIndex: null, staffIndex: 0 },
  { firstName: 'Rosa', lastName: 'Lindstrom', email: 'rosa.lindstrom@example.com', phone: '(972) 555-1118', address: '1510 Carrollton Ave, Carrollton, TX 75006', lat: 32.969, lng: -96.893, source: 'IMPORT', status: 'LOST', score: 32, stormIndex: null, staffIndex: 1 },
  { firstName: 'Samuel', lastName: 'Okafor', email: 'samuel.okafor@example.com', phone: '(940) 555-1119', address: '8900 Teasley Ln, Denton, TX 76210', lat: 33.171, lng: -97.118, source: 'WEBSITE', status: 'CONTACTED', score: 57, stormIndex: null, staffIndex: 2 },
  { firstName: 'Teresa', lastName: 'Vaughn', email: 'teresa.vaughn@example.com', phone: '(817) 555-1120', address: '6200 Oakmont Blvd, Fort Worth, TX 76132', lat: 32.693, lng: -97.401, source: 'IMPORT', status: 'LOST', score: 27, stormIndex: null, staffIndex: 0 },
  { firstName: 'Umar', lastName: 'Haddad', email: 'umar.haddad@example.com', phone: '(972) 555-1121', address: '3311 N O\'Connor Rd, Irving, TX 75062', lat: 32.835, lng: -96.965, source: 'PHONE', status: 'INSPECTION_SCHEDULED', score: 66, stormIndex: 2, staffIndex: 1 },
  { firstName: 'Valerie', lastName: 'Cross', email: 'valerie.cross@example.com', phone: '(972) 555-1122', address: '5904 Summerwind Ln, Sachse, TX 75048', lat: 32.976, lng: -96.591, source: 'REFERRAL', status: 'NEW', score: 52, stormIndex: 0, staffIndex: 3 },
  { firstName: 'Wesley', lastName: 'Drake', email: 'wesley.drake@example.com', phone: '(972) 555-1123', address: '2418 Lakeview Pkwy, Rowlett, TX 75088', lat: 32.902, lng: -96.547, source: 'WEBSITE', status: 'LOST', score: 24, stormIndex: null, staffIndex: 2 },
  { firstName: 'Ximena', lastName: 'Torres', email: 'ximena.torres@example.com', phone: '(972) 555-1124', address: '1101 Sunset Rd, Wylie, TX 75098', lat: 33.008, lng: -96.538, source: 'STORM_EVENT', status: 'WON', score: 93, stormIndex: 0, staffIndex: 1 },
];

interface JobSeed {
  name: string;
  description: string;
  status: 'ACTIVE' | 'COMPLETED' | 'ON_HOLD' | 'CANCELLED';
  phase: 'INSPECTION' | 'ESTIMATE' | 'PRODUCTION' | 'INVOICING' | 'COMPLETED';
  address: string;
  lat: number;
  lng: number;
  budget: number;
  actualCost: number;
  estimatedCost: number;
  grossProfit: number;
  startDaysAgo: number;
  endDaysAhead: number | null;
  createdByIndex: number;
}

const JOB_SEEDS: JobSeed[] = [
  { name: 'Coleman — Full Roof Replacement', description: 'Hail-damaged architectural shingle tear-off and replacement.', status: 'ACTIVE', phase: 'PRODUCTION', address: '1420 W Hunt St, McKinney, TX 75069', lat: 33.1973, lng: -96.6399, budget: 28500, actualCost: 14200, estimatedCost: 17800, grossProfit: 10700, startDaysAgo: 12, endDaysAhead: 3, createdByIndex: 0 },
  { name: 'Price — Storm Damage Re-Roof', description: 'Tornado-lifted shingles, decking repair, and new ridge vent.', status: 'ACTIVE', phase: 'ESTIMATE', address: '5517 Sweetwater Dr, Frisco, TX 75034', lat: 33.1465, lng: -96.839, budget: 32400, actualCost: 4100, estimatedCost: 20500, grossProfit: 11900, startDaysAgo: 6, endDaysAhead: 18, createdByIndex: 1 },
  { name: 'Whitfield — Insurance Claim Roof', description: 'Hail claim approved; full replacement with upgraded impact-resistant shingles.', status: 'ACTIVE', phase: 'INVOICING', address: '3108 Windy Knoll Dr, Plano, TX 75025', lat: 33.07, lng: -96.748, budget: 36800, actualCost: 29400, estimatedCost: 26100, grossProfit: 10700, startDaysAgo: 21, endDaysAhead: 2, createdByIndex: 0 },
  { name: 'Marsh — Gutter and Roof Repair', description: 'Flash-flood gutter replacement plus localized shingle repair.', status: 'COMPLETED', phase: 'COMPLETED', address: '4421 Maple Springs Blvd, Dallas, TX 75219', lat: 32.809, lng: -96.811, budget: 8900, actualCost: 7250, estimatedCost: 6900, grossProfit: 1650, startDaysAgo: 34, endDaysAhead: null, createdByIndex: 2 },
  { name: 'Torres — Hail Resistant Upgrade', description: 'Class 4 shingle upgrade with new underlayment and flashing.', status: 'ACTIVE', phase: 'INSPECTION', address: '1101 Sunset Rd, Wylie, TX 75098', lat: 33.008, lng: -96.538, budget: 41200, actualCost: 2800, estimatedCost: 25700, grossProfit: 15500, startDaysAgo: 3, endDaysAhead: 26, createdByIndex: 1 },
  { name: 'Raman — Ridge Vent and Flashing', description: 'Storm-related ventilation and flashing remediation.', status: 'ON_HOLD', phase: 'ESTIMATE', address: '2204 Ridgeview Dr, Richardson, TX 75080', lat: 32.953, lng: -96.741, budget: 6400, actualCost: 0, estimatedCost: 4100, grossProfit: 2300, startDaysAgo: 9, endDaysAhead: 40, createdByIndex: 2 },
  { name: 'Haddad — Partial Roof Replacement', description: 'Two-slope replacement after wind uplift damage.', status: 'ACTIVE', phase: 'PRODUCTION', address: '3311 N O\'Connor Rd, Irving, TX 75062', lat: 32.835, lng: -96.965, budget: 19700, actualCost: 11800, estimatedCost: 12300, grossProfit: 7400, startDaysAgo: 15, endDaysAhead: 5, createdByIndex: 0 },
  { name: 'Grant — Full Replacement', description: 'Estimated and approved; production scheduled after material delivery.', status: 'ACTIVE', phase: 'ESTIMATE', address: '700 Heritage Pkwy, Prosper, TX 75078', lat: 33.226, lng: -96.793, budget: 27600, actualCost: 3500, estimatedCost: 17400, grossProfit: 10200, startDaysAgo: 4, endDaysAhead: 21, createdByIndex: 1 },
];

interface EstimateSeed {
  jobIndex: number;
  name: string;
  status: 'DRAFT' | 'SENT' | 'APPROVED' | 'REJECTED';
  createdByIndex: number;
  items: Array<{ description: string; quantity: number; unit: string; unitCost: number; unitPrice: number; category: string }>;
}

const ESTIMATE_SEEDS: EstimateSeed[] = [
  {
    jobIndex: 0, name: 'Coleman Roof Replacement — Signed', status: 'APPROVED', createdByIndex: 1,
    items: [
      { description: 'Architectural shingles — install (Class 3)', quantity: 28, unit: 'sq', unitCost: 95, unitPrice: 145, category: 'SHINGLES' },
      { description: 'Synthetic underlayment', quantity: 28, unit: 'sq', unitCost: 12, unitPrice: 22, category: 'UNDERLAYMENT' },
      { description: 'Tear-off and disposal', quantity: 28, unit: 'sq', unitCost: 30, unitPrice: 48, category: 'LABOR' },
      { description: 'Ridge vent — 4 ft sections', quantity: 24, unit: 'ea', unitCost: 14, unitPrice: 26, category: 'VENTILATION' },
    ],
  },
  {
    jobIndex: 1, name: 'Price Storm Damage Re-Roof', status: 'SENT', createdByIndex: 1,
    items: [
      { description: 'Impact-resistant shingles — install', quantity: 32, unit: 'sq', unitCost: 118, unitPrice: 178, category: 'SHINGLES' },
      { description: 'Roof decking replacement (OSB)', quantity: 12, unit: 'sheet', unitCost: 22, unitPrice: 38, category: 'DECKING' },
      { description: 'Storm flashing kit', quantity: 1, unit: 'ea', unitCost: 320, unitPrice: 580, category: 'FLASHING' },
    ],
  },
  {
    jobIndex: 2, name: 'Whitfield Insurance Claim Roof', status: 'APPROVED', createdByIndex: 0,
    items: [
      { description: 'Impact-resistant shingles — install', quantity: 36, unit: 'sq', unitCost: 120, unitPrice: 182, category: 'SHINGLES' },
      { description: 'Synthetic underlayment', quantity: 36, unit: 'sq', unitCost: 12, unitPrice: 24, category: 'UNDERLAYMENT' },
      { description: 'Drip edge — aluminum', quantity: 180, unit: 'lf', unitCost: 2.4, unitPrice: 4.6, category: 'FLASHING' },
      { description: 'Tear-off and disposal', quantity: 36, unit: 'sq', unitCost: 31, unitPrice: 50, category: 'LABOR' },
    ],
  },
  {
    jobIndex: 3, name: 'Marsh Gutter and Roof Repair', status: 'APPROVED', createdByIndex: 2,
    items: [
      { description: 'Seamless gutter — 6 in aluminum', quantity: 140, unit: 'lf', unitCost: 7.5, unitPrice: 13.5, category: 'GUTTER' },
      { description: 'Downspouts', quantity: 8, unit: 'ea', unitCost: 18, unitPrice: 34, category: 'GUTTER' },
      { description: 'Localized shingle repair', quantity: 4, unit: 'sq', unitCost: 95, unitPrice: 148, category: 'SHINGLES' },
    ],
  },
  {
    jobIndex: 4, name: 'Torres Hail Resistant Upgrade', status: 'DRAFT', createdByIndex: 1,
    items: [
      { description: 'Class 4 shingles — install', quantity: 42, unit: 'sq', unitCost: 125, unitPrice: 188, category: 'SHINGLES' },
      { description: 'Synthetic underlayment', quantity: 42, unit: 'sq', unitCost: 13, unitPrice: 25, category: 'UNDERLAYMENT' },
      { description: 'Ice and water shield', quantity: 8, unit: 'roll', unitCost: 78, unitPrice: 132, category: 'UNDERLAYMENT' },
      { description: 'Tear-off and disposal', quantity: 42, unit: 'sq', unitCost: 31, unitPrice: 51, category: 'LABOR' },
    ],
  },
  {
    jobIndex: 5, name: 'Raman Ridge Vent and Flashing', status: 'SENT', createdByIndex: 2,
    items: [
      { description: 'Ridge vent — 4 ft sections', quantity: 14, unit: 'ea', unitCost: 15, unitPrice: 29, category: 'VENTILATION' },
      { description: 'Step flashing', quantity: 60, unit: 'lf', unitCost: 4.2, unitPrice: 8.1, category: 'FLASHING' },
      { description: 'Carpentry labor', quantity: 12, unit: 'hr', unitCost: 58, unitPrice: 105, category: 'LABOR' },
    ],
  },
  {
    jobIndex: 6, name: 'Haddad Partial Roof Replacement', status: 'APPROVED', createdByIndex: 0,
    items: [
      { description: 'Architectural shingles — install', quantity: 19, unit: 'sq', unitCost: 96, unitPrice: 149, category: 'SHINGLES' },
      { description: 'Synthetic underlayment', quantity: 19, unit: 'sq', unitCost: 12, unitPrice: 23, category: 'UNDERLAYMENT' },
      { description: 'Tear-off and disposal', quantity: 19, unit: 'sq', unitCost: 30, unitPrice: 47, category: 'LABOR' },
    ],
  },
  {
    jobIndex: 7, name: 'Grant Full Replacement', status: 'SENT', createdByIndex: 1,
    items: [
      { description: 'Architectural shingles — install', quantity: 30, unit: 'sq', unitCost: 97, unitPrice: 148, category: 'SHINGLES' },
      { description: 'Synthetic underlayment', quantity: 30, unit: 'sq', unitCost: 12, unitPrice: 23, category: 'UNDERLAYMENT' },
      { description: 'Ridge vent — 4 ft sections', quantity: 20, unit: 'ea', unitCost: 14, unitPrice: 27, category: 'VENTILATION' },
    ],
  },
  {
    jobIndex: 0, name: 'Coleman — Change Order Estimate', status: 'APPROVED', createdByIndex: 1,
    items: [
      { description: 'Additional decking replacement', quantity: 6, unit: 'sheet', unitCost: 23, unitPrice: 40, category: 'DECKING' },
      { description: 'New gutter section', quantity: 40, unit: 'lf', unitCost: 8, unitPrice: 14, category: 'GUTTER' },
    ],
  },
  {
    jobIndex: 2, name: 'Whitfield — Change Order Estimate', status: 'REJECTED', createdByIndex: 0,
    items: [
      { description: 'Skylight replacement', quantity: 1, unit: 'ea', unitCost: 410, unitPrice: 780, category: 'OTHER' },
    ],
  },
];

interface CalendarSeed {
  title: string;
  description: string;
  eventType: 'INSPECTION' | 'PRODUCTION' | 'ESTIMATE' | 'MEETING' | 'OTHER';
  jobIndex: number | null;
  startsInHours: number;
  durationHours: number;
  location: string;
  lat: number;
  lng: number;
  createdByIndex: number;
  assigneeIndexes: number[];
}

const CALENDAR_SEEDS: CalendarSeed[] = [
  { title: 'Roof inspection — Coleman', description: 'Post-hail inspection and photo documentation.', eventType: 'INSPECTION', jobIndex: 0, startsInHours: 26, durationHours: 2, location: '1420 W Hunt St, McKinney, TX 75069', lat: 33.1973, lng: -96.6399, createdByIndex: 1, assigneeIndexes: [3] },
  { title: 'Crew dispatch — Coleman production', description: 'Tear-off and install crew on site at 7:00 AM.', eventType: 'PRODUCTION', jobIndex: 0, startsInHours: 2, durationHours: 8, location: '1420 W Hunt St, McKinney, TX 75069', lat: 33.1973, lng: -96.6399, createdByIndex: 2, assigneeIndexes: [3, 2] },
  { title: 'Estimate walkthrough — Price', description: 'Review scope with homeowner and finalize upgrade options.', eventType: 'ESTIMATE', jobIndex: 1, startsInHours: 52, durationHours: 1.5, location: '5517 Sweetwater Dr, Frisco, TX 75034', lat: 33.1465, lng: -96.839, createdByIndex: 1, assigneeIndexes: [1] },
  { title: 'Insurance adjuster meeting — Whitfield', description: 'Adjuster site visit before final invoice.', eventType: 'MEETING', jobIndex: 2, startsInHours: 74, durationHours: 2, location: '3108 Windy Knoll Dr, Plano, TX 75025', lat: 33.07, lng: -96.748, createdByIndex: 0, assigneeIndexes: [1, 2] },
  { title: 'Inspection — Torres', description: 'Full roof and attic inspection after wind event.', eventType: 'INSPECTION', jobIndex: 4, startsInHours: -20, durationHours: 1.5, location: '1101 Sunset Rd, Wylie, TX 75098', lat: 33.008, lng: -96.538, createdByIndex: 1, assigneeIndexes: [3] },
  { title: 'Material delivery — Haddad', description: 'Two pallets of shingles plus underlayment.', eventType: 'OTHER', jobIndex: 6, startsInHours: 30, durationHours: 1, location: '3311 N O\'Connor Rd, Irving, TX 75062', lat: 32.835, lng: -96.965, createdByIndex: 2, assigneeIndexes: [3] },
  { title: 'Storm canvassing — Frisco corridor', description: 'Door-knocking sweep for tornado-damaged properties.', eventType: 'OTHER', jobIndex: null, startsInHours: 8, durationHours: 5, location: 'Frisco, TX 75034', lat: 33.1507, lng: -96.8236, createdByIndex: 0, assigneeIndexes: [1, 0] },
  { title: 'Weekly pipeline review', description: 'Manager review of estimate pipeline and storm lead conversion.', eventType: 'MEETING', jobIndex: null, startsInHours: 120, durationHours: 1, location: 'RoofPro Office — McKinney', lat: 33.1973, lng: -96.6399, createdByIndex: 0, assigneeIndexes: [0, 1, 2] },
  { title: 'Production closeout — Marsh', description: 'Final punch list and homeowner sign-off.', eventType: 'OTHER', jobIndex: 3, startsInHours: -48, durationHours: 1, location: '4421 Maple Springs Blvd, Dallas, TX 75219', lat: 32.809, lng: -96.811, createdByIndex: 2, assigneeIndexes: [2] },
  { title: 'Estimate delivery — Grant', description: 'Present three-tier estimate and financing options.', eventType: 'ESTIMATE', jobIndex: 7, startsInHours: 96, durationHours: 1.5, location: '700 Heritage Pkwy, Prosper, TX 75078', lat: 33.226, lng: -96.793, createdByIndex: 1, assigneeIndexes: [1] },
  { title: 'Inspection — Raman', description: 'Ventilation and flashing assessment.', eventType: 'INSPECTION', jobIndex: 5, startsInHours: 200, durationHours: 1, location: '2204 Ridgeview Dr, Richardson, TX 75080', lat: 32.953, lng: -96.741, createdByIndex: 2, assigneeIndexes: [3] },
  { title: 'Tornado response planning', description: 'Coordinate emergency crews and material staging.', eventType: 'MEETING', jobIndex: null, startsInHours: 44, durationHours: 1, location: 'RoofPro Office — McKinney', lat: 33.1973, lng: -96.6399, createdByIndex: 0, assigneeIndexes: [0, 2] },
];

interface FeedSeed {
  jobIndex: number;
  userIndex: number | null;
  content: string;
  isSystem: boolean;
  data?: Record<string, unknown>;
  hoursAgo: number;
}

const FEED_SEEDS: FeedSeed[] = [
  { jobIndex: 0, userIndex: 3, content: 'Tear-off complete on the Coleman roof. Decking is in good shape — two sheets replaced on the north slope.', isSystem: false, hoursAgo: 5 },
  { jobIndex: 0, userIndex: 2, content: 'Crew is on schedule. Ridge vent install starts first thing tomorrow.', isSystem: false, hoursAgo: 3 },
  { jobIndex: 2, userIndex: 1, content: 'Insurance adjuster approved the Whitfield scope at 36 squares. Sending the updated estimate this afternoon.', isSystem: false, hoursAgo: 26 },
  { jobIndex: 1, userIndex: 1, content: 'Homeowner in Frisco wants impact-resistant shingles after the tornado damage. Scheduling the upgrade walkthrough.', isSystem: false, hoursAgo: 19 },
  { jobIndex: 4, userIndex: 3, content: 'Inspection photos uploaded for the Torres property — hail bruising on three slopes.', isSystem: false, hoursAgo: 10 },
  { jobIndex: 3, userIndex: 2, content: 'Marsh job complete. Homeowner signed off and left a 5-star review.', isSystem: false, hoursAgo: 40 },
  { jobIndex: 0, userIndex: null, content: 'Storm system detected: North Texas Hail Swath — McKinney (SEVERE). 1.75" hail reported near this job site.', isSystem: true, data: { type: 'STORM_ALERT', severity: 'SEVERE', eventIndex: 0 }, hoursAgo: 70 },
  { jobIndex: 1, userIndex: null, content: 'Storm system detected: Collin County Tornado Outbreak (EXTREME). Emergency response mode enabled for impacted properties.', isSystem: true, data: { type: 'STORM_ALERT', severity: 'EXTREME', eventIndex: 1 }, hoursAgo: 24 },
];

const PRICE_DB_SEEDS: Array<{ category: string; name: string; unit: string; defaultCost: number; defaultPrice: number; notes: string }> = [
  { category: 'SHINGLES', name: 'Architectural Shingles — Class 3', unit: 'sq', defaultCost: 95, defaultPrice: 145, notes: 'Standard impact-rated laminate shingle.' },
  { category: 'SHINGLES', name: 'Impact-Resistant Shingles — Class 4', unit: 'sq', defaultCost: 118, defaultPrice: 178, notes: 'Insurance discount eligible in Texas.' },
  { category: 'UNDERLAYMENT', name: 'Synthetic Underlayment', unit: 'sq', defaultCost: 12, defaultPrice: 23, notes: '30-year synthetic felt replacement.' },
  { category: 'UNDERLAYMENT', name: 'Ice and Water Shield', unit: 'roll', defaultCost: 78, defaultPrice: 132, notes: '65 ft roll; required at eaves and valleys.' },
  { category: 'LABOR', name: 'Tear-off and Disposal', unit: 'sq', defaultCost: 30, defaultPrice: 48, notes: 'Includes dumpster and landfill fees.' },
  { category: 'LABOR', name: 'Carpentry Labor', unit: 'hr', defaultCost: 58, defaultPrice: 105, notes: 'Decking and framing repair.' },
  { category: 'FLASHING', name: 'Step Flashing', unit: 'lf', defaultCost: 4.2, defaultPrice: 8.1, notes: 'Aluminum step flashing.' },
  { category: 'FLASHING', name: 'Drip Edge — Aluminum', unit: 'lf', defaultCost: 2.4, defaultPrice: 4.6, notes: '2x4 inch profile.' },
  { category: 'VENTILATION', name: 'Ridge Vent — 4 ft Section', unit: 'ea', defaultCost: 14, defaultPrice: 27, notes: 'Shingle-over ridge vent.' },
  { category: 'GUTTER', name: 'Seamless Gutter — 6 in Aluminum', unit: 'lf', defaultCost: 7.5, defaultPrice: 13.5, notes: 'On-site roll forming.' },
  { category: 'DECKING', name: 'Roof Decking — 7/16 OSB', unit: 'sheet', defaultCost: 22, defaultPrice: 38, notes: '4x8 sheet.' },
  { category: 'DISPOSAL', name: 'Dumpster — 20 yd', unit: 'ea', defaultCost: 385, defaultPrice: 585, notes: 'Includes delivery and pickup.' },
];

const SUBCONTRACTOR_SEEDS: Array<{ name: string; phone: string; email: string; companyName: string; licenseNumber: string; specialties: string[]; rating: number; notes: string; jobIndex: number; userIdIndex: number | null }> = [
  { name: 'Ray Delgado', phone: '(214) 555-2201', email: 'ray.delgado@summitcrews.com', companyName: 'Summit Roofing Crews', licenseNumber: 'TX-RC-448221', specialties: ['TEAR_OFF', 'INSTALL'], rating: 4.8, notes: 'Preferred tear-off and install crew.', jobIndex: 0, userIdIndex: 3 },
  { name: 'Monica Hart', phone: '(972) 555-2202', email: 'monica.hart@premiergutters.com', companyName: 'Premier Gutter Works', licenseNumber: 'TX-RC-771903', specialties: ['GUTTERS', 'FLASHING'], rating: 4.6, notes: 'Seamless gutter specialist.', jobIndex: 3, userIdIndex: 2 },
];

interface MaterialOrderSeed {
  jobIndex: number;
  supplierName: string;
  status: 'PENDING' | 'ORDERED' | 'DELIVERED' | 'BACKORDERED' | 'CANCELLED';
  trackingNumber: string | null;
  notes: string;
  orderedDaysAgo: number;
  deliveredDaysAgo: number | null;
  items: Array<{ name: string; quantity: number; unit: string; unitCost: number }>;
}

const MATERIAL_ORDER_SEEDS: MaterialOrderSeed[] = [
  {
    jobIndex: 0, supplierName: 'ABC Supply Co.', status: 'DELIVERED', trackingNumber: 'ABC-78412-TX', notes: 'Delivered to driveway; crew staged materials.',
    orderedDaysAgo: 8, deliveredDaysAgo: 7,
    items: [
      { name: 'Architectural Shingles — Class 3', quantity: 28, unit: 'sq', unitCost: 95 },
      { name: 'Synthetic Underlayment', quantity: 28, unit: 'sq', unitCost: 12 },
      { name: 'Ridge Vent — 4 ft Section', quantity: 24, unit: 'ea', unitCost: 14 },
    ],
  },
  {
    jobIndex: 6, supplierName: 'Beacon Building Products', status: 'ORDERED', trackingNumber: 'BCN-99135-TX', notes: 'Scheduled for Thursday morning delivery.',
    orderedDaysAgo: 2, deliveredDaysAgo: null,
    items: [
      { name: 'Architectural Shingles — Class 3', quantity: 19, unit: 'sq', unitCost: 96 },
      { name: 'Synthetic Underlayment', quantity: 19, unit: 'sq', unitCost: 12 },
      { name: 'Drip Edge — Aluminum', quantity: 120, unit: 'lf', unitCost: 2.4 },
    ],
  },
];

interface AiUsageSeed {
  userIndex: number;
  providerId: string;
  model: string;
  purpose: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  status: 'SUCCESS' | 'FALLBACK';
  fallback: boolean;
  hoursAgo: number;
}

const AI_USAGE_SEEDS: AiUsageSeed[] = [
  { userIndex: 0, providerId: 'groq', model: 'llama-3.3-70b-versatile', purpose: 'storm_brief', promptTokens: 1520, completionTokens: 540, latencyMs: 820, status: 'SUCCESS', fallback: false, hoursAgo: 6 },
  { userIndex: 1, providerId: 'google', model: 'gemini-2.0-flash', purpose: 'map_copilot', promptTokens: 640, completionTokens: 210, latencyMs: 610, status: 'FALLBACK', fallback: true, hoursAgo: 2 },
];

/**
 * Lead-index → job-index links: every demo job has a converted lead attached via
 * Lead.jobId (unique). Indices refer to LEAD_SEEDS / JOB_SEEDS.
 */
const LEAD_JOB_LINKS: Array<[leadIndex: number, jobIndex: number]> = [
  [2, 0], // Carmen Diaz → Coleman roof
  [3, 1], // Devon Price → Price re-roof
  [6, 2], // Grace Whitfield → Whitfield insurance claim
  [7, 3], // Hassan Khan → Marsh repair job
  [10, 4], // Keisha Grant → Torres hail upgrade
  [14, 5], // Olivia Marsh → Raman ventilation
  [15, 6], // Priya Raman → Haddad partial replacement
  [23, 7], // Ximena Torres → Grant full replacement
];

// ─────────────────────────────────────────────────────────────
// Cleanup — remove ONLY this company's dependent rows
// ─────────────────────────────────────────────────────────────

async function clearDemoCompanyData(companyId: string): Promise<void> {
  await prisma.materialOrderItem.deleteMany({ where: { order: { companyId } } });
  await prisma.materialOrder.deleteMany({ where: { companyId } });
  await prisma.subcontractorAssignment.deleteMany({ where: { job: { companyId } } });
  await prisma.subcontractor.deleteMany({ where: { companyId } });
  await prisma.changeOrder.deleteMany({ where: { companyId } });
  await prisma.estimateItem.deleteMany({ where: { estimate: { companyId } } });
  await prisma.estimate.deleteMany({ where: { companyId } });
  // Break self-referencing feed threads before deleting posts
  await prisma.feedPost.updateMany({
    where: { companyId, parentPostId: { not: null } },
    data: { parentPostId: null },
  });
  await prisma.feedMention.deleteMany({ where: { post: { companyId } } });
  await prisma.feedAttachment.deleteMany({ where: { companyId } });
  await prisma.feedPost.deleteMany({ where: { companyId } });
  await prisma.eventReminder.deleteMany({ where: { event: { companyId } } });
  await prisma.calendarEventAssignee.deleteMany({ where: { event: { companyId } } });
  await prisma.calendarEvent.deleteMany({ where: { companyId } });
  await prisma.document.deleteMany({ where: { companyId } });
  await prisma.notification.deleteMany({ where: { companyId } });
  await prisma.jobAssignee.deleteMany({ where: { job: { companyId } } });
  await prisma.lead.deleteMany({ where: { companyId } });
  await prisma.job.deleteMany({ where: { companyId } });
  await prisma.stormEvent.deleteMany({ where: { companyId } });
  await prisma.priceDatabaseEntry.deleteMany({ where: { companyId } });
  await prisma.integration.deleteMany({ where: { companyId } });
  await prisma.aIUsage.deleteMany({ where: { companyId } });
  await prisma.aISetting.deleteMany({ where: { companyId } });
  await prisma.user.deleteMany({ where: { companyId, email: { not: DEMO_EMAIL } } });
}

// ─────────────────────────────────────────────────────────────
// Seed
// ─────────────────────────────────────────────────────────────

export interface SeedDemoResult {
  companyId: string;
  users: number;
  leads: number;
  jobs: number;
  estimates: number;
  estimateItems: number;
  calendarEvents: number;
  feedPosts: number;
  stormEvents: number;
  priceDatabaseEntries: number;
  subcontractors: number;
  materialOrders: number;
  aiUsage: number;
  rescoredLeads: number;
}

export async function seedDemo(): Promise<SeedDemoResult> {
  const existingDemo = await prisma.user.findUnique({ where: { email: DEMO_EMAIL } });

  let companyId: string;
  if (existingDemo) {
    companyId = existingDemo.companyId;
    await prisma.company.update({
      where: { id: companyId },
      data: {
        name: DEMO_COMPANY_NAME,
        address: '2400 W University Dr, McKinney, TX 75071',
        phone: '(214) 555-0100',
        subscriptionTier: 'Professional',
        settings: {
          seeded: true,
          serviceArea: 'DFW Metroplex',
          onboardingCompleted: true,
        } as Prisma.InputJsonValue,
      },
    });
  } else {
    const company = await prisma.company.create({
      data: {
        name: DEMO_COMPANY_NAME,
        address: '2400 W University Dr, McKinney, TX 75071',
        phone: '(214) 555-0100',
        subscriptionTier: 'Professional',
        settings: {
          seeded: true,
          serviceArea: 'DFW Metroplex',
          onboardingCompleted: true,
        } as Prisma.InputJsonValue,
      },
    });
    companyId = company.id;
  }

  await clearDemoCompanyData(companyId);

  const passwordHash = await bcrypt.hash(DEMO_PASSWORD, 12);

  // ── Users ────────────────────────────────────────────────
  const demoUser: User = existingDemo
    ? await prisma.user.update({
        where: { id: existingDemo.id },
        data: {
          passwordHash,
          firstName: 'Jordan',
          lastName: 'Demo',
          role: 'OWNER',
          isActive: true,
          phone: '(214) 555-0100',
          avatarUrl: null,
        },
      })
    : await prisma.user.create({
        data: {
          companyId,
          email: DEMO_EMAIL,
          passwordHash,
          firstName: 'Jordan',
          lastName: 'Demo',
          role: 'OWNER',
          phone: '(214) 555-0100',
        },
      });

  const staff: User[] = [];
  for (const seed of STAFF) {
    const user = await prisma.user.create({
      data: {
        companyId,
        email: seed.email,
        passwordHash,
        firstName: seed.firstName,
        lastName: seed.lastName,
        role: seed.role,
        phone: seed.phone,
      },
    });
    staff.push(user);
  }
  const allUsers = [demoUser, ...staff];
  const userId = (index: number): string => allUsers[index]?.id ?? demoUser.id;

  // ── Storm events (manual, with polygon geometry) ─────────
  const stormEvents: StormEvent[] = [];
  for (const seed of STORM_SEEDS) {
    const polygon = approximateBufferPolygon(seed.center, seed.radiusKm, 72);
    const event = await prisma.stormEvent.create({
      data: {
        companyId,
        name: seed.name,
        description: seed.description,
        startDate: daysAgo(seed.startDaysAgo),
        endDate:
          seed.endInHours !== undefined
            ? new Date(Date.now() + seed.endInHours * 3_600_000)
            : daysAgo(seed.endDaysAgo),
        severity: seed.severity,
        eventType: seed.eventType,
        affectedArea: { areaDesc: seed.area } as Prisma.InputJsonValue,
        source: 'MANUAL',
        geometry: polygon as unknown as Prisma.InputJsonValue,
        metadata: {
          seeded: true,
          area: seed.area,
          headline: seed.headline,
          alertIds: [] as string[],
          alertCount: 0,
          centroid: seed.center,
          eventTypes: [seed.eventType],
        } as unknown as Prisma.InputJsonValue,
      },
    });
    stormEvents.push(event);
  }

  // ── Jobs ─────────────────────────────────────────────────
  const jobs: Job[] = [];
  for (const seed of JOB_SEEDS) {
    const job = await prisma.job.create({
      data: {
        companyId,
        name: seed.name,
        description: seed.description,
        status: seed.status,
        phase: seed.phase,
        propertyAddress: seed.address,
        propertyLatLng: { lat: seed.lat, lng: seed.lng } as Prisma.InputJsonValue,
        startDate: daysAgo(seed.startDaysAgo),
        endDate: seed.endDaysAhead === null ? daysAgo(seed.startDaysAgo + 7) : daysAgo(-seed.endDaysAhead),
        grossProfit: seed.grossProfit,
        budget: seed.budget,
        actualCost: seed.actualCost,
        estimatedCost: seed.estimatedCost,
        createdByUserId: userId(seed.createdByIndex),
      },
    });
    jobs.push(job);
  }

  // ── Leads ────────────────────────────────────────────────
  const leads: Lead[] = [];
  for (let index = 0; index < LEAD_SEEDS.length; index++) {
    const seed = LEAD_SEEDS[index];
    const jobLink = LEAD_JOB_LINKS.find(([leadIndex]) => leadIndex === index);
    const linkedJob = jobLink ? jobs[jobLink[1]] : undefined;
    const lead = await prisma.lead.create({
      data: {
        companyId,
        firstName: seed.firstName,
        lastName: seed.lastName,
        email: seed.email,
        phone: seed.phone,
        propertyAddress: seed.address,
        propertyLatLng: { lat: seed.lat, lng: seed.lng } as Prisma.InputJsonValue,
        source: seed.source,
        status: seed.status,
        score: seed.score,
        assignedToUserId: seed.staffIndex === null ? null : userId(seed.staffIndex + 1),
        stormEventId: seed.stormIndex === null ? null : stormEvents[seed.stormIndex]?.id ?? null,
        jobId: linkedJob?.id ?? null,
        notes: seed.stormIndex === null ? null : `Storm lead from ${stormEvents[seed.stormIndex].name}.`,
      },
    });
    leads.push(lead);
  }

  // ── Estimates + items ────────────────────────────────────
  const estimates: Estimate[] = [];
  let estimateItemCount = 0;
  for (const seed of ESTIMATE_SEEDS) {
    const job = jobs[seed.jobIndex];
    if (!job) continue;
    const totals = seed.items.reduce(
      (acc, item) => {
        acc.totalCost += item.quantity * item.unitCost;
        acc.totalPrice += item.quantity * item.unitPrice;
        return acc;
      },
      { totalCost: 0, totalPrice: 0 }
    );
    const estimate = await prisma.estimate.create({
      data: {
        companyId,
        jobId: job.id,
        name: seed.name,
        totalCost: round2(totals.totalCost),
        totalPrice: round2(totals.totalPrice),
        status: seed.status,
        sentAt: seed.status === 'DRAFT' ? null : daysAgo(4),
        approvedAt: seed.status === 'APPROVED' ? daysAgo(2) : null,
        notes: null,
        createdById: userId(seed.createdByIndex),
      },
    });
    for (let sortOrder = 0; sortOrder < seed.items.length; sortOrder++) {
      const item = seed.items[sortOrder];
      await prisma.estimateItem.create({
        data: {
          estimateId: estimate.id,
          description: item.description,
          quantity: item.quantity,
          unit: item.unit,
          unitCost: item.unitCost,
          unitPrice: item.unitPrice,
          category: item.category,
          sortOrder,
        },
      });
      estimateItemCount++;
    }
    estimates.push(estimate);
  }

  // ── Calendar events + assignees ──────────────────────────
  for (const seed of CALENDAR_SEEDS) {
    const job = seed.jobIndex === null ? undefined : jobs[seed.jobIndex];
    const event = await prisma.calendarEvent.create({
      data: {
        companyId,
        jobId: job?.id ?? null,
        title: seed.title,
        description: seed.description,
        eventType: seed.eventType,
        startTime: new Date(Date.now() + seed.startsInHours * 3_600_000),
        endTime: new Date(Date.now() + (seed.startsInHours + seed.durationHours) * 3_600_000),
        allDay: false,
        location: seed.location,
        propertyLatLng: { lat: seed.lat, lng: seed.lng } as Prisma.InputJsonValue,
        createdByUserId: userId(seed.createdByIndex),
      },
    });
    for (const assigneeIndex of seed.assigneeIndexes) {
      await prisma.calendarEventAssignee.create({
        data: { eventId: event.id, userId: userId(assigneeIndex) },
      });
    }
  }

  // ── Feed posts ───────────────────────────────────────────
  for (const seed of FEED_SEEDS) {
    const job = jobs[seed.jobIndex];
    if (!job) continue;
    const data = seed.data
      ? ({
          ...seed.data,
          stormEventId:
            typeof seed.data.eventIndex === 'number'
              ? stormEvents[seed.data.eventIndex]?.id ?? null
              : null,
        } as Prisma.InputJsonValue)
      : undefined;
    await prisma.feedPost.create({
      data: {
        companyId,
        jobId: job.id,
        userId: seed.userIndex === null ? null : userId(seed.userIndex),
        content: seed.content,
        isSystem: seed.isSystem,
        data,
        createdAt: new Date(Date.now() - seed.hoursAgo * 3_600_000),
      },
    });
  }

  // ── Price database ───────────────────────────────────────
  for (const seed of PRICE_DB_SEEDS) {
    await prisma.priceDatabaseEntry.create({
      data: {
        companyId,
        category: seed.category,
        name: seed.name,
        unit: seed.unit,
        defaultCost: seed.defaultCost,
        defaultPrice: seed.defaultPrice,
        notes: seed.notes,
      },
    });
  }

  // ── Subcontractors + assignments ─────────────────────────
  for (const seed of SUBCONTRACTOR_SEEDS) {
    const subcontractor = await prisma.subcontractor.create({
      data: {
        companyId,
        name: seed.name,
        phone: seed.phone,
        email: seed.email,
        companyName: seed.companyName,
        licenseNumber: seed.licenseNumber,
        insuranceInfo: {
          carrier: 'Texas Mutual',
          policy: `TX-${seed.licenseNumber}`,
          expiresAt: new Date(Date.now() + 240 * DAY_MS).toISOString(),
        } as Prisma.InputJsonValue,
        specialties: seed.specialties,
        rating: seed.rating,
        notes: seed.notes,
      },
    });
    const job = jobs[seed.jobIndex];
    if (job) {
      await prisma.subcontractorAssignment.create({
        data: {
          jobId: job.id,
          subcontractorId: subcontractor.id,
          userId: seed.userIdIndex === null ? null : userId(seed.userIdIndex),
          notes: 'Seeded demo assignment.',
        },
      });
    }
  }

  // ── Material orders + items ──────────────────────────────
  for (const seed of MATERIAL_ORDER_SEEDS) {
    const job = jobs[seed.jobIndex];
    if (!job) continue;
    const totalCost = round2(
      seed.items.reduce((sum, item) => sum + item.quantity * item.unitCost, 0)
    );
    const order = await prisma.materialOrder.create({
      data: {
        companyId,
        jobId: job.id,
        supplierName: seed.supplierName,
        status: seed.status,
        totalCost,
        orderedAt: daysAgo(seed.orderedDaysAgo),
        deliveredAt: seed.deliveredDaysAgo === null ? null : daysAgo(seed.deliveredDaysAgo),
        trackingNumber: seed.trackingNumber,
        notes: seed.notes,
        orderedByUserId: demoUser.id,
      },
    });
    for (const item of seed.items) {
      await prisma.materialOrderItem.create({
        data: {
          materialOrderId: order.id,
          name: item.name,
          quantity: item.quantity,
          unit: item.unit,
          unitCost: item.unitCost,
          totalCost: round2(item.quantity * item.unitCost),
        },
      });
    }
  }

  // ── AI usage ─────────────────────────────────────────────
  for (const seed of AI_USAGE_SEEDS) {
    await prisma.aIUsage.create({
      data: {
        companyId,
        userId: userId(seed.userIndex),
        providerId: seed.providerId,
        model: seed.model,
        purpose: seed.purpose,
        promptTokens: seed.promptTokens,
        completionTokens: seed.completionTokens,
        totalTokens: seed.promptTokens + seed.completionTokens,
        latencyMs: seed.latencyMs,
        status: seed.status,
        fallback: seed.fallback,
        createdAt: new Date(Date.now() - seed.hoursAgo * 3_600_000),
      },
    });
  }

  // ── Populate deterministic storm impact scores ───────────
  // Seeded storm events are local; skip live NOAA so the seed is fast/offline-safe.
  const rescore = await rescoreLeadsForCompany(companyId, { includeLiveAlerts: false });

  return {
    companyId,
    users: allUsers.length,
    leads: leads.length,
    jobs: jobs.length,
    estimates: estimates.length,
    estimateItems: estimateItemCount,
    calendarEvents: CALENDAR_SEEDS.length,
    feedPosts: FEED_SEEDS.length,
    stormEvents: stormEvents.length,
    priceDatabaseEntries: PRICE_DB_SEEDS.length,
    subcontractors: SUBCONTRACTOR_SEEDS.length,
    materialOrders: MATERIAL_ORDER_SEEDS.length,
    aiUsage: AI_USAGE_SEEDS.length,
    rescoredLeads: rescore.scored,
  };
}

// ─────────────────────────────────────────────────────────────
// CLI runner
// ─────────────────────────────────────────────────────────────

if (process.argv[1]?.endsWith('seedDemo.ts')) {
  seedDemo()
    .then((result) => {
      // eslint-disable-next-line no-console
      console.log('Demo seed complete:', JSON.stringify(result, null, 2));
      // Avoid backend/src/prisma.ts beforeExit loop; disconnect explicitly first.
      return prisma.$disconnect().then(() => process.exit(0));
    })
    .catch((error) => {
      // eslint-disable-next-line no-console
      console.error('Demo seed failed:', error);
      prisma.$disconnect().finally(() => process.exit(1));
    });
}
