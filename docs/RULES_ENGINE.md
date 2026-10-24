# Rules engine

> **Status:** planned (Phase 2). Not implemented.

The rules engine is the **only** component that decides match results. It is deterministic, testable and explainable. The LLM never changes a result; it only explains the result and the trace the engine produces.

## 1. Rule format

Each `rule` row (see [DATA_MODEL.md](DATA_MODEL.md#rule)) holds one condition in [json-logic](https://jsonlogic.com/) plus metadata:

```json
{
  "code": "age_min",
  "json_logic": { ">=": [ { "var": "age" }, 60 ] },
  "description_key": "rule.ignoaps.age_min",
  "is_hard": true,
  "confidence": "confirmed",
  "source_id": "…",
  "source_locator": "Guidelines 2014, para 2.1"
}
```

- **One condition per rule.** Don't combine age and income in one rule; separate rules give precise traces and precise follow-up questions.
- **Rules only reference profile fields** from the list in §2, through `{"var": "<field>"}`.
- **Allowed operators:** `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `and`, `or`, `!`, `if`, `missing`. No custom operators, so the same JSON runs identically in json-logic-js (on the device) and json-logic-java (in the Spring Boot API).
- `description_key` is an i18n key whose text explains the rule in plain words, e.g. *"Age 60 or older"*. It is shown in the trace and read aloud.
- Every rule cites the source clause that justifies it.

## 2. Profile fields

| Field | Type | Asked as |
|---|---|---|
| `age` | band → `{min, max}` | age band tiles |
| `state` | state code | map / list |
| `annual_income` | band → `{min, max}` (₹ per household per year) | income band tiles |
| `occupation` | enum | icon tiles |
| `social_category` | `general` / `obc` / `sc` / `st` / `ews` | tiles, with "prefer not to say" |
| `gender` | `female` / `male` / `transgender` / `undisclosed` | tiles |
| `disability` | bool; optional `disability_percent` band | yes / no |
| `is_student`, `education_level` | bool, enum | tiles |
| `is_bpl` | bool | yes / no / don't know |
| `land_holding` | band (hectares) | tiles (farmers only) |
| `household.*` | per-member fields | family mode |

"Don't know" and "prefer not to say" are stored as **missing**, never as `false`.

## 3. Three-state evaluation

### 3.1 Evaluating one rule

Each rule evaluates to `pass`, `fail` or `unknown`:

1. **Missing data:** if any variable the rule reads is missing from the profile → `unknown`, and those fields are recorded as `missing_fields`.
2. **Bands:** profile values like `age` and `annual_income` are ranges. The engine evaluates the rule at **every corner** of the bands it reads (the lower and upper bound of each, so 2ⁿ evaluations for n banded fields; in practice n ≤ 2):
   - all corners true → `pass`
   - all corners false → `fail`
   - mixed → `unknown`, and the engine asks for a narrower band for that field.
   This is correct because allowed rules are monotonic in each numeric field (they only use comparison operators on it). The reviewer UI rejects rules that aren't.
3. Otherwise evaluate normally → `pass` / `fail`.

### 3.2 Combining rules into a scheme result

```
if any hard rule == fail and its confidence != uncertain → NOT_ELIGIBLE
elif any hard rule == unknown
  or any hard rule == fail with confidence == uncertain  → NEED_MORE_INFO
else                                                     → LIKELY_ELIGIBLE
```

- **Soft rules** (`is_hard = false`) are priority or preference criteria (e.g. "priority to widows"). They never change the result; a passing soft rule adds a "may get priority" note and a failing one adds nothing.
- An **uncertain** hard rule that fails gives "need more info" with the note *"Check this with the office"*, not "not eligible". We don't rule people out on rules we aren't sure of.
- A scheme whose facts are stale (see [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md#5-re-check-job)) still gets a result, but the result carries `stale: true` and the UI shows the warning.

### 3.3 User-facing labels

| Result | Label (en) | Never say |
|---|---|---|
| `LIKELY_ELIGIBLE` | "You may be eligible" | "You are eligible", "You will get" |
| `NEED_MORE_INFO` | "We need a bit more information" | — |
| `NOT_ELIGIBLE` | "This scheme may not fit you, because: …" | "You are rejected" |

## 4. Rule trace

Every evaluation returns a trace that the UI, the TTS read-out and the RAG explainer all use:

```json
{
  "scheme": "ignoaps",
  "result": "NEED_MORE_INFO",
  "ruleset_version": "2026-10-01.3",
  "stale": false,
  "rules": [
    { "code": "age_min", "outcome": "pass",    "is_hard": true, "confidence": "confirmed",
      "description_key": "rule.ignoaps.age_min", "source_id": "…" },
    { "code": "bpl",     "outcome": "unknown", "is_hard": true, "confidence": "confirmed",
      "description_key": "rule.ignoaps.bpl", "missing_fields": ["is_bpl"], "source_id": "…" }
  ],
  "missing_fields": ["is_bpl"],
  "next_question": "is_bpl"
}
```

`next_question` drives the adaptive questionnaire. Across all schemes being matched, the engine picks the missing field that would resolve the most `unknown` rules, and asks that next.

## 5. Worked examples

> The thresholds below are **illustrative**, to show how the engine works. Real rules are encoded only from verified official sources by a reviewer.

### 5.1 Old-age pension (modelled on IGNOAPS)

Rules:

| code | json_logic | hard | confidence |
|---|---|---|---|
| `age_min` | `{">=": [{"var": "age"}, 60]}` | yes | confirmed |
| `bpl` | `{"==": [{"var": "is_bpl"}, true]}` | yes | confirmed |

**Profile A:** `age = 60–69`, `is_bpl` missing.
- `age_min`: corners 60 → true, 69 → true → `pass`.
- `bpl`: `is_bpl` missing → `unknown`.
- Result: **NEED_MORE_INFO**, next question `is_bpl`. The UI asks: *"Does your family have a BPL ration card?"* with Yes / No / Don't know tiles.

**Profile B:** `age = 55–64`, `is_bpl = true`.
- `age_min`: corners 55 → false, 64 → true → `unknown`; ask for a narrower age band.
- Result: **NEED_MORE_INFO**, next question `age` (narrower band).

**Profile C:** `age = 40–49`, `is_bpl = true`.
- `age_min`: both corners false → `fail` (confirmed, hard).
- Result: **NOT_ELIGIBLE**, reason *"Age 60 or older"*, with the source link.

### 5.2 Post-matric scholarship (modelled on the SC post-matric scheme)

| code | json_logic | hard | confidence |
|---|---|---|---|
| `category_sc` | `{"==": [{"var": "social_category"}, "sc"]}` | yes | confirmed |
| `post_matric` | `{"in": [{"var": "education_level"}, ["class_11_12", "diploma", "ug", "pg"]]}` | yes | confirmed |
| `income_max` | `{"<=": [{"var": "annual_income"}, 250000]}` | yes | confirmed |
| `hosteller_priority` | `{"==": [{"var": "is_hosteller"}, true]}` | no | guidance |

**Profile:** `social_category = sc`, `education_level = ug`, `annual_income = 1L–2.5L`, `is_hosteller` missing.
- `category_sc` → `pass`; `post_matric` → `pass`.
- `income_max`: corners 100000 → true, 250000 → true → `pass` (the band's upper bound equals the limit and the rule is `<=`).
- `hosteller_priority`: soft and missing → ignored for the result.
- Result: **LIKELY_ELIGIBLE**, with the label "You may be eligible" and the disclaimer.

If the band had been `2L–3L`, `income_max` would be `unknown` (200000 → true, 300000 → false), and the questionnaire would ask *"Is your family's yearly income up to ₹2.5 lakh?"*

## 6. Family mode

A household profile holds one record per member. The engine evaluates each scheme once per member (household-level fields such as `is_bpl`, `annual_income` and `state` are shared) and groups the results by member. Schemes that pay per household are shown once, under the household.

## 7. Stacking and conflicts

`scheme_relation` rows (`stacks_with` / `excludes`, each with a source) are applied after matching. If two `LIKELY_ELIGIBLE` schemes exclude each other, both are shown with a note *"You can usually get only one of these"* and a link to the source. The engine never picks one for the user.

## 8. Testing

- **Fixtures:** each scheme has `rules/<slug>/cases.yaml` with profiles and the expected result, per-rule outcomes and `missing_fields`.
- **Required edge cases** for every rule: just below, at and just above each numeric limit (e.g. ages 59/60/61; income 249999/250000/250001); a band that straddles the limit; every referenced field missing; "prefer not to say".
- **Parity:** CI runs the same fixtures through the Java engine (JUnit, json-logic-java) and the JS engine (json-logic-js) and fails if any outcome differs. The band-corner evaluation and three-state combination in §3 are implemented in both languages, so they are covered by the same fixtures.
- **Monotonicity check:** the reviewer UI and CI reject rules that use non-comparison operators on banded fields.
- **Publish gate:** a rule can't be published without fixtures that pass.

```yaml
# rules/ignoaps/cases.yaml
- name: exactly 60, BPL
  profile: { age: { min: 60, max: 60 }, is_bpl: true }
  expect: { result: LIKELY_ELIGIBLE }
- name: band straddles 60
  profile: { age: { min: 55, max: 64 }, is_bpl: true }
  expect: { result: NEED_MORE_INFO, missing_fields: [age] }
- name: BPL unknown
  profile: { age: { min: 70, max: 79 } }
  expect: { result: NEED_MORE_INFO, missing_fields: [is_bpl] }
```
