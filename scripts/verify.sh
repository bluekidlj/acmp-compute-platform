#!/usr/bin/env bash
# ACMP-Compute 1.0 全链路验证脚本（Happy Path）
#
# 前提：
#   - kind 集群已启动（kind create cluster --config scripts/kind-cluster.yaml）
#   - 已运行：bash scripts/seed-labels.sh && bash scripts/seed-hami-annotations.sh && bash scripts/install-nvidia-plugin.sh
#   - acmp-compute 已启动（mvn spring-boot:run）监听 8080
#   - 默认 admin/admin123 已初始化
#
# 用法：bash scripts/verify.sh
#
# 退出码：0 = 全部通过，非 0 = 中间某步失败

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
KUBECTL="kubectl"

# ─────────────── 颜色输出 ───────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASSED=0
FAILED=0
RESULTS=()

assert_eq() {
  local name="$1"
  local actual="$2"
  local expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo -e "${GREEN}✓${NC} $name  (got: $actual)"
    PASSED=$((PASSED + 1))
    RESULTS+=("PASS  $name")
  else
    echo -e "${RED}✗${NC} $name  (expected: $expected, got: $actual)"
    FAILED=$((FAILED + 1))
    RESULTS+=("FAIL  $name  (expected: $expected, got: $actual)")
  fi
}

assert_contains() {
  local name="$1"
  local haystack="$2"
  local needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    echo -e "${GREEN}✓${NC} $name  (found: $needle)"
    PASSED=$((PASSED + 1))
    RESULTS+=("PASS  $name")
  else
    echo -e "${RED}✗${NC} $name  (needle not found: $needle)"
    FAILED=$((FAILED + 1))
    RESULTS+=("FAIL  $name  (needle not found: $needle)")
  fi
}

extract_json() {
  local json="$1"
  local path="$2"
  python3 -c "import json,sys; d=json.loads('''$json'''); 
parts='$path'.split('.');
v=d
for p in parts:
  if p.isdigit(): v=v[int(p)]
  else: v=v[p]
print(v)" 2>/dev/null || echo ""
}

curl_json() {
  curl -s -X "$1" "$BASE_URL$2" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "${3:-}"
}

# ─────────────── 步骤 0：登录 ───────────────
echo ""
echo "═══ 0. 登录 ═══"
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('token',''))")
if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}✗ 登录失败${NC}"
  echo "  Response: $LOGIN_RESP"
  exit 1
fi
echo -e "${GREEN}✓ 登录成功${NC}"
PASSED=$((PASSED + 1))

