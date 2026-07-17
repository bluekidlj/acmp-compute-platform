// ============================================================
// 创新实验室 Mock 数据 + 模拟引擎
// ============================================================

// ── GPU 品牌与机房分布 ──
export const LAB_CLUSTERS = [
  { id: 'beijing', name: '北京数据中心', gpus: ['H800', 'A100'], brands: ['NVIDIA'], color: '#00754A', nodes: 48 },
  { id: 'hefei', name: '合肥数据中心', gpus: ['昇腾910B', '昇腾910C', 'DCU'], brands: ['HUAWEI_ASCEND', 'HYGON'], color: '#1A8A50', nodes: 32 },
];

export const GPU_CATALOG = [
  { model: 'H800', brand: 'NVIDIA', memGB: 80, cluster: 'beijing', count: 24 },
  { model: 'A100', brand: 'NVIDIA', memGB: 80, cluster: 'beijing', count: 24 },
  { model: '昇腾910B', brand: 'HUAWEI_ASCEND', memGB: 64, cluster: 'hefei', count: 16 },
  { model: '昇腾910C', brand: 'HUAWEI_ASCEND', memGB: 64, cluster: 'hefei', count: 8 },
  { model: 'DCU', brand: 'HYGON', memGB: 32, cluster: 'hefei', count: 8 },
];

// ── KPI 数据 ──
export function generateLabKpi() {
  return {
    gpuUtilization: 67 + Math.floor(Math.random() * 8),
    resourceWaste: 12 + Math.floor(Math.random() * 5),
    experimentCount: 24,
    simulationCount: 156,
    optimizationGain: 18.5 + Math.random() * 4,
    costSaving: 32 + Math.floor(Math.random() * 8),
  };
}

// ── 策略对比 ──
export function generateStrategyComparison() {
  const hours = Array.from({ length: 24 }, (_, i) => `${i.toString().padStart(2, '0')}:00`);
  const current = hours.map(() => 50 + Math.random() * 35);
  const experimental = hours.map(() => 55 + Math.random() * 35);
  return { hours, current, experimental };
}

// ── 实验入口模块 ──
export const LAB_MODULES = [
  { key: 'digital-twin', title: '数字孪生', desc: '集群镜像与仿真控制', icon: '🌐', status: 'running', updated: '2 分钟前', color: '#00754A' },
  { key: 'strategy-lab', title: '策略实验室', desc: '潮汐调度与 What-if 分析', icon: '🧪', status: 'running', updated: '5 分钟前', color: '#1A8A50' },
  { key: 'workload', title: '负载感知', desc: '任务级负载分析与画像', icon: '📊', status: 'running', updated: '10 分钟前', color: '#52C41A' },
  { key: 'governance', title: '数据治理', desc: '安全策略与合规控制', icon: '🔐', status: 'idle', updated: '1 小时前', color: '#FAAD14' },
];

// ── 最近实验记录 ──
export const LAB_RECORDS = [
  { id: 'exp-001', name: '北京集群潮汐调度策略优化', type: '潮汐调度', status: 'completed', gain: '+18.5%', time: '2026-07-01 14:30' },
  { id: 'exp-002', name: '合肥昇腾910B推理负载仿真', type: '仿真模拟', status: 'running', gain: '-', time: '2026-07-02 09:15' },
  { id: 'exp-003', name: '跨集群故障迁移演练', type: 'What-if', status: 'completed', gain: '+12.3%', time: '2026-06-30 16:00' },
  { id: 'exp-004', name: 'H800 vs 昇腾910C 成本对比', type: '仿真模拟', status: 'completed', gain: '+23.7%', time: '2026-06-29 11:20' },
  { id: 'exp-005', name: '夜间训练潮汐调度策略', type: '潮汐调度', status: 'failed', gain: '-2.1%', time: '2026-06-28 22:00' },
];

