import { useEffect, useRef, useState } from 'react';
import { PieChart, Pie, Cell, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip as ReTooltip, ResponsiveContainer, BarChart, Bar, LineChart, Line } from 'recharts';
import { generateScreenKpi, generateRingData, generatePoolStatus, generateLoadTrend, getEventLog, generateInferenceMetrics, TOP_MODELS, generateHeatmap, generateGlobalTrend } from '../mock/screen-data';

// ─── 颜色常量 ───
const BG = '#0a1628';
const CARD_BG = 'rgba(16, 36, 64, 0.7)';
const BORDER = 'rgba(0, 212, 170, 0.2)';
const GLOW = '#00d4aa';
const BLUE = '#4facfe';
const RED = '#ff6b81';
const ORANGE = '#faad14';
const TEXT = '#b8d4e3';
const TEXT_DIM = '#6a8ca8';

const heatColor = (v: number) =>
  v > 85 ? '#ff4757' : v > 70 ? '#ffa502' : v > 50 ? '#3742fa' : v > 30 ? '#00d4aa' : '#1a3a5a';

// ─── Animated Number ───
function AnimatedNumber({ value, suffix = '', decimals = 0 }: { value: number; suffix?: string; decimals?: number }) {
  const [display, setDisplay] = useState(value);
  const prev = useRef(value);
  useEffect(() => {
    const start = prev.current;
    const diff = value - start;
    if (Math.abs(diff) < 0.5) { setDisplay(value); return; }
    const steps = 20;
    let s = 0;
    const iv = setInterval(() => {
      s++;
      setDisplay(start + diff * (s / steps));
      if (s >= steps) clearInterval(iv);
    }, 30);
    prev.current = value;
    return () => clearInterval(iv);
  }, [value]);
  return <>{display.toFixed(decimals)}{suffix}</>;
}

