"""Deterministic 36-Month Revenue Scenario & Sensitivity Engine for AUCTUM / Vanguard.

Grounded in canonical codebase fee schedules:
- vanguard.b2b.bounty: 1,000 bps (10.00%) buyer-paid
- helios.local.b2c:    500 bps (5.00%) requester-paid
- atlas.roofing:       250 bps (2.50%) contractor-paid
- 120-day DePIN spatial half-life: $16.00 peak -> $2.50 floor, 65% protocol share

Outputs:
- docs/financial/auctum_36m_pnl_scenarios.csv
- docs/financial/auctum_sensitivity_matrix.csv
- docs/financial/auctum_36m_revenue_model.xlsx
"""

from __future__ import annotations

import csv
import math
from pathlib import Path
from typing import Dict, List

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

OUT_DIR = Path(__file__).resolve().parent

SCENARIOS = {
    "Conservative": {
        "contractor_start": 6,
        "contractor_growth": 0.055,
        "saas_churn": 0.055,
        "operator_start": 20,
        "operator_growth": 0.06,
        "b2c_per_op_mo": 9.0,
        "b2b_per_op_mo": 4.0,
        "storm_surge_qtr_boost": 1.15,
        "depin_reuse_cap": 2.2,
        "saas_arpu_usd": 249.0,
        "card_cost_rate": 0.029,
        "fixed_opex_base": 18000.0,
        "fixed_opex_step_per_org": 110.0,
    },
    "Base": {
        "contractor_start": 10,
        "contractor_growth": 0.085,
        "saas_churn": 0.035,
        "operator_start": 32,
        "operator_growth": 0.09,
        "b2c_per_op_mo": 12.0,
        "b2b_per_op_mo": 6.5,
        "storm_surge_qtr_boost": 1.35,
        "depin_reuse_cap": 4.2,
        "saas_arpu_usd": 299.0,
        "card_cost_rate": 0.018,  # blended ACH + card
        "fixed_opex_base": 22000.0,
        "fixed_opex_step_per_org": 115.0,
    },
    "Storm_Surge": {
        "contractor_start": 14,
        "contractor_growth": 0.115,
        "saas_churn": 0.025,
        "operator_start": 45,
        "operator_growth": 0.115,
        "b2c_per_op_mo": 14.0,
        "b2b_per_op_mo": 9.5,
        "storm_surge_qtr_boost": 1.75,
        "depin_reuse_cap": 6.5,
        "saas_arpu_usd": 349.0,
        "card_cost_rate": 0.015,
        "fixed_opex_base": 26000.0,
        "fixed_opex_step_per_org": 125.0,
    },
}

MODELS = [
    "Model_A_Escrow",
    "Model_B_SaaS",
    "Model_C_DePIN",
    "Blended_Flywheel",
]

# Canonical protocol parameters from registers/fees.csv & economics.ts
B2C_TICKET_USD = 30.0  # blended $18 grocery & $42 lawn
B2C_FEE_RATE = 0.05  # 500 bps
B2B_BOUNTY_USD = 95.0  # weighted V-01..V-07 inspection ticket
B2B_FEE_RATE = 0.10  # 1,000 bps
CONTRACTOR_THROUGHPUT_USD = 18500.0  # monthly Atlas invoice volume per active contractor
ATLAS_FEE_RATE = 0.025  # 250 bps
DEPIN_PROTOCOL_SHARE = 0.65  # 65% of half-life query price (vs 25% worker, 10% homeowner)


def avg_half_life_price(avg_age_days: float) -> float:
    return max(2.50, 16.00 * math.pow(0.5, avg_age_days / 120.0))