// ── 数字孪生数据 ──
export function generateTwinStatus() {
  return {
    beijing: {
      cpu: 62 + Math.floor(Math.random() * 15),
      memory: 55 + Math.floor(Math.random() * 20),
      gpuUtil: 70 + Math.floor(Math.random() * 15),
      gpuTemp: 68 + Math.floor(Math.random() * 12),
      power: 320 + Math.floor(Math.random() * 40),
      onlineNodes: 48,
      totalNodes: 48,
      gpuList: [
        { id: 'bj-h800-01', model: 'H800', util: 75 + Math.floor(Math.random() * 15), temp: 72 + Math.floor(Math.random() * 8), mem: 60 + Math.floor(Math.random() * 20), task: 'Qwen3-72B 推理' },
        { id: 'bj-h800-02', model: 'H800', util: 60 + Math.floor(Math.random() * 20), temp: 68 + Math.floor(Math.random() * 10), mem: 45 + Math.floor(Math.random() * 25), task: 'DeepSeek-V3 推理' },
        { id: 'bj-a100-01', model: 'A100', util: 45 + Math.floor(Math.random() * 25), temp: 62 + Math.floor(Math.random() * 10), mem: 35 + Math.floor(Math.random() * 20), task: 'BERT 微调' },
        { id: 'bj-a100-02', model: 'A100', util: 80 + Math.floor(Math.random() * 10), temp: 74 + Math.floor(Math.random() * 6), mem: 70 + Math.floor(Math.random() * 15), task: 'LLaMA-70B 推理' },
      ],
    },
    hefei: {
      cpu: 45 + Math.floor(Math.random() * 15),
      memory: 40 + Math.floor(Math.random() * 20),
      gpuUtil: 50 + Math.floor(Math.random() * 20),
      gpuTemp: 58 + Math.floor(Math.random() * 12),
      power: 180 + Math.floor(Math.random() * 30),
      onlineNodes: 32,
      totalNodes: 32,
      gpuList: [
        { id: 'hf-910b-01', model: '昇腾910B', util: 55 + Math.floor(Math.random() * 20), temp: 60 + Math.floor(Math.random() * 8), mem: 40 + Math.floor(Math.random() * 20), task: '风控模型推理' },
        { id: 'hf-910b-02', model: '昇腾910B', util: 30 + Math.floor(Math.random() * 15), temp: 55 + Math.floor(Math.random() * 6), mem: 25 + Math.floor(Math.random() * 15), task: 'OCR 识别' },
        { id: 'hf-910c-01', model: '昇腾910C', util: 65 + Math.floor(Math.random() * 15), temp: 65 + Math.floor(Math.random() * 8), mem: 50 + Math.floor(Math.random() * 20), task: 'GLM-4 训练' },
        { id: 'hf-dcu-01', model: 'DCU', util: 40 + Math.floor(Math.random() * 15), temp: 52 + Math.floor(Math.random() * 6), mem: 30 + Math.floor(Math.random() * 15), task: 'CV 模型推理' },
      ],
    },
  };
}

// ── 潮汐调度数据 ──
export function generateTidalData(strategy = 'balanced') {
  const hours = Array.from({ length: 24 }, (_, i) => `${i.toString().padStart(2, '0')}:00`);
  const baseMultiplier = strategy === 'balanced' ? 1 : strategy === 'utilization' ? 1.15 : strategy === 'cost' ? 0.85 : 1.05;
  const beijingInfer = hours.map((_, i) => {
    const base = i >= 8 && i <= 22 ? 70 + Math.random() * 25 : 20 + Math.random() * 15;
    return Math.round(base * baseMultiplier);
  });
  const beijingTrain = hours.map((_, i) => {
    const base = i >= 22 || i <= 6 ? 60 + Math.random() * 30 : 15 + Math.random() * 20;
    return Math.round(base * baseMultiplier);
  });
  const hefeiInfer = hours.map((_, i) => {
    const base = i >= 9 && i <= 18 ? 50 + Math.random() * 20 : 15 + Math.random() * 15;
    return Math.round(base * baseMultiplier * 0.7);
  });
  return { hours, beijingInfer, beijingTrain, hefeiInfer };
}

