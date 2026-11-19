import tempfile
from pathlib import Path
from PIL import Image
from main import (
    _pixel_metrics,
    analyze_roof,
    AnalyzeRequest,
    estimate_roof,
    PhotogrammetryRequest,
    imu_jitter_score,
    ImuJitterRequest,
    privacy_redaction_audit,
    PrivacyAuditRequest,
)


def test_real_image_analysis():
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tf:
        p = Path(tf.name)
    img = Image.new("RGB", (64, 64), color=(120, 120, 120))
    img.putpixel((20, 20), (15, 15, 15))
    img.save(p)
    try:
        res = _pixel_metrics(p)
        assert res["width"] == 64
        assert res["height"] == 64
        assert res["fileBytes"] > 0
        photo_out = analyze_roof(AnalyzeRequest(fileName="hail_sample.png", notes="hail bruise"))
        assert photo_out["damageClass"] == "HAIL_IMPACT"
        odm = estimate_roof(PhotogrammetryRequest(fileUrls=["/uploads/a.jpg", "/uploads/b.jpg"]))
        assert odm["estimatedSquares"] > 20
        flat = imu_jitter_score(ImuJitterRequest(samples=[1.0, 1.0, 1.0, 1.0, 1.0]))
        assert flat["badgeState"] == "FLAGGED"
        human = imu_jitter_score(ImuJitterRequest(samples=[0.11, 0.19, 0.07, 0.24, 0.13]))
        assert human["badgeState"] == "VERIFIED"
        priv = privacy_redaction_audit(PrivacyAuditRequest(claimedRedacted=True))
        assert priv["exportAllowed"] is True
    finally:
        p.unlink(missing_ok=True)
