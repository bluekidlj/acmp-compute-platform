import React from 'react';
import { Typography, Space, Tag } from 'antd';

const { Title, Text } = Typography;

interface Props {
  title: string;
  subtitle?: string;
  extra?: React.ReactNode;
  tags?: { label: string; color?: string }[];
}

export default function PageHeader({ title, subtitle, extra, tags }: Props) {
  return (
    <div
      style={{
        background: '#fff',
        borderRadius: 8,
        padding: '20px 24px',
        marginBottom: 16,
        border: '1px solid #E5EBE7',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
    >
      <div>
        <Space align="center" size={12}>
          <Title level={4} style={{ margin: 0 }}>{title}</Title>
          {tags?.map((t, i) => <Tag key={i} color={t.color}>{t.label}</Tag>)}
        </Space>
        {subtitle && (
          <Text type="secondary" style={{ fontSize: 13, display: 'block', marginTop: 4 }}>
            {subtitle}
          </Text>
        )}
      </div>
      <div>{extra}</div>
    </div>
  );
}