// ── 仿真推荐 ──
export function generateSimRecommendation(description: string) {
  const isLarge = description.includes('70B') || description.includes('大模型') || description.includes('训练');
  const isRealtime = description.includes('实时') || description.includes('风控') || description.includes('延迟');
  return {
    recommendedGpu: isLarge ? (isRealtime ? 'H800' : '昇腾910C') : (isRealtime ? 'A100' : '昇腾910B'),
    cluster: isLarge ? 'beijing' : 'hefei',
    estimatedLatency: isRealtime ? '120ms' : '350ms',
    estimatedCost: isLarge ? '¥42.5/小时' : '¥18.3/小时',
    successRate: isLarge ? '96.8%' : '99.2%',
    gpuCount: isLarge ? 4 : 1,
    confidence: 87 + Math.floor(Math.random() * 10),
  };
}

// ── 负载感知 ──
export const WORKLOAD_TASKS = [
  { id: 'task-01', name: '风控模型推理', gpu: '昇腾910B', cluster: '合肥', pattern: 'steady', qps: 320, gpuUtil: 72, memUtil: 58, duration: '持续运行', status: 'running' },
  { id: 'task-02', name: 'OCR 识别服务', gpu: 'DCU', cluster: '合肥', pattern: 'burst', qps: '50-500', gpuUtil: 45, memUtil: 35, duration: '08:00-20:00', status: 'running' },
  { id: 'task-03', name: 'Qwen3-72B 推理', gpu: 'H800', cluster: '北京', pattern: 'steady', qps: 180, gpuUtil: 85, memUtil: 78, duration: '持续运行', status: 'running' },
  { id: 'task-04', name: 'GLM-4 训练任务', gpu: '昇腾910C', cluster: '合肥', pattern: 'batch', qps: '-', gpuUtil: 92, memUtil: 88, duration: '22:00-06:00', status: 'running' },
  { id: 'task-05', name: 'BERT 微调', gpu: 'A100', cluster: '北京', pattern: 'batch', qps: '-', gpuUtil: 55, memUtil: 42, duration: '剩余 2h', status: 'running' },
  { id: 'task-06', name: 'LLaMA-70B 推理', gpu: 'H800', cluster: '北京', pattern: 'steady', qps: 95, gpuUtil: 78, memUtil: 72, duration: '持续运行', status: 'running' },
];

// ── 数据治理 ──
export const DATA_CLASSIFICATIONS = [
  { level: 'Public', label: '公开', color: '#52C41A', desc: '可公开访问的模型和数据' },
  { level: 'Internal', label: '内部', color: '#00754A', desc: '内部使用，不可外传' },
  { level: 'Sensitive', label: '敏感', color: '#FAAD14', desc: '含客户隐私信息' },
  { level: 'Regulatory', label: '监管级', color: '#FF4D4F', desc: '受银保监会监管' },
];

export const GOVERNANCE_BINDINGS = [
  { model: '风控评分模型', dataLevel: 'Regulatory', gpuRestriction: '仅限专用 GPU', cluster: '合肥', allowCrossPool: false, allowHetero: false },
  { model: '智能客服模型', dataLevel: 'Sensitive', gpuRestriction: '昇腾 910B 以上', cluster: '合肥', allowCrossPool: false, allowHetero: true },
  { model: '投研分析模型', dataLevel: 'Internal', gpuRestriction: '无限制', cluster: '北京', allowCrossPool: true, allowHetero: true },
  { model: '代码辅助模型', dataLevel: 'Internal', gpuRestriction: '无限制', cluster: '北京', allowCrossPool: true, allowHetero: true },
  { model: 'OCR 识别模型', dataLevel: 'Sensitive', gpuRestriction: 'DCU 专属', cluster: '合肥', allowCrossPool: false, allowHetero: false },
];

// ── 模拟引擎 ──
export function createSimulationEngine(onTick: (data: any) => void) {
  let tickCount = 0;
  const interval = setInterval(() => {
    tickCount++;
    onTick({
      timestamp: new Date().toISOString(),
      tick: tickCount,
      kpi: generateLabKpi(),
      twin: generateTwinStatus(),
      tidal: generateTidalData(),
    });
  }, 5000);
  return { stop: () => clearInterval(interval) };
}
