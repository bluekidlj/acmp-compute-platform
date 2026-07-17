// ============================================================
// 算力大屏 Mock 数据 + 实时仿真引擎
// ============================================================

let tick = 0;

function hourFactor(): number {
  const h = new Date().getHours();
  if (h >= 8 && h <= 18) return 0.85 + Math.random() * 0.15;   // 白天推理高
  if (h >= 22 || h <= 6) return 0.7 + Math.random() * 0.2;     // 夜间训练高
  return 0.5 + Math.random() * 0.3;
}

export function generateScreenKpi() {
  const f = hourFactor();
  return {
    totalFlops: +(62 + f * 8 + Math.random() * 2).toFixed(1),
    totalGpus: 128 + Math.floor(Math.random() * 4 - 2),
    avgUtil: Math.round((65 + f * 20 + Math.random() * 8 - 4)),
    todayTasks: Math.round(12800 + f * 3200 + Math.random() * 500 - 250),
    scheduleRate: +(99.2 + Math.random() * 0.6).toFixed(1),
    costReduction: +(18.5 + Math.random() * 2).toFixed(1),
  };
}

export function generateRingData() {
  return {
    brands: [
      { name: 'NVIDIA', value: 72 + Math.floor(Math.random() * 6 - 3), color: '#76B900' },
      { name: '昇腾', value: 36 + Math.floor(Math.random() * 4 - 2), color: '#CF0A2C' },
      { name: 'DCU', value: 20 + Math.floor(Math.random() * 3 - 1), color: '#005BAC' },
    ],
    tasks: [
      { name: '在线推理', value: 45 + Math.floor(Math.random() * 6 - 3), color: '#00d4aa' },
      { name: '模型训练', value: 30 + Math.floor(Math.random() * 5 - 2), color: '#4facfe' },
      { name: '批量处理', value: 15 + Math.floor(Math.random() * 3 - 1), color: '#faad14' },
      { name: '数据加工', value: 10 + Math.floor(Math.random() * 2 - 1), color: '#ff6b81' },
    ],
    totalFlops: +(62 + hourFactor() * 8 + Math.random() * 2).toFixed(1),
  };
}

export function generatePoolStatus() {
  const f = hourFactor();
  return [
    { name: '独占池', total: 48, used: Math.round(28 + f * 12), color: '#4facfe' },
    { name: '共享池', total: 60, used: Math.round(40 + f * 15), color: '#00d4aa' },
    { name: '超分池', total: 120, used: Math.round(70 + f * 25), color: '#faad14' },
  ];
}

export function generateLoadTrend() {
  const points = 24;
  return Array.from({ length: points }, (_, i) => ({
    time: `${i}:00`,
    inference: Math.round(i >= 8 && i <= 18 ? 65 + Math.random() * 25 : 10 + Math.random() * 15),
    training: Math.round(i >= 22 || i <= 6 ? 55 + Math.random() * 30 : 8 + Math.random() * 12),
  }));
}

export function generateEvents(count = 30) {
  const tasks = [
    'Qwen3-72B 推理', 'LLaMA-70B 训练', 'DeepSeek-V3 推理', 'BERT 微调',
    'OCR 识别', '风控模型', '投研分析', 'GLM-4 训练', 'SDXL 生图',
  ];
  const actions = ['调度成功 → H800', '分配 → 昇腾910B', '排队等待', '调度成功 → DCU', '迁移 → 合肥'];
  const statuses = ['success', 'info', 'warning', 'success', 'info'] as const;
  return Array.from({ length: count }, (_, i) => ({
    id: `evt-${i}`,
    task: tasks[Math.floor(Math.random() * tasks.length)],
    action: actions[Math.floor(Math.random() * actions.length)],
    status: statuses[Math.floor(Math.random() * statuses.length)],
    time: new Date(Date.now() - Math.random() * 60000).toLocaleTimeString(),
  }));
}

export function generateInferenceMetrics() {
  return {
    totalCalls: 128000 + Math.floor(Math.random() * 3000),
    successRate: +(99.2 + Math.random() * 0.6).toFixed(1),
    failedCalls: Math.floor(Math.random() * 50 + 5),
    avgLatency: Math.round(120 + Math.random() * 40),
  };
}

export const TOP_MODELS = [
  { name: 'DeepSeek-V3', calls: 28500 + Math.floor(Math.random() * 500) },
  { name: 'Qwen3-72B', calls: 22300 + Math.floor(Math.random() * 400) },
  { name: 'LLaMA-3-70B', calls: 18600 + Math.floor(Math.random() * 300) },
  { name: 'ChatGLM-4', calls: 12400 + Math.floor(Math.random() * 200) },
  { name: 'BERT-Base', calls: 8600 + Math.floor(Math.random() * 150) },
];

export interface HeatmapCell {
  value: number;
  task: string;
  status: 'idle' | 'normal' | 'busy' | 'overload' | 'critical';
}

export interface HeatmapRow {
  nodeName: string;
  brand: string;
  cluster: string;
  data: HeatmapCell[];
}

export interface HeatmapInsights {
  avgUtil: number;
  fragmentationIndex: number;
  idleCount: number;
  congestionPeriods: { start: number; end: number }[];
}

