import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { buildH3TerritoryGrid } from '../hexGrid';

describe('H3 geospatial hex territory grid', () => {
  it('builds contiguous 1-ring H3 hex polygons and tracks saturation metrics', () => {
    const fc = buildH3TerritoryGrid([
      { lat: 33.05, lng: -96.75, kind: 'storm', severity: 'EXTREME', score: 92, label: 'DFW Supercell' },
      { lat: 33.051, lng: -96.751, kind: 'lead', score: 88 },
      { lat: 33.0515, lng: -96.7512, kind: 'job' },
      { lat: 33.052, lng: -96.752, kind: 'space', label: 'North Texas Hail Desk' },
    ]);

    assert.equal(fc.type, 'FeatureCollection');
    assert.equal(fc.resolution, 7);
    assert.equal(fc.features.length, 7);
    assert.equal(fc.summary.activeStormHexes, 7);
    assert.equal(fc.summary.totalLeads, 1);
    assert.equal(fc.summary.workedJobs, 1);

    const centerFeature = fc.features.find((f) => f.properties.leadCount === 1);
    assert.ok(centerFeature);
    assert.equal(centerFeature.geometry.type, 'Polygon');
    assert.equal(centerFeature.properties.saturationPct, 100);
    assert.equal(centerFeature.properties.severity, 'EXTREME');
  });
});