// ─── KPI Card ───
function KpiCard({ title, value, suffix = '', decimals = 0, color = GLOW, icon }: { title: string; value: number; suffix?: string; decimals?: number; color?: string; icon: string }) {
  return (
    <div style={{
      flex: 1, background: CARD_BG, borderRadius: 8, padding: '10px 14px',
      border: `1px solid ${BORDER}`, boxShadow: `0 0 20px rgba(0,212,170,0.05)`,
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={{ position: 'absolute', top: -20, right: -10, fontSize: 60, opacity: 0.06 }}>{icon}</div>
      <div style={{ fontSize: 11, color: TEXT_DIM, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 26, fontWeight: 700, color, fontFamily: '"Courier New", monospace', letterSpacing: 1 }}>
        <AnimatedNumber value={value} suffix={suffix} decimals={decimals} />
      </div>
    </div>
  );
}

// ─── 中央宇宙算力图 ───
function CentralRing({ ring }: { ring: ReturnType<typeof generateRingData> }) {
  const brandTotal = ring.brands.reduce((s, b) => s + b.value, 0);
  return (
    <div style={{
      flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      position: 'relative', minHeight: 360,
    }}>
      <ResponsiveContainer width="100%" height={360}>
        <PieChart>
          <Pie data={ring.tasks} dataKey="value" cx="50%" cy="50%" innerRadius={100} outerRadius={140}
            stroke="none" paddingAngle={2}
            animationBegin={0} animationDuration={1500} isAnimationActive>
            {ring.tasks.map((e, i) => <Cell key={i} fill={e.color} />)}
          </Pie>
          <Pie data={ring.brands} dataKey="value" cx="50%" cy="50%" innerRadius={55} outerRadius={90}
            stroke="none" paddingAngle={2}
            animationBegin={300} animationDuration={1500} isAnimationActive>
            {ring.brands.map((e, i) => <Cell key={i} fill={e.color} />)}
          </Pie>
        </PieChart>
      </ResponsiveContainer>
      <div style={{
        position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -60%)',
        textAlign: 'center',
      }}>
        <div style={{ fontSize: 11, color: TEXT_DIM, marginBottom: 2 }}>总算力</div>
        <div style={{ fontSize: 32, fontWeight: 700, color: GLOW, fontFamily: '"Courier New", monospace', textShadow: `0 0 30px rgba(0,212,170,0.4)` }}>
          <AnimatedNumber value={ring.totalFlops} suffix=" PFLOPS" decimals={1} />
        </div>
      </div>
    </div>
  );
}

// ─── 左侧面板 ───
function LeftPanel({ data }: { data: ReturnType<typeof generateRingData> }) {
  const pools = generatePoolStatus();
  const trend = generateLoadTrend();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, height: '100%' }}>
      {/* GPU 品牌环形图 */}
      <div style={{ flex: 1, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: 8 }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 4 }}>GPU 品牌分布</div>
        <ResponsiveContainer width="100%" height={130}>
          <PieChart>
            <Pie data={data.brands} dataKey="value" cx="50%" cy="50%" innerRadius={30} outerRadius={50}
              stroke="none" isAnimationActive>
              {data.brands.map((e, i) => <Cell key={i} fill={e.color} />)}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
      </div>
      {/* 资源池 */}
      <div style={{ flex: 1, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: '8px 10px' }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 6 }}>资源池状态</div>
        {pools.map((p) => {
          const pct = Math.round((p.used / p.total) * 100);
          return (
            <div key={p.name} style={{ marginBottom: 6 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: TEXT_DIM }}>
                <span>{p.name}</span><span>{p.used}/{p.total}</span>
              </div>
              <div style={{ height: 4, background: 'rgba(255,255,255,0.06)', borderRadius: 2, marginTop: 2 }}>
                <div style={{ width: `${pct}%`, height: '100%', background: p.color, borderRadius: 2, transition: 'width 1s' }} />
              </div>
            </div>
          );
        })}
      </div>
      {/* 负载趋势 */}
      <div style={{ flex: 1.6, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: 8 }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 4 }}>负载趋势</div>
        <ResponsiveContainer width="100%" height={100}>
          <AreaChart data={trend}>
            <defs>
              <linearGradient id="inf" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor={GLOW} stopOpacity={0.3} /><stop offset="95%" stopColor={GLOW} stopOpacity={0} /></linearGradient>
              <linearGradient id="trn" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor={BLUE} stopOpacity={0.3} /><stop offset="95%" stopColor={BLUE} stopOpacity={0} /></linearGradient>
            </defs>
            <CartesianGrid stroke="rgba(255,255,255,0.04)" />
            <XAxis dataKey="time" hide />
            <YAxis hide />
            <ReTooltip contentStyle={{ background: '#0a1628', border: `1px solid ${BORDER}`, borderRadius: 4 }} />
            <Area type="monotone" dataKey="inference" stroke={GLOW} fill="url(#inf)" strokeWidth={2} dot={false} />
            <Area type="monotone" dataKey="training" stroke={BLUE} fill="url(#trn)" strokeWidth={2} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

// ─── 右侧面板 ───
function RightPanel({ events, inference }: { events: any[]; inference: ReturnType<typeof generateInferenceMetrics> }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, height: '100%' }}>
      {/* 调度日志 */}
      <div style={{ flex: 2, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: 8, overflow: 'hidden' }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 6 }}>实时调度流</div>
        <div style={{ height: 160, overflow: 'hidden' }}>
          {events.slice(0, 8).map((e, i) => (
            <div key={e.id + i} style={{
              display: 'flex', gap: 6, alignItems: 'center', padding: '2px 0',
              fontSize: 10, color: e.status === 'success' ? GLOW : e.status === 'warning' ? ORANGE : TEXT_DIM,
              animation: i < 3 ? 'slideIn 0.3s ease' : undefined,
            }}>
              <div style={{ width: 4, height: 4, borderRadius: 2, background: e.status === 'success' ? GLOW : e.status === 'warning' ? ORANGE : BLUE, flexShrink: 0 }} />
              <span style={{ color: TEXT_DIM, flexShrink: 0 }}>{e.time}</span>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.task} {e.action}</span>
            </div>
          ))}
        </div>
      </div>
      {/* AI推理监控 */}
      <div style={{ flex: 1, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: '8px 10px' }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 6 }}>AI 推理调用监控</div>
        <div style={{ display: 'flex', gap: 16 }}>
          {[
            { label: '调用次数', value: inference.totalCalls.toLocaleString(), color: GLOW },
            { label: '成功率', value: `${inference.successRate}%`, color: BLUE },
            { label: '失败', value: inference.failedCalls, color: RED },
            { label: '延迟', value: `${inference.avgLatency}ms`, color: ORANGE },
          ].map((m, i) => (
            <div key={i}>
              <div style={{ fontSize: 9, color: TEXT_DIM }}>{m.label}</div>
              <div style={{ fontSize: 16, fontWeight: 700, color: m.color, fontFamily: '"Courier New", monospace' }}>{m.value}</div>
            </div>
          ))}
        </div>
      </div>
      {/* TOP模型 */}
      <div style={{ flex: 1.4, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: 8 }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 4 }}>TOP 模型调用排行</div>
        <ResponsiveContainer width="100%" height={90}>
          <BarChart data={TOP_MODELS} layout="vertical" margin={{ top: 0, right: 0, bottom: 0, left: 60 }}>
            <CartesianGrid stroke="rgba(255,255,255,0.04)" horizontal={false} />
            <XAxis type="number" hide />
            <YAxis type="category" dataKey="name" tick={{ fill: TEXT_DIM, fontSize: 9 }} width={70} />
            <ReTooltip contentStyle={{ background: '#0a1628', border: `1px solid ${BORDER}`, borderRadius: 4 }} />
            <Bar dataKey="calls" fill={GLOW} radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