def simulate_model_36m(
    scenario_name: str,
    model_name: str,
    cfg: Dict[str, float],
    overrides: Dict[str, float] | None = None,
) -> List[Dict[str, object]]:
    p = {**cfg, **(overrides or {})}
    rows: List[Dict[str, object]] = []
    contractors = p["contractor_start"]
    operators = p["operator_start"]
    cum_optin_parcels = 0.0
    cum_cf = 0.0

    b2b_rate = p.get("b2b_fee_rate", B2B_FEE_RATE)
    storm_boost = p.get("storm_surge_qtr_boost", cfg["storm_surge_qtr_boost"])

    for m in range(1, 37):
        net_saas_growth = max(-0.02, p["contractor_growth"] - p["saas_churn"])
        contractors = contractors * (1.0 + net_saas_growth) if m > 1 else contractors
        operators = operators * (1.0 + p["operator_growth"] * (0.97 ** (m // 6))) if m > 1 else operators

        # Spring/Late-summer hail seasonality multiplier
        is_storm_month = (m % 12) in (3, 4, 5, 8, 9)
        season_mult = storm_boost if is_storm_month else 1.0

        b2c_tasks = operators * p["b2c_per_op_mo"]
        b2b_tasks = operators * p["b2b_per_op_mo"] * season_mult
        b2c_gmv = b2c_tasks * B2C_TICKET_USD
        b2b_gmv = b2b_tasks * B2B_BOUNTY_USD

        # Accumulate opt-in parcel stock (~55% opt-in rate)
        new_parcels = (b2c_tasks * 0.45 + b2b_tasks * 0.65)
        cum_optin_parcels += new_parcels
        active_grid_parcels = min(cum_optin_parcels, cum_optin_parcels * 0.72)

        # Ramp reuse multiplier as grid matures over 18 months
        grid_maturity = min(1.0, m / 18.0)
        reuse_per_mo = (p["depin_reuse_cap"] / 12.0) * (0.25 + 0.75 * grid_maturity)
        depin_queries = active_grid_parcels * reuse_per_mo * (1.15 if is_storm_month else 1.0)
        blended_query_price = avg_half_life_price(72.0)  # ~72d blended corpus age -> ~$10.55

        # Revenue streams
        rev_a = (b2c_gmv * B2C_FEE_RATE) + (b2b_gmv * b2b_rate) + (operators * 6.0 * 1.25)  # + instant cashout
        rev_b = (contractors * p["saas_arpu_usd"]) + (
            contractors * CONTRACTOR_THROUGHPUT_USD * 0.35 * ATLAS_FEE_RATE
        )
        rev_c = depin_queries * blended_query_price * DEPIN_PROTOCOL_SHARE

        if model_name == "Model_A_Escrow":
            gmv = b2c_gmv + b2b_gmv
            rev = rev_a
            cogs = gmv * p["card_cost_rate"] + (b2b_tasks + b2c_tasks) * 0.42 + operators * 6.0
            opex = p["fixed_opex_base"] * 0.85 + operators * 45.0
        elif model_name == "Model_B_SaaS":
            gmv = contractors * CONTRACTOR_THROUGHPUT_USD * 0.35
            rev = rev_b
            cogs = rev * 0.14 + contractors * 22.0
            opex = p["fixed_opex_base"] * 1.05 + contractors * p["fixed_opex_step_per_org"]
        elif model_name == "Model_C_DePIN":
            gmv = depin_queries * blended_query_price
            rev = rev_c
            cogs = rev * 0.12 + depin_queries * 0.08
            opex = p["fixed_opex_base"] * 1.10 + (18000.0 if m <= 12 else 9000.0)
        else:  # Blended_Flywheel: Stage 1 (A+B) -> Stage 2 Vault -> Stage 3 (DePIN)
            vault_lift = 1.0 + 0.35 * min(1.0, max(0.0, (m - 6) / 18.0))
            rev_a_eff = rev_a * vault_lift
            rev_c_eff = rev_c * min(1.0, max(0.15, (m - 4) / 14.0))
            gmv = (b2c_gmv + b2b_gmv) * vault_lift + contractors * CONTRACTOR_THROUGHPUT_USD * 0.35 + depin_queries * blended_query_price
            rev = rev_a_eff + rev_b + rev_c_eff
            payment_gmv = (b2c_gmv + b2b_gmv) * vault_lift
            cogs = (
                payment_gmv * p["card_cost_rate"]
                + (b2b_tasks + b2c_tasks) * 0.38
                + (rev_b + rev_c_eff) * 0.13
            )
            opex = p["fixed_opex_base"] * 1.15 + contractors * p["fixed_opex_step_per_org"] + operators * 35.0

        gross_profit = rev - cogs
        gm_pct = (gross_profit / rev * 100.0) if rev > 0 else 0.0
        ebitda = gross_profit - opex
        cum_cf += ebitda

        rows.append(
            {
                "scenario": scenario_name,
                "model": model_name,
                "month": m,
                "active_contractors": round(contractors, 1),
                "active_operators": round(operators, 1),
                "b2c_tasks": round(b2c_tasks),
                "b2b_bounties": round(b2b_tasks),
                "depin_queries": round(depin_queries),
                "gmv_usd": round(gmv, 2),
                "recognized_revenue_usd": round(rev, 2),
                "payment_infra_cogs_usd": round(cogs, 2),
                "gross_profit_usd": round(gross_profit, 2),
                "gross_margin_pct": round(gm_pct, 2),
                "fixed_opex_usd": round(opex, 2),
                "ebitda_usd": round(ebitda, 2),
                "cumulative_cashflow_usd": round(cum_cf, 2),
            }
        )
    return rows


def build_sensitivity_rows() -> List[Dict[str, object]]:
    base = SCENARIOS["Base"]
    cases = [
        ("Buyer_B2B_Fee_BPS", "500 bps (5%)", {"b2b_fee_rate": 0.05}),
        ("Buyer_B2B_Fee_BPS", "1000 bps (10% Canonical)", {"b2b_fee_rate": 0.10}),
        ("Buyer_B2B_Fee_BPS", "1500 bps (15% Premium)", {"b2b_fee_rate": 0.15}),
        ("Storm_Season_Surge", "1.00x (No Surge)", {"storm_surge_qtr_boost": 1.00}),
        ("Storm_Season_Surge", "1.35x (Base)", {"storm_surge_qtr_boost": 1.35}),
        ("Storm_Season_Surge", "1.85x (Active Hail Year)", {"storm_surge_qtr_boost": 1.85}),
        ("DePIN_Annual_Query_Reuse", "1.5x / parcel / yr", {"depin_reuse_cap": 1.5}),
        ("DePIN_Annual_Query_Reuse", "4.2x / parcel / yr (Base)", {"depin_reuse_cap": 4.2}),
        ("DePIN_Annual_Query_Reuse", "8.0x / parcel / yr (Dense Carrier API)", {"depin_reuse_cap": 8.0}),
        ("Payment_Rail_Cost", "0.80% (ACH / Prefunded Wallet)", {"card_cost_rate": 0.008}),
        ("Payment_Rail_Cost", "1.80% (Base Blend)", {"card_cost_rate": 0.018}),
        ("Payment_Rail_Cost", "2.90% (Full Card Processing)", {"card_cost_rate": 0.029}),
        ("SaaS_Monthly_Churn", "2.0% / mo", {"saas_churn": 0.02}),
        ("SaaS_Monthly_Churn", "3.5% / mo (Base)", {"saas_churn": 0.035}),
        ("SaaS_Monthly_Churn", "7.0% / mo (Seasonal Churn)", {"saas_churn": 0.07}),
    ]

    out: List[Dict[str, object]] = []
    for lever, label, ov in cases:
        for model_name in MODELS:
            sim = simulate_model_36m("Base", model_name, base, ov)
            total_rev_36m = sum(float(r["recognized_revenue_usd"]) for r in sim)
            total_ebitda_36m = sum(float(r["ebitda_usd"]) for r in sim)
            m36_rev = float(sim[-1]["recognized_revenue_usd"])
            m36_gm = float(sim[-1]["gross_margin_pct"])
            breakeven = next((int(r["month"]) for r in sim if float(r["ebitda_usd"]) > 0), None)
            out.append(
                {
                    "sensitivity_lever": lever,
                    "parameter_case": label,
                    "model": model_name,
                    "m36_monthly_arr_runrate_usd": round(m36_rev * 12.0, 2),
                    "total_36m_revenue_usd": round(total_rev_36m, 2),
                    "total_36m_ebitda_usd": round(total_ebitda_36m, 2),
                    "m36_gross_margin_pct": m36_gm,
                    "monthly_breakeven_month": breakeven if breakeven is not None else "Not_Within_36M",
                }
            )
    return out


def style_sheet_header(ws, headers: List[str]) -> None:
    fill = PatternFill(start_color="0E1116", end_color="0E1116", fill_type="solid")
    font = Font(color="E6ECF4", bold=True, size=10)
    ws.append(headers)
    for idx in range(1, len(headers) + 1):
        cell = ws.cell(row=1, column=idx)
        cell.fill = fill
        cell.font = font
        cell.alignment = Alignment(horizontal="left", vertical="center")
    ws.freeze_panes = "A2"


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    all_rows: List[Dict[str, object]] = []
    for scen_name, cfg in SCENARIOS.items():
        for model_name in MODELS:
            all_rows.extend(simulate_model_36m(scen_name, model_name, cfg))

    pnl_csv = OUT_DIR / "auctum_36m_pnl_scenarios.csv"
    with pnl_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(all_rows[0].keys()))
        writer.writeheader()
        writer.writerows(all_rows)

    sens_rows = build_sensitivity_rows()
    sens_csv = OUT_DIR / "auctum_sensitivity_matrix.csv"
    with sens_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(sens_rows[0].keys()))
        writer.writeheader()
        writer.writerows(sens_rows)

    # Summary table across Scenarios x Models
    summary_rows = []
    for scen_name in SCENARIOS:
        for model_name in MODELS:
            subset = [r for r in all_rows if r["scenario"] == scen_name and r["model"] == model_name]
            tot_rev = sum(float(r["recognized_revenue_usd"]) for r in subset)
            tot_gp = sum(float(r["gross_profit_usd"]) for r in subset)
            tot_ebitda = sum(float(r["ebitda_usd"]) for r in subset)
            be_m = next((int(r["month"]) for r in subset if float(r["ebitda_usd"]) > 0), None)
            min_cum_cf = min(float(r["cumulative_cashflow_usd"]) for r in subset)
            summary_rows.append(
                {
                    "Scenario": scen_name,
                    "Model": model_name,
                    "M12_Monthly_Rev_USD": subset[11]["recognized_revenue_usd"],
                    "M24_Monthly_Rev_USD": subset[23]["recognized_revenue_usd"],
                    "M36_ARR_RunRate_USD": round(float(subset[35]["recognized_revenue_usd"]) * 12.0, 2),
                    "Total_36M_Rev_USD": round(tot_rev, 2),
                    "Blended_36M_GM_Pct": round((tot_gp / tot_rev * 100.0) if tot_rev else 0.0, 2),
                    "Total_36M_EBITDA_USD": round(tot_ebitda, 2),
                    "Peak_Cash_Draw_USD": round(min(0.0, min_cum_cf), 2),
                    "Monthly_Breakeven_Month": be_m if be_m is not None else "Not_Within_36M",
                }
            )

    # Export Multi-Sheet Excel Workbook
    wb = openpyxl.Workbook()
    ws_sum = wb.active
    ws_sum.title = "Scenario_Summary"
    style_sheet_header(ws_sum, list(summary_rows[0].keys()))
    for r in summary_rows:
        ws_sum.append(list(r.values()))

    ws_assump = wb.create_sheet("Protocol_Parameters")
    style_sheet_header(ws_assump, ["Parameter", "Value", "Source / Basis"])
    params_table = [
        ("Worker Floor Deduction", "0 bps (100% bounty kept)", "packages/trust-engine/src/economics.ts (Invariant I-1)"),
        ("B2B Verification Escrow", "1,000 bps (10.00%)", "registers/fees.csv -> vanguard.b2b.bounty"),
        ("Community Escrow Rate", "500 bps (5.00%)", "registers/fees.csv -> helios.local.b2c"),
        ("Atlas Contractor Throughput Fee", "250 bps (2.50%)", "registers/fees.csv -> atlas.roofing"),
        ("IRS H2 2026 Mileage Cost", "$0.760 / mile", "packages/trust-engine/src/economics.ts"),
        ("DePIN Peak / Floor Query Price", "$16.00 Day-0 -> $2.50 Floor", "economics.ts -> computeEconomicHalfLife"),
        ("DePIN Spatial Half-Life", "120 days (1.85x refresh >=90d)", "economics.ts -> computeEconomicHalfLife"),
        ("DePIN Query Revenue Split", "65% Protocol / 25% Worker / 10% Owner", "economics.ts -> computeEconomicHalfLife"),
    ]
    for row in params_table:
        ws_assump.append(list(row))

    ws_pnl = wb.create_sheet("Monthly_PnL_36M")
    style_sheet_header(ws_pnl, list(all_rows[0].keys()))
    for r in all_rows:
        ws_pnl.append(list(r.values()))

    ws_sens = wb.create_sheet("Sensitivity_Matrix")
    style_sheet_header(ws_sens, list(sens_rows[0].keys()))
    for r in sens_rows:
        ws_sens.append(list(r.values()))

    for ws in wb.worksheets:
        for col_idx in range(1, ws.max_column + 1):
            col_letter = get_column_letter(col_idx)
            max_len = max((len(str(cell.value or "")) for cell in ws[col_letter]), default=12)
            ws.column_dimensions[col_letter].width = min(42, max(14, max_len + 3))

    xlsx_path = OUT_DIR / "auctum_36m_revenue_model.xlsx"
    wb.save(xlsx_path)
    print(f"Generated: {pnl_csv.name} ({len(all_rows)} rows), {sens_csv.name} ({len(sens_rows)} rows), {xlsx_path.name}")
    for s in summary_rows:
        print(
            f"{s['Scenario']:12s} | {s['Model']:16s} | M36 ARR=${s['M36_ARR_RunRate_USD']:10,.0f} | "
            f"36M Rev=${s['Total_36M_Rev_USD']:10,.0f} | GM={s['Blended_36M_GM_Pct']:5.1f}% | "
            f"EBITDA=${s['Total_36M_EBITDA_USD']:10,.0f} | Draw=${s['Peak_Cash_Draw_USD']:9,.0f} | BE=M{s['Monthly_Breakeven_Month']}"
        )


if __name__ == "__main__":
    main()