# ─────────────── 步骤 1：检查预置规格 ───────────────
echo ""
echo "═══ 1. 检查 7 条预置规格 ═══"
SPECS_RESP=$(curl_json GET /api/v1/specs)
SPEC_COUNT=$(echo "$SPECS_RESP" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
assert_eq "预置规格数量" "$SPEC_COUNT" "7"

# ─────────────── 步骤 2：注册集群 ───────────────
echo ""
echo "═══ 2. 注册物理集群（用 kind kubeconfig） ═══"
KUBECONFIG_CONTENT=$($KUBECTL config view --raw -o json | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)))")
REGISTER_RESP=$(curl_json POST /api/v1/clusters "{
  \"name\": \"kind-acmp-test\",
  \"kubeconfigBase64\": $(echo "$KUBECONFIG_CONTENT" | python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))"),
  \"gpuTypes\": \"NVIDIA\",
  \"location\": \"kind\"
}")
CLUSTER_ID=$(echo "$REGISTER_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
if [[ -z "$CLUSTER_ID" ]]; then
  echo -e "${RED}✗ 注册集群失败${NC}"
  echo "  Response: $REGISTER_RESP"
  exit 1
fi
echo -e "${GREEN}✓ 集群注册成功${NC}: $CLUSTER_ID"
PASSED=$((PASSED + 1))

# ─────────────── 步骤 3：扫描集群 ───────────────
echo ""
echo "═══ 3. 扫描集群（回写 gpuTypes / hamiSplits） ═══"
SCAN_RESP=$(curl_json POST "/api/v1/clusters/$CLUSTER_ID/scan" "")
SCAN_OK=$(echo "$SCAN_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print('ok' if d.get('nodeCount',0)>0 else 'fail')" 2>/dev/null)
assert_eq "扫描返回 nodeCount > 0" "$SCAN_OK" "ok"

# ─────────────── 步骤 4：查显卡 ───────────────
echo ""
echo "═══ 4. 查集群显卡 ═══"
GPUS_RESP=$(curl_json GET "/api/v1/clusters/$CLUSTER_ID/gpus" "")
GPU_HAS_A100=$(echo "$GPUS_RESP" | grep -c "NVIDIA-A100" || true)
assert_contains "显卡列表含 A100" "$GPUS_RESP" "NVIDIA-A100"

# ─────────────── 步骤 5：查切分规格 ───────────────
echo ""
echo "═══ 5. 查 vGPU 切分规格 ═══"
SPLITS_RESP=$(curl_json GET "/api/v1/clusters/$CLUSTER_ID/gpu-splits" "")
SPLIT_HAS=$(echo "$SPLITS_RESP" | grep -c "nvidia-7b" || true)
assert_contains "vGPU 切分列表含 nvidia-7b" "$SPLITS_RESP" "nvidia-7b"

# ─────────────── 步骤 6：创建工作空间 ───────────────
echo ""
echo "═══ 6. 创建工作空间（自动建 3 类池） ═══"
WS_RESP=$(curl_json POST /api/v1/workspaces "{
  \"name\": \"ai-rd\",
  \"description\": \"AI 算法部\",
  \"clusterId\": \"$CLUSTER_ID\",
  \"maxPods\": 50
}")
WS_ID=$(echo "$WS_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
WS_NS=$(echo "$WS_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('namespace',''))")
POOL_COUNT=$(echo "$WS_RESP" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('pools',[])))" 2>/dev/null || echo "0")
assert_eq "WS 自动建 3 类池" "$POOL_COUNT" "3"

# 校验 K8s 上 NS 存在
NS_EXISTS=$($KUBECTL get namespace "$WS_NS" -o name 2>/dev/null | grep -c "^namespace/" || echo "0")
assert_eq "K8s Namespace 真实存在" "$NS_EXISTS" "1"

# ─────────────── 步骤 7：取池 ID 与规格 ID ───────────────
echo ""
echo "═══ 7. 查池与规格 ID ═══"
WS_DETAIL=$(curl_json GET "/api/v1/workspaces/$WS_ID" "")
POOL_EXCLUSIVE=$(echo "$WS_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((p['id'] for p in d['pools'] if p['poolType']=='EXCLUSIVE'), ''))")
POOL_SHARED=$(echo "$WS_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((p['id'] for p in d['pools'] if p['poolType']=='SHARED'), ''))")
POOL_OVERSELL=$(echo "$WS_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((p['id'] for p in d['pools'] if p['poolType']=='OVERSELL'), ''))")
echo "  EXCLUSIVE=$POOL_EXCLUSIVE  SHARED=$POOL_SHARED  OVERSELL=$POOL_OVERSELL"

SPEC_EXCL=$(curl_json GET "/api/v1/specs?poolType=EXCLUSIVE" "" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
SPEC_SHARED=$(curl_json GET "/api/v1/specs?poolType=SHARED" "" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((s['id'] for s in d if s['name']=='shared-hami-a100-1/4'), ''))")
SPEC_OVERSELL=$(curl_json GET "/api/v1/specs?poolType=OVERSELL" "" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
echo "  SPEC EXCL=$SPEC_EXCL  SHARED=$SPEC_SHARED  OVERSELL=$SPEC_OVERSELL"

# ─────────────── 步骤 8：修改池容量 + 关联规格 ───────────────
echo ""
echo "═══ 8. PATCH SHARED 池：totalNodes=10, specs=[shared-hami-a100-1/4] ═══"
PATCH_RESP=$(curl_json PATCH "/api/v1/pools/$POOL_SHARED" "{
  \"totalNodes\": 10,
  \"specs\": [\"$SPEC_SHARED\"]
}")
PATCH_OK=$(echo "$PATCH_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print('ok' if d.get('totalNodes')==10 else 'fail')" 2>/dev/null)
assert_eq "SHARED 池容量 = 10" "$PATCH_OK" "ok"

# 校验 K8s ResourceQuota
QUOTA_COUNT=$($KUBECTL get resourcequota -n "$WS_NS" --no-headers 2>/dev/null | wc -l | tr -d ' ')
assert_eq "K8s ResourceQuota 数量 = 1（不重复）" "$QUOTA_COUNT" "1"

# ─────────────── 步骤 9：创建项目 ───────────────
echo ""
echo "═══ 9. 创建项目 ═══"
PROJ_RESP=$(curl_json POST "/api/v1/workspaces/$WS_ID/projects" '{
  "name": "llm-team",
  "description": "LLM 算法组"
}')
PROJ_ID=$(echo "$PROJ_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
if [[ -z "$PROJ_ID" ]]; then
  echo -e "${RED}✗ 创建项目失败${NC}"
  echo "  Response: $PROJ_RESP"
  exit 1
fi
echo -e "${GREEN}✓ 项目创建成功${NC}: $PROJ_ID"
PASSED=$((PASSED + 1))

# ─────────────── 步骤 10：分配项目配额 ───────────────
echo ""
echo "═══ 10. 分配项目配额：SHARED 池 shared-hami-a100-1/4 给 5 节点 ═══"
QUOTA_RESP=$(curl_json POST "/api/v1/projects/$PROJ_ID/quotas" "{
  \"poolId\": \"$POOL_SHARED\",
  \"specId\": \"$SPEC_SHARED\",
  \"totalNodes\": 5
}")
QUOTA_OK=$(echo "$QUOTA_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print('ok' if d.get('totalNodes')==5 else 'fail')" 2>/dev/null)
assert_eq "配额 totalNodes = 5" "$QUOTA_OK" "ok"

# ─────────────── 步骤 11：部署推理服务 ═══
echo ""
echo "═══ 11. 部署推理服务（spec=shared-hami-a100-1/4） ═══"
DEP_RESP=$(curl_json POST "/api/v1/projects/$PROJ_ID/deployments" '{
  "name": "qwen3-svc",
  "specName": "shared-hami-a100-1/4",
  "replicas": 1,
  "image": "vllm/vllm-openai:latest",
  "envVars": { "MODEL_NAME": "Qwen3-14B" },
  "command": "python",
  "args": "-m vllm.entrypoints.openai.api_server --model /models/Qwen3",
  "modelSource": "with_weights",
  "modelIdOrPath": "/models",
  "modelName": "Qwen3-14B"
}')
DEP_ID=$(echo "$DEP_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
DEP_STATUS=$(echo "$DEP_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
if [[ -z "$DEP_ID" ]]; then
  echo -e "${RED}✗ 部署失败${NC}"
  echo "  Response: $DEP_RESP"
  exit 1
fi
echo -e "${GREEN}✓ 部署提交成功${NC}: id=$DEP_ID status=$DEP_STATUS"
PASSED=$((PASSED + 1))

# 校验 K8s Deployment
K8S_DEPLOY=$($KUBECTL get deploy -n "$WS_NS" --no-headers 2>/dev/null | grep -c "vllm-qwen3-svc" || echo "0")
assert_eq "K8s Deployment 存在" "$K8S_DEPLOY" "1"

# 校验 K8s Service
K8S_SVC=$($KUBECTL get svc -n "$WS_NS" --no-headers 2>/dev/null | grep -c "vllm-qwen3-svc-svc" || echo "0")
assert_eq "K8s Service 存在" "$K8S_SVC" "1"

# 校验 Pod 资源限制
POD_LIMITS=$($KUBECTL get deploy "vllm-qwen3-svc" -n "$WS_NS" -o jsonpath='{.spec.template.spec.containers[0].resources.limits}' 2>/dev/null)
assert_contains "Pod limits 含 nvidia.com/gpumem" "$POD_LIMITS" "nvidia.com/gpumem"
assert_contains "Pod limits 含 platform.io" "$POD_LIMITS" "platform.io"

# 校验 prq.used += 1
PROJ_DETAIL=$(curl_json GET "/api/v1/projects/$PROJ_ID" "")
USED=$(echo "$PROJ_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); v=[q['usedNodes'] for vs in d['quotaByPoolType'].values() for q in vs]; print(sum(v))" 2>/dev/null)
assert_eq "项目已用配额 = 1" "$USED" "1"

# ─────────────── 步骤 12：部署 OVERSELL（应不调 K8s） ═══
echo ""
echo "═══ 12. 部署超分规格（应仅记账，不调 K8s） ═══"
curl_json PATCH "/api/v1/pools/$POOL_OVERSELL" "{
  \"totalNodes\": 5,
  \"specs\": [\"$SPEC_OVERSELL\"]
}" > /dev/null
curl_json POST "/api/v1/projects/$PROJ_ID/quotas" "{
  \"poolId\": \"$POOL_OVERSELL\",
  \"specId\": \"$SPEC_OVERSELL\",
  \"totalNodes\": 3
}" > /dev/null
OVER_RESP=$(curl_json POST "/api/v1/projects/$PROJ_ID/deployments" '{
  "name": "oversell-test",
  "specName": "oversell-a100-mig-1/2",
  "replicas": 1,
  "image": "vllm/vllm-openai:latest",
  "modelSource": "without_weights",
  "modelIdOrPath": "/models"
}')
OVER_STATUS=$(echo "$OVER_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
assert_eq "超分部署 status=running（占位）" "$OVER_STATUS" "running"

OVER_K8S_DEPLOY=$($KUBECTL get deploy -n "$WS_NS" --no-headers 2>/dev/null | grep -c "vllm-oversell-test" || echo "0")
assert_eq "K8s 不应有超分 deployment" "$OVER_K8S_DEPLOY" "0"

# ─────────────── 步骤 13：删除部署 ═══
echo ""
echo "═══ 13. 删除部署（配额回滚） ═══"
curl_json DELETE "/api/v1/projects/$PROJ_ID/deployments/$DEP_ID" "" > /dev/null
$KUBECTL get deploy "vllm-qwen3-svc" -n "$WS_NS" 2>/dev/null && {
  echo -e "${RED}✗ K8s Deployment 残留${NC}"
  FAILED=$((FAILED + 1))
} || {
  echo -e "${GREEN}✓ K8s Deployment 已删${NC}"
  PASSED=$((PASSED + 1))
}

PROJ_DETAIL=$(curl_json GET "/api/v1/projects/$PROJ_ID" "")
USED=$(echo "$PROJ_DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); v=[q['usedNodes'] for vs in d['quotaByPoolType'].values() for q in vs]; print(sum(v))" 2>/dev/null)
# 应等于 1（超分仍占 1，shared 部署已删）
assert_eq "删除后项目已用配额 = 1（仅超分）" "$USED" "1"

# ─────────────── 步骤 14：删除工作空间 ═══
echo ""
echo "═══ 14. 删除工作空间（级联） ═══"
curl_json DELETE "/api/v1/workspaces/$WS_ID" "" > /dev/null
NS_EXISTS=$($KUBECTL get namespace "$WS_NS" -o name 2>/dev/null | grep -c "^namespace/" || echo "0")
assert_eq "K8s Namespace 已删" "$NS_EXISTS" "0"

# ─────────────── 总结 ───────────────
echo ""
echo "════════════════════════════════════════"
echo "  验证结果：${GREEN}通过 ${PASSED}${NC}  /  ${RED}失败 ${FAILED}${NC}"
echo "════════════════════════════════════════"
for r in "${RESULTS[@]}"; do
  echo "  $r"
done

if [[ $FAILED -gt 0 ]]; then
  exit 1
fi
echo ""
echo -e "${GREEN}✅ 全部通过${NC}"
