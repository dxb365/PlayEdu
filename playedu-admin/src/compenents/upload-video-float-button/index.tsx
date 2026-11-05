import { InboxOutlined } from "@ant-design/icons";
import {
  Button,
  Col,
  message,
  Modal,
  Progress,
  Row,
  Table,
  Tag,
  Upload,
} from "antd";
import Dragger from "antd/es/upload/Dragger";
import { useEffect, useRef, useState } from "react";
import { generateUUID, parseVideo } from "../../utils";
import styles from "./index.module.less";
import { minioMergeVideo, minioUploadId } from "../../api/upload";
import { UploadChunk } from "../../js/minio-upload-chunk";
import { videoUploadError, VIDEO_UPLOAD_CONCURRENCY } from "../../js/video-upload-limits";
import { useDispatch } from "react-redux";
import { useSelector } from "react-redux";
import { uploadAction } from "../../store/user/loginUserSlice";
import upIcon from "../../assets/images/commen/upload.png";

export const UploadVideoFloatButton = () => {
  const dispatch = useDispatch();
  const [showModal, setShowModal] = useState(false);
  const localFileList = useRef<FileItem[]>([]);
  const pendingFiles = useRef<FileItem[]>([]);
  const activeUploads = useRef(0);
  const generation = useRef(0);
  const intervalId = useRef<number>();
  const intervalId2 = useRef<number>();
  const [successNum, setSuccessNum] = useState(0);
  const [fileList, setFileList] = useState<FileItem[]>([]);
  const uploadStatus = useSelector(
    (state: any) => state.loginUser.value.uploadStatus
  );
  const categoryIds = useSelector(
    (state: any) => state.loginUser.value.uploadCateIds
  );

  const getMinioUploadId = async () => {
    let resp: any = await minioUploadId("mp4");
    return resp.data;
  };

  useEffect(() => {
    if (uploadStatus) {
      setShowModal(true);
      intervalId.current = setInterval(() => {
        let num = localFileList.current.filter(
          (it) => it.upload.status === 7
        ).length;
        setSuccessNum(num);
      }, 5000);
      let timeDiv = document.createElement("div");
      document.body.appendChild(timeDiv);
      intervalId2.current = setInterval(() => {
        timeDiv && timeDiv.click();
      }, 10000);
    } else {
      window.clearInterval(intervalId.current);
      window.clearInterval(intervalId2.current);
      console.log("定时器已销毁");
    }
  }, [uploadStatus]);

  const processQueue = () => {
    while (activeUploads.current < VIDEO_UPLOAD_CONCURRENCY && pendingFiles.current.length) {
      const item = pendingFiles.current.shift()!;
      const currentGeneration = generation.current;
      activeUploads.current++;
      performUpload(item, currentGeneration).finally(() => {
        if (generation.current !== currentGeneration) return;
        activeUploads.current--;
        processQueue();
      });
    }
  };

  const performUpload = (item: FileItem, currentGeneration: number) => new Promise<void>((resolve) => {
    const current = () => generation.current === currentGeneration;
    const refresh = () => { if (current()) setFileList([...localFileList.current]); };
    const fail = (error: any) => {
      item.upload.status = 5;
      item.upload.handler.uploadStatus = 5;
      item.upload.remark = typeof error === "string" ? error : "上传失败，请重试";
      refresh();
      resolve();
    };
    const run = item.upload.handler;
    run.on("progress", (progress: number) => {
      item.upload.status = 3;
      item.upload.progress = Math.min(progress, 99);
      refresh();
    });
    run.on("error", fail);
    run.on("success", async () => {
      if (!current()) { resolve(); return; }
      try {
        await minioMergeVideo(run.filename, run.uploadId, categoryIds.join(","),
          item.file.name, "mp4", item.file.size, item.video?.duration || 0, item.video?.poster || "");
        item.upload.progress = 100;
        item.upload.status = 7;
        refresh();
        resolve();
      } catch (error) {
        fail(error);
      }
    });
    (async () => {
      item.upload.status = 3;
      refresh();
      if (!item.video) item.video = await parseVideo(item.file);
      if (!current()) { resolve(); return; }
      if (!run.uploadId) {
        const data = await getMinioUploadId();
        run.uploadId = data.upload_id;
        run.filename = data.filename;
      }
      if (!current()) { resolve(); return; }
      run.isStop = false;
      run.uploadStatus = 0;
      run.start();
    })().catch(fail);
  });

  const queueFile = (item: FileItem) => {
    if (item.upload.status === 3 || pendingFiles.current.includes(item)) return;
    item.upload.status = 0;
    item.upload.remark = "";
    pendingFiles.current.push(item);
    setFileList([...localFileList.current]);
    processQueue();
  };

  const uploadProps = {
    multiple: true,
    beforeUpload: (file: File, selectedFiles: File[]) => {
      const error = videoUploadError(file, selectedFiles.length);
      if (error) {
        message.error({ content: error, key: selectedFiles.length > 100 ? "video-upload-count" : file.name });
        return Upload.LIST_IGNORE;
      }
      const item: FileItem = {
        id: generateUUID(), file,
        upload: { handler: new UploadChunk(file, "", ""), progress: 0, status: 0, remark: "" },
      };
      localFileList.current.push(item);
      queueFile(item);
      return Upload.LIST_IGNORE;
    },
  };

  const closeWin = () => {
    generation.current++;
    pendingFiles.current = [];
    activeUploads.current = 0;
    if (fileList.length > 0) {
      fileList.forEach((item) => {
        if (item.upload.status !== 5 && item.upload.status !== 7) {
          item.upload.handler.cancel();
        }
      });
    }
    localFileList.current = [];
    setFileList([]);
    setSuccessNum(0);
    setShowModal(false);
    dispatch(
      uploadAction({
        uploadStatus: false,
        uploadCateIds: [],
      })
    );
  };

  return (
    <>
      {uploadStatus ? (
        <>
          <div
            style={{ display: showModal ? "none" : "flex" }}
            className={styles["float-button"]}
            onClick={() => setShowModal(true)}
          >
            <img src={upIcon} />
            <span>
              视频上传成功 ({successNum}/{fileList.length})
            </span>
          </div>
          <Modal
            width={800}
            title="上传视频"
            open={showModal}
            maskClosable={false}
            footer={null}
            onCancel={() => {
              closeWin();
            }}
          >
            <Row gutter={[0, 10]}>
              <Col span={24}>
                <Dragger {...uploadProps}>
                  <p className="ant-upload-drag-icon">
                    <InboxOutlined />
                  </p>
                  <p className="ant-upload-text">请将视频拖拽到此处上传</p>
                  <p className="ant-upload-hint">
                    单次最多上传100个视频 / 单个最大10GB / 同时上传10个 / 支持mp4格式
                  </p>
                </Dragger>
              </Col>
              <Col span={24}>
                <Table
                  pagination={false}
                  rowKey="id"
                  columns={[
                    {
                      title: "视频",
                      dataIndex: "name",
                      key: "name",
                      render: (_, record) => <span>{record.file.name}</span>,
                    },
                    {
                      title: "大小",
                      dataIndex: "size",
                      key: "size",
                      render: (_, record) => (
                        <span>
                          {(record.file.size / 1024 / 1024).toFixed(2)}M
                        </span>
                      ),
                    },
                    {
                      title: "进度",
                      dataIndex: "progress",
                      key: "progress",
                      render: (_, record: FileItem) => (
                        <>
                          {record.upload.status === 0 ? (
                            "等待上传"
                          ) : (
                            <Progress
                              size="small"
                              steps={20}
                              percent={record.upload.progress}
                            />
                          )}
                        </>
                      ),
                    },
                    {
                      title: "操作",
                      key: "action",
                      render: (_, record) => (
                        <>
                          {record.upload.status === 5 ? (
                            <>
                              <Button
                                type="link"
                                size="small"
                                className="b-n-link c-red"
                                onClick={() => {
                                  queueFile(record);
                                }}
                              >
                                失败.重试
                              </Button>
                            </>
                          ) : null}

                          {record.upload.status === 7 ? (
                            <Tag color="success">上传成功</Tag>
                          ) : null}
                        </>
                      ),
                    },
                  ]}
                  dataSource={fileList}
                />
              </Col>
              <Col span={24}>
                <div className="r-r-flex">
                  <Button
                    type="primary"
                    onClick={() => {
                      closeWin();
                    }}
                  >
                    完成
                  </Button>
                  <Button
                    type="default"
                    className="mr-16"
                    onClick={() => {
                      setShowModal(false);
                    }}
                  >
                    最小化
                  </Button>
                </div>
              </Col>
            </Row>
          </Modal>
        </>
      ) : null}
    </>
  );
};
