import { writeFileSync, readFileSync } from 'fs';

const API = 'http://localhost:5000/api/v1';
const credsPath = 'test-audit-creds.json';

// --- Register ---
const regRes = await fetch(`${API}/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    email: 'audit@test.com',
    password: 'password123',
    firstName: 'Audit',
    lastName: 'User',
    companyName: 'Audit Company',
    phone: '555-0100',
  }),
});
let reg;
try { reg = await regRes.json(); } catch { reg = { error: 'register failed', status: regRes.status }; }

if (!reg.accessToken) {
  // Try login instead
  const logRes = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'audit@test.com', password: 'password123' }),
  });
  reg = await logRes.json();
}

const token = reg.accessToken || reg.token;
const userId = reg.user?.id;
const companyId = reg.company?.id;

if (!token) {
  console.log('FAILED to get token:', JSON.stringify(reg, null, 2));
  process.exit(1);
}

console.log(`✅ Login OK: ${reg.user.email} (ID: ${userId})`);
writeFileSync(credsPath, JSON.stringify({ token, userId, companyId }));

const auth = { Authorization: `Bearer ${token}` };
const j = (k,v) => JSON.stringify(v, null, 2);

// --- Create a lead with propertyLatLng (should auto-calculate storm score) ---
const leadRes = await fetch(`${API}/leads`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    firstName: 'John',
    lastName: 'Doe',
    email: 'john@example.com',
    phone: '555-1234',
    propertyAddress: '123 Main St, Dallas, TX',
    propertyLatLng: { lat: 32.7767, lng: -96.7970 },
    source: 'STORM_EVENT',
  }),
});
const lead = await leadRes.json();
console.log(`✅ Lead created: ${lead.lead.firstName} ${lead.lead.lastName}, score=${lead.lead.score}, status=${lead.lead.status}`);
const leadId = lead.lead?.id;
const jobName = `Job for ${lead.lead.firstName}`;

// --- Create a job from the lead ---
const jobRes = await fetch(`${API}/jobs`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: jobName,
    propertyAddress: lead.lead?.propertyAddress,
    description: 'Roof inspection and repair',
    propertyLatLng: { lat: 32.7767, lng: -96.7970 },
  }),
});
const job = await jobRes.json();
console.log(`✅ Job created: ${job.job.name} (ID: ${job.job.id})`);
const jobId = job.job?.id;

// --- Create an estimate with line items ---
const estRes = await fetch(`${API}/estimates`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jobId: jobId,
    name: 'Roof Replacement Estimate',
    notes: 'Full replacement with architectural shingles',
    items: [
      { description: 'Architectural Shingles', quantity: 50, unit: 'bn', unitCost: 35, unitPrice: 55, category: 'SHINGLES' },
      { description: 'Underlayment', quantity: 200, unit: 'lf', unitCost: 0.5, unitPrice: 1.25, category: 'UNDERLAYMENT' },
      { description: 'Labor', quantity: 1, unit: 'ea', unitCost: 800, unitPrice: 1200, category: 'LABOR' },
    ],
  }),
});
const est = await estRes.json();
console.log(`✅ Estimate created: ${est.estimate.name}`);
console.log(`   totalCost=${est.estimate.totalCost}, totalPrice=${est.estimate.totalPrice}`);
// Verify totals are auto-calculated: 50*35 + 200*0.5 + 1*800 = 1750+100+800 = 2650 cost; 50*55+200*1.25+1*1200 = 2750+250+1200 = 4200 price
const expectedCost = 50*35 + 200*0.5 + 1*800;
const expectedPrice = 50*55 + 200*1.25 + 1*1200;
console.log(`   Expected: cost=${expectedCost}, price=${expectedPrice}`);
console.log(`   Match: ${est.estimate.totalCost === expectedCost && est.estimate.totalPrice === expectedPrice ? '✅' : '❌'}`);
const estimateId = est.estimate?.id;

// --- Send estimate ---
const sentRes = await fetch(`${API}/estimates/${estimateId}/send`, {
  method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
});
const sentEst = await sentRes.json();
console.log(`✅ Estimate sent: status=${sentEst.estimate.status}, sentAt=${sentEst.estimate.sentAt}`);

// --- Approve estimate ---
const apprRes = await fetch(`${API}/estimates/${estimateId}/approve`, {
  method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
});
const apprEst = await apprRes.json();
console.log(`✅ Estimate approved: status=${apprEst.estimate.status}, approvedAt=${apprEst.estimate.approvedAt}`);

// --- Generate PDF (the fix we need to verify) ---
const pdfRes = await fetch(`${API}/estimates/${estimateId}/pdf`, {
  method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
});
const pdf = await pdfRes.json();
const hasPdf = pdfRes.ok && pdf.pdfUrl;
console.log(`✅ PDF generation: ${hasPdf ? 'SUCCESS - ' + pdf.pdfUrl : 'FAILED - ' + JSON.stringify(pdf)}`);

// Verify PDF file exists
if (hasPdf) {
  const path = pdf.pdfUrl.replace('/uploads/', '');
  console.log(`   PDF file path: ${path}`);
}

// --- Create a calendar event with conflict detection ---
const eventRes = await fetch(`${API}/calendar`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    title: 'Roof Inspection',
    description: 'Initial inspection at property',
    eventType: 'INSPECTION',
    startTime: '2026-09-15T09:00:00Z',
    endTime: '2026-09-15T11:00:00Z',
    location: '123 Main St, Dallas, TX',
    jobId: jobId,
    assigneeUserIds: [userId],
  }),
});
const event = await eventRes.json();
console.log(`✅ Calendar event created: ${event.data.title}, conflicts=${event.conflicts?.length || 0}`);

// Create a conflicting event
const conflictRes = await fetch(`${API}/calendar`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    title: 'Overlapping Meeting',
    eventType: 'MEETING',
    startTime: '2026-09-15T10:00:00Z',
    endTime: '2026-09-15T12:00:00Z',
    location: 'Remote',
    jobId: jobId,
    assigneeUserIds: [userId],
  }),
});
const conflict = await conflictRes.json();
console.log(`✅ Conflict event created: conflicts=${conflict.conflicts?.length || 0} (expected >= 1)`);
if (conflict.conflicts?.length > 0) {
  console.log(`   Conflict with: ${conflict.conflicts[0].title}`);
}

// --- Feed post with @mentions ---
// First, create a user to mention
const userRes = await fetch(`${API}/auth/users`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    email: 'john@contractor.com',
    firstName: 'John',
    lastName: 'Doe',
    role: 'PROJECT_MANAGER',
  }),
});
const newUser = await userRes.json();
console.log(`✅ User created for mention: ${newUser.user?.firstName} ${newUser.user?.lastName} (ID: ${newUser.user?.id})`);

// Now create a feed post mentioning the user by IDs
const feedRes = await fetch(`${API}/feed/${jobId}/posts`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    content: 'Hey team, we need to schedule the inspection. @John Doe any availability this week?',
    mentionedUserIds: [newUser.user?.id, userId],
  }),
});
const feed = await feedRes.json();
console.log(`✅ Feed post created: ${feed.post?.content?.substring(0, 50)}...`);
console.log(`   mentions=${JSON.stringify(feed.post?.mentions?.map(m => ({id: m.userId, name: m.user?.firstName + ' ' + m.user?.lastName}))) || 'none'}`);
console.log(`   mentions parsed from content: ${feed.post?.mentions?.length || 0} (expected 2)`);

// --- Price database ---
const priceRes = await fetch(`${API}/materials/price-database`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    category: 'SHINGLES',
    name: 'GAF Timberline HDZ',
    unit: 'bn',
    defaultCost: 30,
    defaultPrice: 50,
  }),
});
const price = await priceRes.json();
console.log(`✅ Price database entry created: ${price.data?.name}, cost=${price.data?.defaultCost}, price=${price.data?.defaultPrice}`);

// --- Materials order ---
const matRes = await fetch(`${API}/materials`, {
  method: 'POST',
  headers: { ...auth, 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jobId: jobId,
    supplierName: 'ABC Supply',
    notes: 'Delivery by Thursday',
    items: [
      { name: 'GAF Timberline HDZ', quantity: 50, unit: 'bn', unitCost: 35 },
    ],
  }),
});
const mat = await matRes.json();
console.log(`✅ Material order created: ${mat.data?.supplierName}, totalCost=${mat.data?.totalCost}, items=${mat.data?.items?.length || 1}`);

// --- Reports ---
const reportRes = await fetch(`${API}/reports/pipeline`, {
  method: 'GET', headers: { ...auth },
});
const report = await reportRes.json();
console.log(`✅ Pipeline report: totalLeads=${report.summary?.totalLeads || 0}, conversionRate=${report.summary?.conversionRate || 0}%`);

const profRes = await fetch(`${API}/reports/profitability`, {
  method: 'GET', headers: { ...auth },
});
const prof = await profRes.json();
console.log(`✅ Profitability report: totalJobs=${prof.summary?.totalJobs || 0}, totalRevenue=${prof.summary?.totalBudget || 0}, avgMargin=${prof.summary?.avgMarginPercent || 0}%`);

console.log('\n=== All audit tests complete ===');