// ─── GPU 热力矩阵 ───
const CELL_COLORS = ['#1a3a5a', '#00d4aa', '#3742fa', '#ffa502', '#ff4757'];
const CELL_LABELS = ['空闲', '正常', '忙碌', '过载', '拥堵'];

function GpuHeatmap({ heatmap }: { heatmap: ReturnType<typeof generateHeatmap> }) {
  const [tooltip, setTooltip] = useState<{ x: number; y: number; node: string; hour: string; value: number; task: string; status: string } | null>(null);
  const { rows, timeLabels, avgPerHour, insights } = heatmap;
  const cellW = 28;
  const cellH = 20;
  const labelW = 80;

  const hc = (v: number) => v > 90 ? CELL_COLORS[4] : v > 75 ? CELL_COLORS[3] : v > 50 ? CELL_COLORS[2] : v > 25 ? CELL_COLORS[1] : CELL_COLORS[0];
  const hs = (v: number) => v > 90 ? 'critical' : v > 75 ? 'overload' : v > 50 ? 'busy' : v > 25 ? 'normal' : 'idle';

  return (
    <div style={{ flex: 1, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: '8px 12px', overflow: 'hidden', position: 'relative' }}>
      {/* Header：标题 + 图例 + 统计 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <div style={{ fontSize: 11, color: TEXT }}>GPU 资源热力矩阵</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ display: 'flex', gap: 2, alignItems: 'center' }}>
            {CELL_COLORS.map((c, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                <div style={{ width: 10, height: 10, background: c, borderRadius: 2 }} />
                <span style={{ fontSize: 9, color: TEXT_DIM, marginRight: 4 }}>{CELL_LABELS[i]}</span>
              </div>
            ))}
          </div>
          <div style={{ width: 1, height: 14, background: BORDER }} />
          <span style={{ fontSize: 10, color: TEXT_DIM }}>平均 <b style={{ color: GLOW }}>{insights.avgUtil}%</b></span>
          <span style={{ fontSize: 10, color: TEXT_DIM }}>碎片 <b style={{ color: ORANGE }}>{insights.fragmentationIndex}</b></span>
          <span style={{ fontSize: 10, color: TEXT_DIM }}>空闲 <b style={{ color: BLUE }}>{insights.idleCount}</b></span>
        </div>
      </div>

      {/* 矩阵主体 */}
      <div style={{ position: 'relative', marginLeft: labelW }}>
        {/* X轴时间标签 */}
        <div style={{ display: 'flex', marginBottom: 2 }}>
          {timeLabels.map((t, i) => (
            <div key={t} style={{
              width: cellW, fontSize: 8, color: TEXT_DIM, textAlign: 'center',
              visibility: i % 3 === 0 ? 'visible' : 'hidden',
            }}>{t}</div>
          ))}
        </div>

        {/* 每行 */}
        {rows.map((row, ri) => (
          <div key={row.nodeName} style={{ display: 'flex', alignItems: 'center', marginBottom: 1 }}>
            {/* Y轴标签 */}
            <div style={{
              width: labelW, fontSize: 9, color: TEXT_DIM, textAlign: 'right', paddingRight: 6,
              whiteSpace: 'nowrap',
            }}>
              <span style={{ color: row.cluster === '北京' ? GLOW : BLUE }}>●</span> {row.nodeName}
            </div>
            {/* 数据格 */}
            {row.data.map((cell, ci) => (
              <div key={ci} style={{
                width: cellW, height: cellH, background: hc(cell.value),
                borderRadius: 1, cursor: 'pointer', position: 'relative',
                transition: 'background 0.8s',
                border: tooltip?.node === row.nodeName && tooltip?.hour === timeLabels[ci]
                  ? '1px solid #fff' : '1px solid transparent',
              }}
                onMouseEnter={(e) => setTooltip({
                  x: e.clientX, y: e.clientY,
                  node: row.nodeName, hour: timeLabels[ci],
                  value: cell.value, task: cell.task, status: hs(cell.value),
                })}
                onMouseLeave={() => setTooltip(null)}
              />
            ))}
          </div>
        ))}

        {/* 拥堵标记条 */}
        <div style={{ display: 'flex', marginTop: 4, marginLeft: 0 }}>
          {avgPerHour.map((avg, i) => (
            <div key={i} style={{
              width: cellW, height: 4,
              background: avg > 70 ? RED : avg > 50 ? ORANGE : 'transparent',
              borderRadius: 1, transition: 'background 1s',
            }} />
          ))}
        </div>
        <div style={{ display: 'flex', marginTop: 1 }}>
          {timeLabels.map((t, i) => (
            <div key={t} style={{
              width: cellW, fontSize: 7, color: avgPerHour[i] > 70 ? RED : TEXT_DIM, textAlign: 'center',
              visibility: i % 3 === 0 ? 'visible' : 'hidden',
            }}>{t}</div>
          ))}
        </div>

        {/* 拥塞时段标注 */}
        {insights.congestionPeriods.map((p) => (
          <div key={`${p.start}-${p.end}`} style={{
            position: 'absolute', bottom: 18, left: p.start * cellW,
            width: (p.end - p.start) * cellW, height: 14,
            background: 'rgba(255,71,87,0.08)', border: '1px solid rgba(255,71,87,0.2)',
            borderRadius: 2, display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 8, color: RED, pointerEvents: 'none',
          }}>
            🔴 拥堵
          </div>
        ))}
      </div>

      {/* Tooltip */}
      {tooltip && (
        <div style={{
          position: 'fixed', top: tooltip.y - 80, left: tooltip.x + 12,
          background: 'rgba(10,22,40,0.95)', border: `1px solid ${GLOW}`,
          borderRadius: 6, padding: '8px 12px', fontSize: 11, color: TEXT,
          zIndex: 9999, boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
          pointerEvents: 'none',
        }}>
          <div style={{ fontWeight: 700, marginBottom: 4 }}>{tooltip.node} · {tooltip.hour}</div>
          <div>利用率: <span style={{ color: hc(tooltip.value), fontWeight: 600 }}>{tooltip.value}%</span></div>
          <div>状态: <span style={{ color: hc(tooltip.value) }}>{tooltip.status}</span></div>
          <div>任务: <span style={{ color: TEXT_DIM }}>{tooltip.task}</span></div>
        </div>
      )}
    </div>
  );
}

