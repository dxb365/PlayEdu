import { useEffect, useState } from "react";
import {
  Modal,
  Table,
  message,
  Space,
  Dropdown,
  Typography,
  Input,
  Button,
  Tag,
  Select,
} from "antd";
import type { MenuProps } from "antd";
import { resource } from "../../../api";
import { useLocation } from "react-router-dom";
import { DownOutlined, ExclamationCircleFilled } from "@ant-design/icons";
import type { ColumnsType } from "antd/es/table";
import { dateFormat } from "../../../utils/index";
import { TreeCategory, DurationText } from "../../../compenents";
import { UploadVideoButton } from "../../../compenents/upload-video-button";
import { VideoPlayDialog } from "./compenents/video-play-dialog";
import { VideosUpdateDialog } from "./compenents/update-dialog";
import { NasImportDialog } from "./compenents/nas-import-dialog";
import { VideoBulkDialog } from "./compenents/bulk-dialog";
import type { VideoBulkOperation } from "./compenents/bulk-dialog";

const { confirm } = Modal;

interface DataType {
  id: React.Key;
  admin_id: number;
  created_at: string;
  updated_at: string;
  disk: string;
  extension: string;
  file_id: string;
  name: string;
  parent_id: number;
  path: string;
  size: number;
  type: string;
  url: string;
}

type VideosExtraModel = {
  [key: number]: VideoModel;
};

type VideoModel = {
  duration: number;
  poster: string;
  rid: number;
};

type AdminUsersModel = {
  [key: number]: string;
};

