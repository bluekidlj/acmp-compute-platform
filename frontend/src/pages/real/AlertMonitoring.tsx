import { useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import dayjs from 'dayjs';
import { api } from '../../api/real';
import PageHeader from '../../components/PageHeader';
import type { PhysicalCluster } from '../../types';

type AlertSeverity = 'WARNING' | 'CRITICAL';

interface AlertRuleDraft {
  name: string;
  clusterId: string;
  severity: AlertSeverity;
  expression: string;
  durationMinutes: number;
  summary: string;
}

interface AlertRule extends AlertRuleDraft {
  id: string;
  clusterName: string;
  enabled: boolean;
  createdAt: string;
}

interface AlertEvent {
  id: string;
  ruleName: string;
  severity: AlertSeverity;
  status: 'FIRING' | 'RESOLVED';
  target: string;
  value: string;
  startsAt: string;
  endsAt: string | null;
  summary: string;
}

const ALERT_RULE_STORAGE_KEY = 'acmp-monitoring-alert-rules';
const ALERT_EVENT_STORAGE_KEY = 'acmp-monitoring-alert-events';

const PROMQL_EXAMPLES = [
  {
    label: 'GPU 利用率持续过高',
    value: 'avg by (Hostname, UUID) (DCGM_FI_DEV_GPU_UTIL) > 90',
  },
  {
    label: 'GPU 显存使用率过高',
    value: '100 * DCGM_FI_DEV_FB_USED / (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE) > 90',
  },
  {
    label: 'Kubernetes 节点不可用',
    value: 'kube_node_status_condition{condition="Ready",status="true"} == 0',
  },
  {
    label: 'vLLM 存在等待请求',
    value: 'vllm:num_requests_waiting > 0',
  },
];

function readLocalItems<T>(key: string): T[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || '[]') as T[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveLocalItems<T>(key: string, values: T[]) {
  localStorage.setItem(key, JSON.stringify(values));
}

function severityTag(value: AlertSeverity) {
  return <Tag color={value === 'CRITICAL' ? 'red' : 'orange'}>{value === 'CRITICAL' ? '严重' : '警告'}</Tag>;
}

export default function AlertMonitoringPage() {
  const [form] = Form.useForm<AlertRuleDraft>();
  const [clusters, setClusters] = useState<PhysicalCluster[]>([]);
  const [rules, setRules] = useState<AlertRule[]>(readLocalItems(ALERT_RULE_STORAGE_KEY));
  const [events] = useState<AlertEvent[]>(readLocalItems(ALERT_EVENT_STORAGE_KEY));
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(function loadClusters() {
    api.clusters()
      .then(setClusters)
      .catch(error => message.error(error instanceof Error ? error.message : '集群加载失败'));
  }, []);

  function openCreateDrawer() {
    form.resetFields();
    form.setFieldsValue({
      severity: 'WARNING',
      durationMinutes: 5,
    });
    setDrawerOpen(true);
  }

  function createRule(values: AlertRuleDraft) {
    const cluster = clusters.find(item => item.id === values.clusterId);
    const nextRule: AlertRule = {
      ...values,
      id: `alert-rule-${Date.now()}`,
      clusterName: cluster?.name || values.clusterId,
      enabled: true,
      createdAt: dayjs().format('YYYY-MM-DD HH:mm:ss'),
    };
    const nextRules = [nextRule, ...rules];
    saveLocalItems(ALERT_RULE_STORAGE_KEY, nextRules);
    setRules(nextRules);
    setDrawerOpen(false);
    message.success('告警规则已保存并启用');
  }

  function toggleRule(rule: AlertRule, enabled: boolean) {
    const nextRules = rules.map(item => item.id === rule.id ? { ...item, enabled } : item);
    saveLocalItems(ALERT_RULE_STORAGE_KEY, nextRules);
    setRules(nextRules);
    message.success(enabled ? '告警规则已启用' : '告警规则已停用');
  }

  function deleteRule(ruleId: string) {
    const nextRules = rules.filter(item => item.id !== ruleId);
    saveLocalItems(ALERT_RULE_STORAGE_KEY, nextRules);
    setRules(nextRules);
    message.success('告警规则已删除');
  }

  const ruleTable = (
    <div className="surface data-table">
      <div className="toolbar">
        <span>PromQL 规则由 Prometheus 执行，持续时间内表达式始终成立后触发告警。</span>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateDrawer}>新增告警</Button>
      </div>
      <Table
        rowKey="id"
        dataSource={rules}
        pagination={false}
        locale={{ emptyText: '暂无告警规则' }}
        columns={[
          { title: '规则名称', dataIndex: 'name', render: value => <strong>{value}</strong> },
          { title: '集群', dataIndex: 'clusterName' },
          { title: '级别', dataIndex: 'severity', render: severityTag },
          { title: 'PromQL', dataIndex: 'expression', ellipsis: true, width: 360 },
          { title: '持续时间', dataIndex: 'durationMinutes', render: value => `${value} 分钟` },
          { title: '状态', dataIndex: 'enabled', render: value => <Tag color={value ? 'green' : 'default'}>{value ? '已启用' : '已停用'}</Tag> },
          {
            title: '操作',
            width: 180,
            render: (_, rule) => (
              <Space>
                <Switch size="small" checked={rule.enabled} checkedChildren="启用" unCheckedChildren="停用" onChange={enabled => toggleRule(rule, enabled)} />
                <Popconfirm title="确认删除该告警规则？" onConfirm={() => deleteRule(rule.id)}>
                  <Button size="small" danger>删除</Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </div>
  );

  const eventTable = (
    <div className="surface data-table">
      <Alert
        type="info"
        showIcon
        message="告警记录将在接入 Alertmanager Webhook 后按发生时间倒序展示。"
        className="alert-monitoring-note"
      />
      <Table
        rowKey="id"
        dataSource={[...events].sort((left, right) => right.startsAt.localeCompare(left.startsAt))}
        pagination={false}
        locale={{ emptyText: '暂无告警记录' }}
        columns={[
          { title: '发生时间', dataIndex: 'startsAt', width: 180 },
          { title: '恢复时间', dataIndex: 'endsAt', width: 180, render: value => value || '-' },
          { title: '规则名称', dataIndex: 'ruleName' },
          { title: '级别', dataIndex: 'severity', render: severityTag },
          { title: '状态', dataIndex: 'status', render: value => <Tag color={value === 'FIRING' ? 'red' : 'green'}>{value === 'FIRING' ? '告警中' : '已恢复'}</Tag> },
          { title: '告警对象', dataIndex: 'target' },
          { title: '当前值', dataIndex: 'value' },
          { title: '信息', dataIndex: 'summary', ellipsis: true },
        ]}
      />
    </div>
  );

  return (
    <div>
      <PageHeader title="监控告警" subtitle="配置 PromQL 告警规则并查看 Alertmanager 告警记录" tags={[{ label: 'Prometheus', color: 'blue' }, { label: 'Alertmanager', color: 'purple' }]} />
      <Tabs items={[
        { key: 'rules', label: `告警规则（${rules.length}）`, children: ruleTable },
        { key: 'events', label: `告警记录（${events.length}）`, children: eventTable },
      ]} />

      <Drawer title="新增告警规则" width={680} open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <Alert
          type="info"
          showIcon
          message="配置方法"
          description="选择监控集群，填写返回布尔结果的 PromQL 表达式，并设置持续时间。例如持续5分钟 GPU 利用率大于90%时触发。"
          className="alert-monitoring-note"
        />
        <Form form={form} layout="vertical" onFinish={createRule}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：GPU 利用率持续过高" />
          </Form.Item>
          <Form.Item name="clusterId" label="监控集群" rules={[{ required: true, message: '请选择集群' }]}>
            <Select options={clusters.map(item => ({ label: item.name, value: item.id }))} />
          </Form.Item>
          <Form.Item name="severity" label="告警级别" rules={[{ required: true }]}>
            <Select options={[{ label: '警告', value: 'WARNING' }, { label: '严重', value: 'CRITICAL' }]} />
          </Form.Item>
          <Form.Item label="常用规则模板">
            <Select
              placeholder="选择模板后自动填入 PromQL，可继续修改"
              options={PROMQL_EXAMPLES}
              onChange={value => form.setFieldValue('expression', value)}
            />
          </Form.Item>
          <Form.Item
            name="expression"
            label="PromQL 表达式"
            rules={[{ required: true, message: '请输入 PromQL 表达式' }]}
            extra="指标名称和标签必须以当前集群 Prometheus 中实际存在的数据为准。"
          >
            <Input.TextArea rows={5} className="promql-editor" placeholder='例如：DCGM_FI_DEV_GPU_UTIL > 90' />
          </Form.Item>
          <Form.Item name="durationMinutes" label="持续时间（分钟）" rules={[{ required: true }]}>
            <InputNumber min={1} max={1440} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="summary" label="告警信息" rules={[{ required: true, message: '请输入告警信息' }]}>
            <Input placeholder="例如：GPU 利用率连续5分钟超过90%" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存并启用</Button>
        </Form>
      </Drawer>
    </div>
  );
}
