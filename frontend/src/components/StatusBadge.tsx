import { Badge, Tooltip } from 'antd';

const labels: Record<string, string> = {
  ACTIVE: '正常',
  READY: '就绪',
  RUNNING: '运行中',
  SUBMITTED: '已提交',
  PENDING: '调度中',
  FAILED: '失败',
  INACTIVE: '未激活',
  IDLE: '空闲',
};

export default function StatusBadge({ value }: { value: string | null | undefined }) {
  const status = value || 'UNKNOWN';
  let badge: 'success' | 'processing' | 'error' | 'default' | 'warning' = 'default';

  if (['ACTIVE', 'READY', 'RUNNING', 'IDLE'].includes(status)) {
    badge = 'success';
  } else if (['SUBMITTED', 'PENDING'].includes(status)) {
    badge = 'processing';
  } else if (status === 'FAILED' || status === 'ERROR') {
    badge = 'error';
  } else if (status === 'WARNING') {
    badge = 'warning';
  }

  return (
    <Tooltip title={status}>
      <Badge status={badge} text={labels[status] || status} />
    </Tooltip>
  );
}