const ResourceVideosPage = () => {
  const result = new URLSearchParams(useLocation().search);
  const [videoList, setVideoList] = useState<DataType[]>([]);
  const [videosExtra, setVideoExtra] = useState<VideosExtraModel>({});
  const [adminUsers, setAdminUsers] = useState<AdminUsersModel>({});
  const [resourceUrl, setResourceUrl] = useState<ResourceUrlModel>({});
  const [refresh, setRefresh] = useState(false);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [category_ids, setCategoryIds] = useState<number[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<any>([]);
  const [selLabel, setLabel] = useState<string>(
    result.get("label") ? String(result.get("label")) : "全部视频"
  );
  const [cateId, setCateId] = useState(Number(result.get("cid")));
  const [updateVisible, setUpdateVisible] = useState(false);
  const [playVisible, setPlayeVisible] = useState(false);
  const [multiConfig, setMultiConfig] = useState(false);
  const [updateId, setUpdateId] = useState(0);
  const [playUrl, setPlayUrl] = useState("");
  const [title, setTitle] = useState("");
  const [nasEnabled, setNasEnabled] = useState(false);
  const [nasVisible, setNasVisible] = useState(false);
  const [bulkOperation, setBulkOperation] = useState<VideoBulkOperation | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [sort, setSort] = useState("created_at:desc");

  useEffect(() => {
    resource.nasLibraryStatus().then((res: any) => {
      setNasEnabled(Boolean(res.data.enabled));
    }).catch(() => setNasEnabled(false));
  }, []);

  useEffect(() => {
    setCateId(Number(result.get("cid")));
    if (Number(result.get("cid"))) {
      let arr = [];
      arr.push(Number(result.get("cid")));
      setCategoryIds(arr);
    }
  }, [result.get("cid")]);

  const columns: ColumnsType<DataType> = [
    {
      title: "视频名称",
      dataIndex: "name",
      render: (text: string, record: DataType) => (
        <div className="d-flex">
          <i
            className="iconfont icon-icon-video"
            style={{
              fontSize: 16,
              color: "rgba(0,0,0,0.3)",
            }}
          />
          <span className="ml-8">{text}</span>
          {record.disk === "nas" &&
            <Tag className="ml-8" color="blue">
              NAS 原目录
            </Tag>}
        </div>
      ),
    },
    {
      title: "视频时长",
      dataIndex: "id",
      render: (id: number) => (
        <DurationText duration={videosExtra[id].duration}></DurationText>
      ),
    },
    {
      title: "创建人",
      dataIndex: "admin_id",
      render: (admin_id: number) =>
        JSON.stringify(adminUsers) !== "{}" && (
          <span>{adminUsers[admin_id]}</span>
        ),
    },
    {
      title: "创建时间",
      dataIndex: "created_at",
      render: (created_at: string) => <span>{dateFormat(created_at)}</span>,
    },
    {
      title: "资源修改时间",
      dataIndex: "updated_at",
      render: (updatedAt: string) => <span>{updatedAt ? dateFormat(updatedAt) : "—"}</span>,
    },
    {
      title: "操作",
      key: "action",
      fixed: "right",
      width: 160,
      render: (_, record: any) => {
        const items: MenuProps["items"] = [
          {
            key: "1",
            label: (
              <Button
                type="link"
                className="b-link c-red"
                onClick={() => {
                  setUpdateId(record.id);
                  setUpdateVisible(true);
                }}
              >
                编辑
              </Button>
            ),
          },
          {
            key: "2",
            label: (
              <Button
                type="link"
                className="b-link c-red"
                onClick={() => removeResource(record.id)}
              >
                删除
              </Button>
            ),
          },
        ];

        return (
          <Space size="small">
            <Button
              type="link"
              size="small"
              className="b-n-link c-red"
              onClick={() => {
                setUpdateId(record.id);
                setPlayUrl(resourceUrl[record.id]);
                setPlayeVisible(true);
              }}
            >
              预览
            </Button>
            <div className="form-column"></div>
            <Dropdown menu={{ items }}>
              <Button
                type="link"
                className="b-link c-red"
                onClick={(e) => e.preventDefault()}
              >
                <Space size="small" align="center">
                  更多
                  <DownOutlined />
                </Space>
              </Button>
            </Dropdown>
          </Space>
        );
      },
    },
  ];

  // 删除图片
  const removeResource = (id: number) => {
    if (id === 0) {
      return;
    }
    confirm({
      title: "操作确认",
      icon: <ExclamationCircleFilled />,
      content: videoList.find(video => video.id === id)?.disk === "nas"
        ? "删除前请检查视频无关联课程。本操作只删除网站引用，NAS 原视频保留。确认删除？"
        : "删除前请检查选中视频文件无关联课程，确认删除？",
      centered: true,
      okText: "确认",
      cancelText: "取消",
      onOk() {
        resource.destroyResource(id).then(() => {
          message.success("删除成功");
          resetVideoList();
        });
      },
      onCancel() {
        console.log("Cancel");
      },
    });
  };

  const removeResourceMulti = () => {
    if (selectedRowKeys.length === 0 || bulkBusy) {
      return;
    }
    confirm({
      title: "操作确认",
      icon: <ExclamationCircleFilled />,
      content: "删除前请检查选中视频无关联课程。NAS 视频只删除网站引用，原视频保留；上传的视频按原规则删除。确认继续？",
      centered: true,
      okText: "确认",
      cancelText: "取消",
      async onOk() {
        setBulkBusy(true);
        try {
          await resource.destroyResourceMulti(selectedRowKeys);
          message.success("删除成功");
          resetVideoList();
        } catch (error: any) {
          message.error(error?.msg || "删除失败，请刷新列表确认结果后重试");
          throw error;
        } finally {
          setBulkBusy(false);
        }
      },
      onCancel() {
        console.log("Cancel");
      },
    });
  };

  // 获取视频列表
  const getVideoList = () => {
    setLoading(true);
    let categoryIds = category_ids.join(",");
    resource
      .resourceList(page, size, sort.split(":")[0], sort.split(":")[1], title, "VIDEO", categoryIds)
      .then((res: any) => {
        setTotal(res.data.result.total);
        setVideoList(res.data.result.data);
        setVideoExtra(res.data.videos_extra);
        setAdminUsers(res.data.admin_users);
        setResourceUrl(res.data.resource_url);
        setLoading(false);
      })
      .catch((err: any) => {
        console.log("错误,", err);
      });
  };

  // 重置列表
  const resetVideoList = () => {
    setPage(1);
    setSize(10);
    setVideoList([]);
    setSelectedRowKeys([]);
    setTitle("");
    setRefresh(!refresh);
  };

  // 加载视频列表
  useEffect(() => {
    getVideoList();
  }, [category_ids, refresh, page, size, sort]);

  const paginationProps = {
    current: page, //当前页码
    pageSize: size,
    total: total, // 总条数
    onChange: (page: number, pageSize: number) =>
      handlePageChange(page, pageSize), //改变页码的函数
    showSizeChanger: true,
    pageSizeOptions: [10, 20, 50, 100, 200],
    disabled: bulkBusy,
  };

  const handlePageChange = (page: number, pageSize: number) => {
    setPage(page);
    setSize(pageSize);
  };

  const rowSelection = {
    selectedRowKeys,
    preserveSelectedRowKeys: true,
    getCheckboxProps: () => ({ disabled: bulkBusy }),
    onChange: (keys: React.Key[]) => {
      if (keys.length > 200) {
        message.warning("每次最多选择 200 个视频");
        return;
      }
      setSelectedRowKeys(keys.map(Number));
    },
  };

  return (
    <>
      <div className="tree-main-body">
        <div className="left-box">
          <TreeCategory
            selected={category_ids}
            type="no-cate"
            text={"视频"}
            onUpdate={(keys: any, title: any) => {
              if (bulkBusy) return;
              setSelectedRowKeys([]);
              setPage(1);
              setCategoryIds(keys);
              if (typeof title === "string") {
                setLabel(title);
              } else {
                setLabel(title.props.children[0]);
              }
            }}
          />
        </div>
        <div className="right-box">
          <div className="d-flex playedu-main-title float-left mb-24">
            视频 | {selLabel}
          </div>
          <div className="float-left  j-b-flex  mb-24">
            <div>
              <UploadVideoButton
                categoryIds={category_ids}
                onUpdate={() => {
                  resetVideoList();
                }}
              ></UploadVideoButton>
              {nasEnabled && <Button className="ml-16" onClick={() => setNasVisible(true)}>
                从 NAS 视频库添加
              </Button>}
              <Button
                type="default"
                className="ml-16"
                disabled={bulkBusy}
                onClick={() => {
                  setSelectedRowKeys([]);
                  setMultiConfig(!multiConfig);
                }}
              >
                {multiConfig ? "取消操作" : "批量操作"}
              </Button>
            </div>
            <div className="d-flex">
              <Select aria-label="视频列表排序" value={sort} disabled={bulkBusy} className="mr-16"
                style={{ width: 200 }} options={[
                  { value: "created_at:desc", label: "默认：最近添加" },
                  { value: "name:asc", label: "名称：从小到大" },
                  { value: "name:desc", label: "名称：从大到小" },
                  { value: "updated_at:desc", label: "资源修改时间：从新到旧" },
                  { value: "updated_at:asc", label: "资源修改时间：从旧到新" },
                ]} onChange={value => { setSort(value); setPage(1); }} />
              <div className="d-flex mr-24">
                <Typography.Text>名称：</Typography.Text>
                <Input
                  value={title}
                  onChange={(e) => {
                    setTitle(e.target.value);
                  }}
                  allowClear
                  style={{ width: 160 }}
                  placeholder="请输入名称关键字"
                />
              </div>
              <div className="d-flex">
                <Button className="mr-16" onClick={resetVideoList}>
                  重 置
                </Button>
                <Button
                  type="primary"
                  onClick={() => {
                    setPage(1);
                    setRefresh(!refresh);
                  }}
                >
                  查 询
                </Button>
              </div>
            </div>
          </div>
          <div className="float-left">
            {multiConfig && selectedRowKeys.length > 0 && <div role="toolbar" aria-label="视频批量操作"
              style={{ position: "sticky", top: 0, zIndex: 5, background: "#fff", padding: 16,
                marginBottom: 16, border: "1px solid #eee", borderRadius: 8 }}>
              <Space wrap>
                <span>已选择 {selectedRowKeys.length} / 200 个视频</span>
                <Button disabled={bulkBusy} onClick={() => setBulkOperation("move")}>批量移动</Button>
                <Button disabled={bulkBusy} onClick={() => setBulkOperation("copy")}>批量复制</Button>
                <Button disabled={bulkBusy} onClick={() => setBulkOperation("categories")}>批量改分类</Button>
                <Button danger disabled={bulkBusy} onClick={removeResourceMulti}>批量删除</Button>
                <Button type="link" disabled={bulkBusy} onClick={() => setSelectedRowKeys([])}>清空选择</Button>
              </Space>
            </div>}
            {multiConfig ? (
              <Table
                rowSelection={{
                  type: "checkbox",
                  ...rowSelection,
                }}
                columns={columns}
                dataSource={videoList}
                loading={loading}
                pagination={paginationProps}
                rowKey={(record) => record.id}
              />
            ) : (
              <Table
                columns={columns}
                dataSource={videoList}
                loading={loading}
                pagination={paginationProps}
                rowKey={(record) => record.id}
              />
            )}
          </div>
        </div>
        <NasImportDialog open={nasVisible} categoryIds={category_ids}
          onCancel={() => setNasVisible(false)} onImported={resetVideoList} />
        <VideoBulkDialog operation={bulkOperation} ids={selectedRowKeys.map(Number)}
          onBusy={setBulkBusy} onCancel={() => setBulkOperation(null)}
          onSuccess={() => { setBulkOperation(null); resetVideoList(); }} />
        <VideoPlayDialog
          id={Number(updateId)}
          open={playVisible}
          url={playUrl}
          onCancel={() => setPlayeVisible(false)}
        ></VideoPlayDialog>
        <VideosUpdateDialog
          id={Number(updateId)}
          open={updateVisible}
          onCancel={() => setUpdateVisible(false)}
          onSuccess={() => {
            setUpdateVisible(false);
            setRefresh(!refresh);
          }}
        ></VideosUpdateDialog>
      </div>
    </>
  );
};

export default ResourceVideosPage;
