import { useEffect, useState } from "react";
import { Alert, Button, Input, Modal, Progress, Space, Table, Tag, message } from "antd";
import type { ColumnsType } from "antd/es/table";
import { resource } from "../../../../../api";

type Entry = {
  name: string;
  path: string;
  directory: boolean;
  size: number;
  extension: string;
  imported: boolean;
};

type Props = {
  open: boolean;
  categoryIds: number[];
  onCancel: () => void;
  onImported: () => void;
};

export const NasImportDialog = ({ open, categoryIds, onCancel, onImported }: Props) => {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [path, setPath] = useState("");
  const [parent, setParent] = useState("");
  const [name, setName] = useState("");
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [done, setDone] = useState(0);
  const [count, setCount] = useState(0);
  const [errors, setErrors] = useState<string[]>([]);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [truncated, setTruncated] = useState(false);

  const load = async (nextPath: string, nextPage = 1, query = name) => {
    setLoading(true);
    setSelected([]);
    try {
      const res: any = await resource.nasLibraryBrowse(nextPath, nextPage, query);
      setEntries(res.data.entries);
      setPath(res.data.path);
      setParent(res.data.parent);
      setPage(nextPage);
      setTotal(res.data.total);
      setTruncated(res.data.truncated);
    } catch (error: any) {
      message.error(error?.msg || "无法读取视频目录，请检查目录挂载与读取权限");
    } finally {
      setLoading(false);
    }
  };

  const enter = (nextPath: string) => {
    setName("");
    load(nextPath, 1, "");
  };

  useEffect(() => {
    if (!open) return;
    setErrors([]);
    setWarnings([]);
    setDone(0);
    setCount(0);
    setName("");
    load("", 1, "");
  }, [open]);

  const add = async () => {
    setImporting(true);
    setErrors([]);
    setWarnings([]);
    setDone(0);
    setCount(selected.length);
    let created = 0;
    const failures: string[] = [];
    const notices: string[] = [];
    try {
      for (let index = 0; index < selected.length; index++) {
        const source = selected[index];
        try {
          const res: any = await resource.importNasVideo(source, categoryIds);
          if (res.data.created) created++;
          if (res.data.warning) notices.push(source + "：" + res.data.warning);
        } catch (error: any) {
          failures.push(source + "：" + (error?.msg || "添加失败，请稍后重试"));
        }
        setDone(index + 1);
      }
      setErrors(failures);
      setWarnings(notices);
      if (created > 0) {
        message.success("已添加 " + created + " 个视频引用");
        onImported();
      } else if (failures.length === 0) {
        message.info("所选视频已添加过");
      }
      await load(path);
    } finally {
      setImporting(false);
    }
  };

  const columns: ColumnsType<Entry> = [
    {
      title: "名称", dataIndex: "name",
      render: (value, entry) => entry.directory
        ? <Button type="link" disabled={importing} onClick={() => enter(entry.path)}>📁 {value}</Button>
        : <span>{value} {entry.imported && <Tag color="green">已添加</Tag>}</span>,
    },
    { title: "格式", width: 90, render: (_, entry) => entry.directory ? "—" : entry.extension },
    {
      title: "大小", width: 120,
      render: (_, entry) => entry.directory ? "文件夹"
        : (entry.size / 1024 / 1024).toFixed(1) + " MB",
    },
  ];

  return <Modal title="从 NAS 视频库添加" open={open} width={940}
    maskClosable={!importing} closable={!importing} keyboard={!importing} destroyOnClose
    onCancel={onCancel}
    footer={[
      <Button key="cancel" disabled={importing} onClick={onCancel}>关闭</Button>,
      <Button key="add" type="primary" loading={importing} disabled={selected.length === 0}
        onClick={add}>添加所选视频（{selected.length}）</Button>,
    ]}>
    <Alert type="info" showIcon style={{ marginBottom: 16 }}
      message="只添加课程引用，视频保留在 NAS 原目录。请保持源文件位置不变。" />
    <Space wrap style={{ marginBottom: 12 }}>
      <Button disabled={importing || !path} onClick={() => enter("")}>视频库根目录</Button>
      <Button disabled={importing || !path} onClick={() => enter(parent)}>返回上级</Button>
      <span>当前位置：/{path}</span>
    </Space>
    <Space wrap style={{ marginBottom: 16 }}>
      <Input.Search placeholder="在当前目录按名称查找" value={name} disabled={importing}
        onChange={event => setName(event.target.value)}
        onSearch={value => load(path, 1, value)} style={{ width: 300 }} allowClear />
    </Space>
    {truncated && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
      message="当前目录条目较多，请输入文件名筛选或进入更细分的目录。" />}
    {count > 0 && <Progress percent={Math.round(done * 100 / count)}
      format={() => done + " / " + count} />}
    {errors.length > 0 && <Alert type="error" showIcon style={{ marginBottom: 12 }}
      message="以下视频未添加" description={errors.map((text, index) => <div key={index}>{text}</div>)} />}
    {warnings.length > 0 && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
      message="请先预览这些原格式视频" description={warnings.map((text, index) => <div key={index}>{text}</div>)} />}
    <Table<Entry> rowKey="path" columns={columns} dataSource={entries}
      loading={loading} size="small"
      rowSelection={{
        selectedRowKeys: selected,
        getCheckboxProps: entry => ({
          disabled: importing || entry.directory || entry.imported,
        }),
        onChange: keys => {
          if (keys.length > 20) {
            message.info("每次最多选择 20 个视频");
            return;
          }
          setSelected(keys.map(String));
        },
      }}
      pagination={{ current: page, pageSize: 50, total, showSizeChanger: false, disabled: importing,
        onChange: value => load(path, value) }} />
  </Modal>;
};
