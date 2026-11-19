/**
 * EstimatePdfService — generates professional estimate PDFs with pdfkit.
 * Used by backend/src/routes/estimates.routes.ts (POST /:id/pdf).
 */
import PDFDocument from 'pdfkit';
import fs from 'fs';
import path from 'path';

export interface PdfItem {
  description: string;
  quantity: number;
  unit: string;
  unitCost: number;
  unitPrice: number;
  category?: string | null;
}

export interface PdfEstimate {
  id: string;
  name: string;
  totalCost: number;
  totalPrice: number;
  status: string;
  notes?: string | null;
  sentAt?: string | null;
  approvedAt?: string | null;
  createdAt: string;
  job?: { id: string; name: string; propertyAddress: string; description?: string | null } | null;
  createdBy?: { firstName: string; lastName: string; email: string } | null;
}

export interface PdfCompany {
  id: string;
  name?: string | null;
  address?: string | null;
  phone?: string | null;
  logoUrl?: string | null;
}

function fmtCurrency(v: number): string {
  return `$${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function fmtQty(q: number | string): string {
  const n = Number(q);
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: 'Draft',
    SENT: 'Sent for Approval',
    APPROVED: 'Approved',
    REJECTED: 'Rejected',
  };
  return map[status] || status;
}

const STATUS_COLOR: Record<string, [number, number, number]> = {
  DRAFT: [245, 158, 11],
  SENT: [59, 130, 246],
  APPROVED: [16, 185, 129],
  REJECTED: [239, 68, 68],
};

/** Write a PDF for the given estimate to disk and return the absolute file path. */
export async function generateEstimatePdf(
  estimate: PdfEstimate,
  items: PdfItem[],
  company: PdfCompany,
  outputPath: string
): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const dir = path.dirname(outputPath);
    fs.mkdirSync(dir, { recursive: true });

    const doc = new PDFDocument({
      size: 'LETTER',
      margin: 50,
      info: {
        Title: `Estimate ${estimate.name}`,
        Author: company.name || 'RoofPro CRM',
        Subject: 'Roofing Estimate',
      },
    });

    const stream = fs.createWriteStream(outputPath);
    doc.pipe(stream);

    const pageWidth = doc.page.width;
    const left = 50;
    const right = pageWidth - 50;

    // Footer drawn on every page
    let _pageCount = 1;
    let _footerGuard = false;
    doc.on('pageAdded', () => {
      if (_footerGuard) return;
      _footerGuard = true;
      _pageCount++;
      doc
        .fontSize(8)
        .fillColor('#94a3b8')
        .text(
          `RoofPro CRM • ${company.name || 'Roofing Contractor'} • ${company.phone || ''}`,
          left,
          doc.page.height - 40,
          { align: 'left' }
        )
        .text(`Page ${_pageCount}`, right - 60, doc.page.height - 40, {
          align: 'right',
        });
      _footerGuard = false;
    });

    // ---- Header ----
    doc
      .fontSize(10)
      .fillColor('#94a3b8')
      .text(`Estimate #${estimate.id.slice(0, 8).toUpperCase()}`, left, doc.y);
    doc
      .fontSize(11)
      .fillColor('#94a3b8')
      .text(`Issued: ${new Date(estimate.createdAt).toLocaleDateString()}`, right - 130, doc.y, {
        align: 'right',
      });
    doc.moveDown(0.6);

    doc.fontSize(24).fillColor('#f8fafc').text(estimate.name || 'Estimate', left);

    // Status chip
    const status = statusLabel(estimate.status);
    const [r, g, b] = STATUS_COLOR[estimate.status] || [100, 116, 139];
    const statusX = right - 150;
    const statusY = doc.y;
    doc
      .rect(statusX, statusY - 2, 110, 14)
      .fillColor(`rgba(${r},${g},${b})`, 0.15)
      .strokeColor(`rgb(${r},${g},${b})`)
      .strokeOpacity(0.4)
      .fillOpacity(0.15);
    doc
      .rect(statusX, statusY - 2, 8, 14)
      .fillColor(`rgb(${r},${g},${b})`)
      .fillOpacity(1);
    doc.fontSize(9).fillColor('#cbd5e1').text(`  ${status}`, statusX + 12, statusY, {
      underline: false,
    });

    doc.moveDown(1);

    // ---- Bill to & job info ----
    let y = doc.y;
    doc.fontSize(9).fillColor('#94a3b8').text('BILL TO', left, y);
    doc.fontSize(10).fillColor('#cbd5e1').text(company.name || '—', left, y + 12);
    y += 26;
    if (company.address) { doc.text(company.address, left, y); y += 14; }
    if (company.phone) { doc.text(`Phone: ${company.phone}`, left, y); y += 14; }

    doc.fontSize(9).fillColor('#94a3b8').text('JOB', right - 160, doc.y - (company.name ? 26 : 0));
    doc.fontSize(10).fillColor('#cbd5e1').text(estimate.job?.name || '—', right - 160, doc.y + 2);
    doc.text(estimate.job?.propertyAddress || '—', right - 160, doc.y + 14);
    y = doc.y + 24;
    doc.y = Math.max(y, doc.y);

    // ---- Line items table ----
    doc.y = doc.y;
    const startX = left;
    const columns = [
      { x: startX, w: 190, label: 'Description', align: 'left' as const },
      { x: startX + 195, w: 40, label: 'Qty', align: 'right' as const },
      { x: startX + 240, w: 40, label: 'Unit', align: 'left' as const },
      { x: startX + 285, w: 60, label: 'Cost', align: 'right' as const },
      { x: startX + 350, w: 60, label: 'Price', align: 'right' as const },
      { x: startX + 415, w: 70, label: 'Total', align: 'right' as const },
    ];

    const headerH = 18;
    doc.rect(startX, doc.y, right - startX, headerH).fillColor('rgba(30,41,79,0.6)');
    doc.fontSize(8).fillColor('#94a3b8').font('Helvetica-Bold');
    columns.forEach((c) => {
      doc.text(c.label, c.x, doc.y + 6, { width: c.w, align: c.align });
    });
    doc.font('Helvetica');
    doc.y += headerH;

    const lineH = 15;
    for (const item of items) {
      const rowY = doc.y;
      if (rowY + lineH > 700) {
        doc.addPage();
      }
      doc.fontSize(8).fillColor('#cbd5e1');
      const desc = item.description || '';
      doc.text(desc, columns[0].x, rowY, { width: columns[0].w, align: 'left' });
      doc.text(fmtQty(item.quantity), columns[1].x, rowY, { width: columns[1].w, align: 'right' });
      doc.text(item.unit || '', columns[2].x, rowY, { width: columns[2].w, align: 'left' });
      doc.text(fmtCurrency(item.unitCost), columns[3].x, rowY, { width: columns[3].w, align: 'right' });
      doc.text(fmtCurrency(item.unitPrice), columns[4].x, rowY, { width: columns[4].w, align: 'right' });
      doc.text(fmtCurrency(item.unitPrice * item.quantity), columns[5].x, rowY, {
        width: columns[5].w,
        align: 'right',
      });
      doc.y = rowY + lineH;
    }

    // ---- Totals ----
    doc.addPage();
    doc.fontSize(10).fillColor('#94a3b8').text('ESTIMATE TOTALS', left, 50);

    const totalCost = Number(estimate.totalCost) || 0;
    const totalPrice = Number(estimate.totalPrice) || 0;
    const margin = totalPrice - totalCost;

    const summary: Array<[string, number]> = [
      ['Total Material/Labor Cost', totalCost],
      ['Total Price', totalPrice],
      ['Profit (Margin)', margin],
    ];
    let ty = doc.y + 16;
    summary.forEach(([label, value]) => {
      doc.fontSize(9).fillColor('#94a3b8').text(label, left, ty);
      doc.fontSize(9).fillColor('#f8fafc').text(fmtCurrency(value), right - 100, ty, { align: 'right' });
      ty += 16;
    });

    // ---- Notes ----
    if (estimate.notes) {
      doc.addPage();
      doc.fontSize(10).fillColor('#94a3b8').text('NOTES', left, 50);
      doc.fontSize(9).fillColor('#cbd5e1').text(estimate.notes, left, 66, {
        align: 'left',
        lineGap: 2,
      });
    }

    // ---- Prepared by ----
    const preparedY = doc.page.height - 80;
    const preparedBy = estimate.createdBy ? `${estimate.createdBy.firstName} ${estimate.createdBy.lastName}` : '—';
    doc
      .fontSize(8)
      .fillColor('#94a3b8')
      .text(`Prepared by: ${preparedBy}`, left, preparedY)
      .text(`Email: ${estimate.createdBy?.email || '—'}`, left, preparedY + 12);

    doc.end();

    stream.on('finish', () => resolve(outputPath));
    stream.on('error', (err) => reject(err));
  });
}
