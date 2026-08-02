#!/usr/bin/env bash
# 두 회차를 나란히 놓고 비교표를 만든다. 개선 전후를 판정하는 도구다.
#
# 사용:
#   ./scenarios/compare.sh run-1-baseline run-2-region-index
#   ./scenarios/compare.sh run-1-baseline run-2-region-index > results/run-2-region-index/compare.md
#
# 비교가 성립하려면 두 회차의 계단 구성(건수)이 같아야 한다. 다르면 경고하고 겹치는 계단만 쓴다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

BEFORE="${1:?사용법: ./scenarios/compare.sh <이전 회차> <이후 회차>}"
AFTER="${2:?비교할 회차 두 개를 지정할 것}"

for r in "$BEFORE" "$AFTER"; do
  [ -d "$PERF_DIR/results/$r" ] || { echo "✗ 회차 폴더 없음: results/$r" >&2; exit 1; }
done

python3 - "$PERF_DIR/results/$BEFORE" "$PERF_DIR/results/$AFTER" "$BEFORE" "$AFTER" <<'PY'
import json, sys, glob, os

bdir, adir, bname, aname = sys.argv[1:5]

def load(d):
    out = {}
    for f in glob.glob(os.path.join(d, "step-*.json")):
        try: j = json.load(open(f))
        except Exception: continue
        out[str(j["step"])] = j
    return out

B, A = load(bdir), load(adir)
steps = sorted(set(B) & set(A), key=lambda x: (len(x), x))
if not steps:
    print("겹치는 계단이 없다. 두 회차의 계단 번호가 같아야 비교가 성립한다.")
    raise SystemExit(1)

only_b, only_a = sorted(set(B) - set(A)), sorted(set(A) - set(B))
if only_b or only_a:
    print(f"> ⚠️ 한쪽에만 있는 계단은 제외했다 — {bname}: {only_b or '없음'} / {aname}: {only_a or '없음'}\n")

# 계단별 데이터량이 다르면 비교 자체가 성립하지 않는다.
mismatch = [s for s in steps if B[s]["data"]["products_total"] != A[s]["data"]["products_total"]]
if mismatch:
    print(f"> ⚠️ 계단 {', '.join(mismatch)} 의 상품 건수가 다르다. 같은 조건이 아니므로 해석에 주의할 것.\n")

label = {"list_first":"목록 진입","list_region":"동네 필터","list_deep":"스크롤 깊게",
         "detail_hot":"인기 상품 상세","comments":"상세 댓글",
         "admin_products":"관리자 상품","admin_members":"관리자 회원",
         "admin_dashboard":"관리자 대시보드","search_common":"검색(흔함)","search_rare":"검색(0건)"}

print(f"# 비교 — {bname} → {aname}\n")
print(f"| 계단 | 상품 | DB (MiB) {bname} → {aname} | 히트율 (%) |")
print("|---|---|---|---|")
for s in steps:
    b, a = B[s], A[s]
    print(f'| {s} | {b["data"]["products_total"]:,} | {b["data"]["db_mib"]} → {a["data"]["db_mib"]} '
          f'| {b["db"]["buffer_hit_rate_pct"]:.3f} → {a["db"]["buffer_hit_rate_pct"]:.3f} |')

names = [k for k in label if any(k in B[s]["endpoints"] for s in steps)]
for k in names:
    if not any(k in A[s]["endpoints"] for s in steps): continue
    print(f'\n## {label[k]}\n')
    print("| 계단 | 상품 | 이전 | 이후 | 변화 |")
    print("|---|---|---|---|---|")
    for s in steps:
        eb, ea = B[s]["endpoints"].get(k), A[s]["endpoints"].get(k)
        if not eb or not ea: continue
        r = ea["median_ms"] / eb["median_ms"] if eb["median_ms"] else 0
        mark = "개선" if r < 0.9 else ("악화" if r > 1.1 else "동일")
        print(f'| {s} | {B[s]["data"]["products_total"]:,} | {eb["median_ms"]} ms '
              f'| {ea["median_ms"]} ms | **{r:.2f}배** ({mark}) |')

print("\n> 값은 워밍업 1회를 버린 뒤 측정한 중앙값이다. 이 호스트의 절대 수치는 다른 환경과")
print("> 비교할 수 없다 — 같은 조건에서 잰 두 회차의 **상대 변화**만 의미가 있다.")
PY