// ─── 底部面板 ───
function BottomPanel({ heatmap, trend }: { heatmap: ReturnType<typeof generateHeatmap>; trend: ReturnType<typeof generateGlobalTrend> }) {
  return (
    <div style={{ display: 'flex', gap: 10, height: '100%' }}>
      <div style={{ flex: 3, minWidth: 0 }}>
        <GpuHeatmap heatmap={heatmap} />
      </div>
      <div style={{ flex: 1, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, padding: 8 }}>
        <div style={{ fontSize: 11, color: TEXT, marginBottom: 4 }}>算力趋势</div>
        <ResponsiveContainer width="100%" height={180}>
          <LineChart data={trend}>
            <CartesianGrid stroke="rgba(255,255,255,0.04)" />
            <XAxis dataKey="time" hide />
            <YAxis hide />
            <ReTooltip contentStyle={{ background: '#0a1628', border: `1px solid ${BORDER}`, borderRadius: 4 }} />
            <Line type="monotone" dataKey="flops" stroke={GLOW} strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="utilization" stroke={BLUE} strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

// ─── 主体 ───
export default function ScreenDashboard() {
  const [data, setData] = useState(() => ({
    kpi: generateScreenKpi(),
    ring: generateRingData(),
    pools: generatePoolStatus(),
    loadTrend: generateLoadTrend(),
    events: getEventLog(),
    inference: generateInferenceMetrics(),
    topModels: TOP_MODELS,
    heatmap: generateHeatmap(),
    globalTrend: generateGlobalTrend(),
  }));

  useEffect(() => {
    const iv = setInterval(() => {
      setData({
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
    return () => clearInterval(iv);
  }, []);

  return (
    <div style={{
      width: '100vw', height: '100vh', background: BG, overflow: 'hidden',
      fontFamily: '"PingFang SC","Microsoft YaHei",sans-serif',
      display: 'flex', flexDirection: 'column', padding: 14, gap: 10,
    }}>
      {/* Title */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0 }}>
        <div style={{ fontSize: 18, fontWeight: 700, color: '#fff', letterSpacing: 4, textShadow: `0 0 20px rgba(0,212,170,0.3)` }}>
          ⚡ AI 算力运营指挥中心
        </div>
        <div style={{ fontSize: 11, color: TEXT_DIM, fontFamily: '"Courier New", monospace' }}>
          {new Date().toLocaleDateString()} {new Date().toLocaleTimeString()}
        </div>
      </div>

      {/* KPI 条 */}
      <div style={{ display: 'flex', gap: 10, flexShrink: 0 }}>
        <KpiCard title="总算力" value={data.kpi.totalFlops} suffix=" PFLOPS" decimals={1} color={GLOW} icon="⚡" />
        <KpiCard title="GPU 总数" value={data.kpi.totalGpus} suffix=" 张" color={BLUE} icon="🖥" />
        <KpiCard title="平均利用率" value={data.kpi.avgUtil} suffix="%" color={data.kpi.avgUtil > 80 ? RED : GLOW} icon="📊" />
        <KpiCard title="今日任务" value={data.kpi.todayTasks} suffix="" color={ORANGE} icon="📋" />
        <KpiCard title="调度成功率" value={data.kpi.scheduleRate} suffix="%" decimals={1} color={data.kpi.scheduleRate < 99 ? ORANGE : GLOW} icon="✅" />
        <KpiCard title="成本下降" value={data.kpi.costReduction} suffix="%" decimals={1} color={GLOW} icon="💰" />
      </div>

      {/* 中间三栏 */}
      <div style={{ flex: 1, display: 'flex', gap: 10, minHeight: 0 }}>
        <div style={{ flex: 1.2, minWidth: 0 }}><LeftPanel data={data.ring} /></div>
        <div style={{ flex: 2, minWidth: 0, background: CARD_BG, borderRadius: 8, border: `1px solid ${BORDER}`, position: 'relative' }}>
          <CentralRing ring={data.ring} />
          <div style={{ position: 'absolute', bottom: 8, left: 14, fontSize: 9, color: TEXT_DIM, letterSpacing: 1 }}>
            ● 内环: GPU 品牌  ● 外环: 任务类型
          </div>
        </div>
        <div style={{ flex: 1.2, minWidth: 0 }}><RightPanel events={data.events} inference={data.inference} /></div>
      </div>

      {/* 底部 */}
      <div style={{ height: 260, flexShrink: 0 }}>
        <BottomPanel heatmap={data.heatmap} trend={data.globalTrend} />
      </div>

      <style>{`
        @keyframes slideIn {
          from { opacity: 0; transform: translateX(-10px); }
          to { opacity: 1; transform: translateX(0); }
        }
        ::-webkit-scrollbar { width: 0; }
      `}</style>
    </div>
  );
}