const HEATMAP_NODES = [
  { nodeName: 'H800-01', brand: 'H800', cluster: '北京' },
  { nodeName: 'H800-02', brand: 'H800', cluster: '北京' },
  { nodeName: 'H800-03', brand: 'H800', cluster: '北京' },
  { nodeName: 'H800-04', brand: 'H800', cluster: '北京' },
  { nodeName: 'A100-01', brand: 'A100', cluster: '北京' },
  { nodeName: 'A100-02', brand: 'A100', cluster: '北京' },
  { nodeName: '昇腾910B-01', brand: '昇腾910B', cluster: '合肥' },
  { nodeName: '昇腾910B-02', brand: '昇腾910B', cluster: '合肥' },
  { nodeName: '昇腾910C-01', brand: '昇腾910C', cluster: '合肥' },
  { nodeName: 'DCU-01', brand: 'DCU', cluster: '合肥' },
];

const TASKS_BY_BRAND: Record<string, string[]> = {
  H800: ['Qwen3-72B 推理', 'DeepSeek-V3 推理', 'LLaMA-70B 推理', 'GLM-4 推理'],
  A100: ['BERT 微调', 'SDXL 生图', 'ChatGLM 推理', 'OCR 识别'],
  '昇腾910B': ['风控模型推理', '智能客服', 'OCR 识别', '文档审核'],
  '昇腾910C': ['GLM-4 训练', '大模型微调', '批量推理'],
  DCU: ['CV 模型推理', '图像识别', '视频分析'],
};

function getStatus(v: number): HeatmapCell['status'] {
  if (v <= 20) return 'idle';
  if (v <= 50) return 'normal';
  if (v <= 75) return 'busy';
  if (v <= 90) return 'overload';
  return 'critical';
}

export function generateHeatmap() {
  const f = hourFactor();
  const rows: HeatmapRow[] = HEATMAP_NODES.map((node) => {
    const tasks = TASKS_BY_BRAND[node.brand] || ['通用任务'];
    const isBeijing = node.cluster === '北京';
    const data: HeatmapCell[] = Array.from({ length: 24 }, (_, c) => {
      let base: number;
      if (node.brand === 'H800' || node.brand === 'A100') {
        base = (c >= 8 && c <= 18) ? 65 : (c >= 22 || c <= 6) ? 50 : 15;
      } else if (node.brand === '昇腾910B') {
        base = (c >= 9 && c <= 17) ? 55 : (c >= 23 || c <= 5) ? 30 : 10;
      } else if (node.brand === '昇腾910C') {
        base = (c >= 22 || c <= 6) ? 75 : (c >= 10 && c <= 16) ? 30 : 10;
      } else {
        base = (c >= 8 && c <= 18) ? 45 : (c >= 22 || c <= 4) ? 20 : 10;
      }
      const noise = Math.random() * 20 - 10;
      const value = Math.max(0, Math.min(100, Math.round(base + noise * f)));
      return { value, task: tasks[Math.floor(Math.random() * tasks.length)], status: getStatus(value) };
    });
    return { ...node, data };
  });

  const avgPerHour = Array.from({ length: 24 }, (_, c) =>
    Math.round(rows.reduce((s, r) => s + r.data[c].value, 0) / rows.length)
  );

  const avgUtil = Math.round(avgPerHour.reduce((a, b) => a + b, 0) / 24);
  const fragmentationIndex = +(Math.random() * 0.15 + 0.15).toFixed(2);
  const idleCount = rows.filter((r) => r.data.some((d) => d.status === 'idle')).length;

  const congestionPeriods: { start: number; end: number }[] = [];
  let conStart: number | null = null;
  for (let c = 0; c < 24; c++) {
    const avg = avgPerHour[c];
    if (avg > 70 && conStart === null) conStart = c;
    if ((avg <= 70 || c === 23) && conStart !== null) {
      congestionPeriods.push({ start: conStart, end: c === 23 && avg > 70 ? c + 1 : c });
      conStart = null;
    }
  }

  return {
    rows,
    timeLabels: Array.from({ length: 24 }, (_, i) => `${i.toString().padStart(2, '0')}:00`),
    avgPerHour,
    insights: { avgUtil, fragmentationIndex, idleCount, congestionPeriods } as HeatmapInsights,
  };
}

export function generateGlobalTrend() {
  const points = 24;
  return Array.from({ length: points }, (_, i) => ({
    time: `${i}:00`,
    flops: +(55 + Math.sin(i / 4) * 10 + Math.random() * 5).toFixed(1),
    utilization: Math.round(50 + Math.sin((i - 8) / 5) * 25 + Math.random() * 8),
  }));
}

let eventLog: any[] = generateEvents(20);

export function getEventLog() {
  tick++;
  if (tick % 2 === 0) {
    eventLog = [generateEvents(1)[0], ...eventLog].slice(0, 30);
  }
  return eventLog;
}

export function createScreenEngine(onTick: (data: any) => void) {
  const interval = setInterval(() => {
    onTick({
      kpi: generateScreenKpi(),
      ring: generateRingData(),
      pools: generatePoolStatus(),
      loadTrend: generateLoadTrend(),
      events: getEventLog(),
      inference: generateInferenceMetrics(),
      topModels: TOP_MODELS,
      heatmap: generateHeatmap(),
      globalTrend: generateGlobalTrend(),
    });
  }, 3000);
  return { stop: () => clearInterval(interval) };
}
