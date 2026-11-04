import { useEffect, useState } from "react";
import { Alert, Modal, TreeSelect, message } from "antd";
import { resource, resourceCategory } from "../../../../../api";

export type VideoBulkOperation = "move" | "copy" | "categories";

type CategoryOption = { title: string; value: number; children?: CategoryOption[] };
type Category = { id: number; name: string };
type Props = {
  operation: VideoBulkOperation | null;
  ids: number[];
  onCancel: () => void;
  onSuccess: () => void;
  onBusy: (busy: boolean) => void;
};

const titles = { move: "批量移动", copy: "批量复制", categories: "批量改分类" };

export const VideoBulkDialog = ({ operation, ids, onCancel, onSuccess, onBusy }: Props) => {
  const [options, setOptions] = useState<CategoryOption[]>([]);
  const [categoryIds, setCategoryIds] = useState<number[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    if (!operation) return;
    let active = true;
    setCategoryIds(operation === "categories" ? [] : [0]);
    setOptions([]);
    setLoading(true);
    setLoadFailed(false);
    resourceCategory.resourceCategoryList().then((response: any) => {
      const groups: Record<string, Category[]> = response.data.categories || {};
      const build = (parent: number): CategoryOption[] => (groups[parent] || []).map(category => ({
        title: category.name,
        value: category.id,
        children: build(category.id),
      }));
      if (active) setOptions(build(0));
    }).catch(() => {
      if (active) {
        setLoadFailed(true);
        message.error("无法读取分类，请关闭后重试");
      }
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [operation]);

  const submit = async () => {
    if (!operation || ids.length === 0 || loading || loadFailed || saving) return;
    setSaving(true);
    onBusy(true);
    try {
      const response: any = await resource.bulkVideos(operation, ids, categoryIds);
      message.success(titles[operation] + "成功，共 " + response.data.affected + " 个视频");
      onSuccess();
    } catch (error: any) {
      message.error(error?.msg || "操作失败，请刷新列表确认结果后重试");
    } finally {
      setSaving(false);
      onBusy(false);
    }
  };

  return <Modal title={operation ? titles[operation] : "批量操作"} open={operation !== null}
    onCancel={onCancel} onOk={submit} confirmLoading={saving}
    okButtonProps={{ disabled: loading || loadFailed || ids.length === 0 }}
    cancelButtonProps={{ disabled: saving }} closable={!saving} keyboard={!saving}
    maskClosable={!saving} destroyOnClose>
    <p>已选择 {ids.length} 个视频</p>
    <Alert type="info" showIcon style={{ marginBottom: 16 }} message={operation === "copy"
      ? "在目标分类创建视频引用。复制后可分别管理引用。"
      : "所选视频的分类将替换为本次选择。"} />
    <TreeSelect style={{ width: "100%" }} loading={loading} disabled={saving || loading || loadFailed}
      multiple={operation === "categories"} allowClear={operation === "categories"}
      treeDefaultExpandAll showSearch treeNodeFilterProp="title"
      treeData={operation === "categories" ? options : [{ title: "未分类", value: 0 }, ...options]}
      value={operation === "categories" ? categoryIds : categoryIds[0]}
      placeholder="请选择目标分类"
      onChange={(value: number | number[] | undefined) => setCategoryIds(
        value === undefined ? [] : Array.isArray(value) ? value : [value])} />
    {operation === "categories" && <p style={{ marginTop: 12 }}>可选择多个分类，清空选择表示设为未分类。</p>}
  </Modal>;
};
